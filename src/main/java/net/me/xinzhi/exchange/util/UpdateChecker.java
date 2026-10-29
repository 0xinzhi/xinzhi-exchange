package net.me.xinzhi.exchange.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 启动时向 GitHub Releases 查一次新版本（腐竹 2026-10-28「这个子附属能自动更新吗」）。
 *
 * <p>三段链路：
 * <ol>
 *   <li><b>出包端</b> .github/workflows/release.yml：推 v* tag 由 Actions 编译发 Release；</li>
 *   <li><b>检查端</b>（本类 start）：启动时查 releases/latest，新版下到 plugins/update/；</li>
 *   <li><b>安装端</b>（本类 installOnShutdown）：正常关服时派一个脱离的替换脚本，
 *       等 JVM 退出（jar 解锁）后把新 jar 覆盖插件本体，下次启动即新版。</li>
 * </ol>
 *
 * <p>两条安全线：<b>绝不热替换正在运行的 jar</b>（版本和 Slimefun 核心不配套会让服直接起不来）；
 * 替换只发生在关服之后，且脚本自带重试，失败就留着旧 jar（服还能起）。
 *
 * <p>配置（config.yml）：update.check（默认开）、update.repo、update.download（默认开 = 查到就下到 plugins/update）、
 * update.auto-install（默认开 = 关服时自动换掉旧 jar）、update.interval-minutes（默认 30 = 开服期间每 30 分钟再查一次，
 * 0 = 只在启动时查）。腐竹 2026-10-29：「能否在服务器启动中时就时不时检查是否有新版本呢？也好进行下载下次重启直接替换就行。」
 */
public final class UpdateChecker {

    private UpdateChecker() {}

    /** 已下载待安装的新 jar；为 null 表示这轮没有更新。 */
    private static volatile File pendingJar;

    /** 已知的新版本号（远端最新、且与当前不同）；为 null 表示没有待处理的新版。 */
    private static volatile String pendingVersion;

    /** 本轮已经提示过的玩家，避免每次进服都念一遍同一句。 */
    private static final Set<UUID> notified = new HashSet<>();

    /** 防止两次检查叠在一起（30 分钟的定时器碰上手动 /xzex scan 之类）。 */
    private static volatile boolean checking;

    /** plugins/update 里已经下好的新版 jar 的固定前缀（Release 资产名 xinzhi-exchange-<版本>.jar）。 */
    private static final String ASSET_PREFIX = "xinzhi-exchange-";

    public static void start(JavaPlugin plugin) {
        try {
            if (!plugin.getConfig().getBoolean("update.check", true)) return;
            String repo = plugin.getConfig().getString("update.repo", "0xinzhi/xinzhi-exchange");
            boolean download = plugin.getConfig().getBoolean("update.download", true);
            loadState(plugin);
            // 进服提示一直挂着（配置 update.notify.on-join）：管理员上线时把「有新版」告诉他
            if (plugin.getConfig().getBoolean("update.notify.on-join", true)) {
                Bukkit.getPluginManager().registerEvents(new JoinNotice(), plugin);
            }
            // 上次关服前已经下好、但没换成的新 jar：这次启动就认领它，关服时照常替换
            recoverPending(plugin);

            Runnable task = () -> {
                try {
                    check(plugin, repo, download);
                } catch (Throwable t) {
                    plugin.getLogger().fine("[等价交换] 更新检查失败（不影响使用）: " + t);
                }
            };
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);

            // 开服期间也时不时看一眼（腐竹 2026-10-29）：查到就下到 plugins/update，
            // 下次正常关服直接替换，不用等下一次启服才拿到新版。
            int minutes = plugin.getConfig().getInt("update.interval-minutes", 30);
            if (minutes > 0) {
                long ticks = Math.max(1, minutes) * 60L * 20L;
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, ticks, ticks);
                plugin.getLogger().info("[等价交换] 更新检查：启动时一次，之后每 " + Math.max(1, minutes) + " 分钟一次");
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[等价交换] 更新检查未启动: " + t);
        }
    }

    // ---- 「有新版本」这件事要让服里的人知道（腐竹 m00733） ----

    /**
     * 在线提示：管理员立刻收到；配置 {@code update.notify.players: true} 时全服玩家也收到。
     *
     * <p>腐竹的原话是「怎么让其他玩家知道更新了提醒腐竹去更新呢」——所以默认只提醒
     * 有权限的人（op 或 {@code xzex.update}），玩家广播默认关，免得每台服都被刷屏。
     */
    private static void announce(JavaPlugin plugin) {
        String v = pendingVersion;
        if (v == null) return;
        boolean toPlayers = plugin.getConfig().getBoolean("update.notify.players", false);
        boolean toAdmins = plugin.getConfig().getBoolean("update.notify.admins", true);
        String cur = plugin.getPluginMeta().getVersion();
        if (!toPlayers && !toAdmins) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (notified.contains(p.getUniqueId())) continue;
                    boolean admin = p.isOp() || p.hasPermission("xzex.update");
                    if (admin && toAdmins) {
                        p.sendMessage("§e[等价交换] 服务端有新版本 §f" + v + "§e（当前 §f" + cur
                                + "§e）。新版已备好，重启服务器后自动生效；急的话手动换 jar 也行。");
                        notified.add(p.getUniqueId());
                    } else if (toPlayers) {
                        p.sendMessage("§e[等价交换] 服务器稍后会更新到 §f" + v
                                + "§e，更新后部分物品的等价价格可能变化。");
                        notified.add(p.getUniqueId());
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 玩家进服时补一句（他不在线的时候发生的更新，就在他下次上线时告诉他）。 */
    public static final class JoinNotice implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            Player p = e.getPlayer();
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(UpdateChecker.class);
            String v = pendingVersion;
            if (v == null || plugin == null) return;
            boolean admin = p.isOp() || p.hasPermission("xzex.update");
            boolean toPlayers = plugin.getConfig().getBoolean("update.notify.players", false);
            if (!admin && !toPlayers) return;
            if (!notified.add(p.getUniqueId())) return;
            String cur = plugin.getPluginMeta().getVersion();
            // 进服提示晚 2 秒，别和登录那一屏文字抢
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.sendMessage(admin
                        ? "§e[等价交换] 服务端有新版本 §f" + v + "§e（当前 §f" + cur + "§e），重启后自动生效。"
                        : "§e[等价交换] 服务器稍后会更新到 §f" + v + "§e。");
            }, 40L);
        }
    }

    /**
     * 认领 plugins/update 里已经下好的新版 jar（腐竹 2026-10-29 的场景：
     * 上一轮开着服就下载好了，但关服是被强杀的，替换脚本没跑成；这次启动要接着用）。
     */
    private static void recoverPending(JavaPlugin plugin) {
        try {
            String cur = plugin.getPluginMeta().getVersion();
            File dir = new File(plugin.getDataFolder().getParentFile(), "update");
            // 顺手清掉上次没下完的 .part，半截文件绝不留在 update 里
            File[] parts = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".part"));
            if (parts != null) for (File pf : parts) if (pf.delete()) plugin.getLogger().info("[等价交换] 清掉未下完的 " + pf.getName());
            File[] files = dir.listFiles((d, n) -> n.toLowerCase().startsWith(ASSET_PREFIX) && n.toLowerCase().endsWith(".jar"));
            if (files == null) return;
            File best = null;
            String bestVer = null;
            for (File f : files) {
                String n = f.getName();
                String ver = n.substring(ASSET_PREFIX.length(), n.length() - 4);
                if (!ver.matches("\\d+([._-]\\d+)*")) continue;
                if (compareVersion(ver, cur) <= 0) continue;
                if (!validJar(f)) {   // 不完整的 jar 不认领，否则关服替换会把服弄起不来
                    plugin.getLogger().warning("[等价交换] plugins/update/" + n + " 不完整，跳过（下次检查重新下）");
                    continue;
                }
                if (bestVer == null || compareVersion(ver, bestVer) > 0) {
                    best = f;
                    bestVer = ver;
                }
            }
            if (best == null) return;
            pendingJar = best;
            pendingVersion = bestVer;
            saveState(plugin, bestVer);
            plugin.getLogger().info("[等价交换] plugins/update 里已有新版 " + bestVer + "（当前 " + cur
                    + "），关服时自动替换，下次启动生效");
        } catch (Throwable ignored) {}
    }

    /** 比较点分版本号：a>b 返回正数。非数字段当 0，够用即可。 */
    private static int compareVersion(String a, String b) {
        String[] x = a.split("[._-]");
        String[] y = b.split("[._-]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static File stateFile(JavaPlugin plugin) {
        return new File(plugin.getDataFolder(), "update-state.yml");
    }

    /** 把「待安装的新版号」落盘：重启后仍然知道有新版没装。 */
    private static void saveState(JavaPlugin plugin, String version) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("pending-version", version);
            y.save(stateFile(plugin));
        } catch (Throwable ignored) {}
    }

    private static void loadState(JavaPlugin plugin) {
        try {
            File f = stateFile(plugin);
            if (!f.isFile()) return;
            String v = YamlConfiguration.loadConfiguration(f).getString("pending-version", null);
            if (v == null || v.isEmpty()) return;
            if (v.equalsIgnoreCase(plugin.getPluginMeta().getVersion())) {
                saveState(plugin, null); // 已经装上了，清掉
                return;
            }
            pendingVersion = v;
        } catch (Throwable ignored) {}
    }

    private static void check(JavaPlugin plugin, String repo, boolean download) throws Exception {
        if (checking) return;
        checking = true;
        try {
            doCheck(plugin, repo, download);
        } finally {
            checking = false;
        }
    }

    private static void doCheck(JavaPlugin plugin, String repo, boolean download) throws Exception {
        String current = plugin.getPluginMeta().getVersion();
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "XinZhiExchange/" + current);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        int code = c.getResponseCode();
        if (code != 200) {
            plugin.getLogger().fine("[等价交换] 更新检查: GitHub 返回 " + code + "（仓库没发过 Release 就是 404）");
            return;
        }
        JsonObject root;
        try (InputStream in = c.getInputStream();
             InputStreamReader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(rd).getAsJsonObject();
        }
        String tag = root.has("tag_name") ? root.get("tag_name").getAsString() : "";
        String latest = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
        if (latest.isEmpty()) return;
        if (latest.equalsIgnoreCase(current)) {
            plugin.getLogger().info("[等价交换] 已是最新版本 " + current);
            pendingVersion = null;
            pendingJar = null;
            saveState(plugin, null);
            return;
        }

        String url = null, name = null;
        long assetSize = -1;
        JsonArray assets = root.has("assets") ? root.getAsJsonArray("assets") : null;
        if (assets != null) {
            for (JsonElement el : assets) {
                JsonObject a = el.getAsJsonObject();
                String an = a.has("name") ? a.get("name").getAsString() : "";
                if (an.endsWith(".jar")) {
                    name = an;
                    url = a.has("browser_download_url") ? a.get("browser_download_url").getAsString() : null;
                    if (a.has("size")) {
                        try { assetSize = a.get("size").getAsLong(); } catch (Throwable ignored) { assetSize = -1; }
                    }
                    break;
                }
            }
        }
        plugin.getLogger().info("[等价交换] 发现新版本 " + latest + "（当前 " + current + "）"
                + (url == null ? "" : " · " + url));

        if (!download || url == null || name == null) {
            // 不自动下载：手上没有可校验的文件，只能先通知
            pendingVersion = latest;
            saveState(plugin, latest);
            announce(plugin);
            plugin.getLogger().info("[等价交换] 新版未自动下载（update.download = false）");
            return;
        }

        // 只落到 plugins/update/，绝不碰正在跑的 jar
        File dir = new File(plugin.getDataFolder().getParentFile(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("[等价交换] 建不了 plugins/update 目录，跳过下载");
            return;
        }
        File out = new File(dir, name);
        if (out.isFile() && validJar(out) && latest.equals(versionOfAssetName(out.getName()))) {
            pendingJar = out;
            pendingVersion = latest;
            saveState(plugin, latest);
            announce(plugin);
            plugin.getLogger().info("[等价交换] 新版 " + latest + " 已在 plugins/update/" + name + "，关服后替换");
            return;
        }

        // 腐竹 2026-10-29「等下载完再提示」：先下到 .part，大小与 zip 结构都过了才改名并提示；
        // 半截文件绝不留在这两个位置（他被截断的 jar 卡住过一次起不来服）。
        File part = new File(dir, name + ".part");
        try {
            HttpURLConnection d = (HttpURLConnection) new URL(url).openConnection();
            d.setRequestProperty("User-Agent", "XinZhiExchange/" + current);
            d.setConnectTimeout(10000);
            d.setReadTimeout(30000);
            try (InputStream in = d.getInputStream()) {
                Files.copy(in, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            long len = part.length();
            if (assetSize > 0 && len != assetSize) {
                throw new java.io.IOException("大小不符（下到 " + len + " 字节，发布的是 " + assetSize + " 字节）");
            }
            if (!validJar(part)) throw new java.io.IOException("zip 结构不完整（没有中央目录）");
            try {
                Files.move(part.toPath(), out.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            part.delete();
            plugin.getLogger().warning("[等价交换] 新版 " + latest + " 下载或校验失败，这次不提示也不替换（下次检查会重试）: " + t);
            return;
        }

        pendingJar = out;
        pendingVersion = latest;
        saveState(plugin, latest);
        announce(plugin);   // 文件已就位，这时候才让人重启
        plugin.getLogger().info("[等价交换] 新版 " + latest + " 已下载到 plugins/update/" + name
                + (plugin.getConfig().getBoolean("update.auto-install", true)
                        ? "，关服后自动替换，下次启动生效"
                        : "，需手动替换（update.auto-install = false）"));
    }

    /** jar 是不是完整可读（能被 JarFile 打开 = 有中央目录，才算下完）。 */
    private static boolean validJar(File f) {
        if (f == null || !f.isFile() || f.length() < 1024) return false;
        try (JarFile jf = new JarFile(f)) {
            return jf.size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** xinzhi-exchange-<版本>.jar → 版本 */
    private static String versionOfAssetName(String name) {
        String base = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        return base.startsWith(ASSET_PREFIX) ? base.substring(ASSET_PREFIX.length()) : "";
    }

    /**
     * 正常关服时调用：把本插件 jar 换成 plugins/update/ 里那份新的。
     *
     * <p>正在跑的 jar 被 JVM 锁住，没法直接动，所以派一个脱离的脚本出去：
     * 它先等几秒（等 java 退出、文件解锁），再 copy /Y 覆盖，失败就隔 2 秒重试，
     * 最多约 90 秒；始终失败就留着旧 jar 不动（服还能起）。脚本正文只含 ASCII，
     * 中文路径全走参数传入，避免 cmd 的代码页把 粘液等价交换 吃掉。
     */
    public static void installOnShutdown(JavaPlugin plugin) {
        File next = pendingJar;
        if (next == null || !next.isFile()) {
            recoverPending(plugin);   // 定时器可能刚下完还没认领（或 state 被清过），关服前再找一遍
            next = pendingJar;
        }
        if (next == null || !next.isFile()) return;
        if (!validJar(next)) {
            plugin.getLogger().warning("[等价交换] " + next.getName() + " 不完整，跳过自动安装（旧 jar 保留）");
            return;
        }
        try {
            if (!plugin.getConfig().getBoolean("update.auto-install", true)) return;
            File self = selfJar(plugin);
            if (self == null || !self.isFile()) {
                plugin.getLogger().warning("[等价交换] 找不到插件自己的 jar，跳过自动安装");
                return;
            }
            // Paper 1.20.5+ 会把插件 jar remap 到 plugins/.paper-remapped/ 再从那里加载，
            // 于是「自己 jar」的路径是那份副本。真正要换的是 plugins/<同名>.jar，
            // 副本还得一起删掉 —— 否则下次启动 Paper 照样读旧副本，白换。
            File target = self;
            File staleRemapped = null;
            File selfParent = self.getParentFile();
            if (selfParent != null && ".paper-remapped".equalsIgnoreCase(selfParent.getName())
                    && selfParent.getParentFile() != null
                    && new File(selfParent.getParentFile(), self.getName()).isFile()) {
                staleRemapped = self;
                target = new File(selfParent.getParentFile(), self.getName());
            }
            if (target.getCanonicalFile().equals(next.getCanonicalFile())) return;
            if (target.getPath().contains(" ") || next.getPath().contains(" ")) {
                // cmd /c 的引号规则处理不了「带空格的中文路径」，宁可不动
                plugin.getLogger().warning("[等价交换] 插件路径含空格，跳过自动安装，请手动把 "
                        + next.getName() + " 覆盖到 " + target.getName());
                return;
            }

            File dir = next.getParentFile();
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            File script = new File(dir, win ? "xzex-install.cmd" : "xzex-install.sh");
            Files.write(script.toPath(), (win ? WIN_SCRIPT : UNIX_SCRIPT).getBytes(StandardCharsets.UTF_8));

            File log = new File(dir, "install.log");
            String staleArg = staleRemapped == null ? "" : staleRemapped.getAbsolutePath();
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd.exe", "/c", script.getAbsolutePath(),
                            next.getAbsolutePath(), target.getAbsolutePath(), staleArg)
                    : new ProcessBuilder("/bin/sh", script.getAbsolutePath(),
                            next.getAbsolutePath(), target.getAbsolutePath(), staleArg);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            pb.start();
            plugin.getLogger().info("[等价交换] 已安排关服后替换插件 jar：" + next.getName()
                    + " -> " + target.getName()
                    + (staleRemapped == null ? "" : "（并清掉 Paper 的 remap 副本）")
                    + "（日志 plugins/update/install.log）");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 安排自动安装失败（旧 jar 保留）: " + t);
        }
    }

    private static File selfJar(JavaPlugin plugin) {
        try {
            URL loc = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            return new File(loc.toURI());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 正文纯 ASCII：路径全靠 %1 / %2 传进来。 */
    private static final String WIN_SCRIPT =
            "@echo off\r\n"
            + "set \"NEW=%~1\"\r\n"
            + "set \"OLD=%~2\"\r\n"
            + "echo [xzex-install] waiting for server to stop...\r\n"
            + "ping -n 7 127.0.0.1 >nul\r\n"
            + "for /L %%i in (1,1,45) do (\r\n"
            + "  if not exist \"%NEW%\" goto end\r\n"
            + "  copy /Y \"%NEW%\" \"%OLD%.tmp\" >nul 2>&1\r\n"
            + "  move /Y \"%OLD%.tmp\" \"%OLD%\" >nul 2>&1\r\n"
            + "  if not errorlevel 1 (\r\n"
            + "    del \"%NEW%\" >nul 2>&1\r\n"
            + "    if not \"%~3\"==\"\" del \"%~3\" >nul 2>&1\r\n"
            + "    echo [xzex-install] done, next start uses new jar\r\n"
            + "    goto end\r\n"
            + "  )\r\n"
            + "  ping -n 3 127.0.0.1 >nul\r\n"
            + ")\r\n"
            + "echo [xzex-install] failed, old jar kept\r\n"
            + ":end\r\n";

    private static final String UNIX_SCRIPT =
            "#!/bin/sh\n"
            + "NEW=\"$1\"\n"
            + "OLD=\"$2\"\n"
            + "sleep 6\n"
            + "i=0\n"
            + "while [ $i -lt 45 ]; do\n"
            + "  if [ ! -f \"$NEW\" ]; then exit 0; fi\n"
            + "  if cp -f \"$NEW\" \"$OLD.tmp\" 2>/dev/null && mv -f \"$OLD.tmp\" \"$OLD\" 2>/dev/null; then rm -f \"$NEW\"; [ -n \"$3\" ] && rm -f \"$3\"; exit 0; fi\n"
            + "  i=$((i+1)); sleep 2\n"
            + "done\n"
            + "exit 1\n";
}
