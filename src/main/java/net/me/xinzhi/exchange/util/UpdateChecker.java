package net.me.xinzhi.exchange.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
 * <p>配置（config.yml）：update.check（默认开）、update.repo、update.download（默认关 = 只提示不下载）、
 * update.auto-install（默认关 = 只下到 plugins/update 不自动换）。
 */
public final class UpdateChecker {

    private UpdateChecker() {}

    /** 已下载待安装的新 jar；为 null 表示这轮没有更新。 */
    private static volatile File pendingJar;

    public static void start(JavaPlugin plugin) {
        try {
            if (!plugin.getConfig().getBoolean("update.check", true)) return;
            String repo = plugin.getConfig().getString("update.repo", "0xinzhi/xinzhi-exchange");
            boolean download = plugin.getConfig().getBoolean("update.download", false);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    check(plugin, repo, download);
                } catch (Throwable t) {
                    plugin.getLogger().fine("[等价交换] 更新检查失败（不影响使用）: " + t);
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().fine("[等价交换] 更新检查未启动: " + t);
        }
    }

    private static void check(JavaPlugin plugin, String repo, boolean download) throws Exception {
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
            return;
        }

        String url = null, name = null;
        JsonArray assets = root.has("assets") ? root.getAsJsonArray("assets") : null;
        if (assets != null) {
            for (JsonElement el : assets) {
                JsonObject a = el.getAsJsonObject();
                String an = a.has("name") ? a.get("name").getAsString() : "";
                if (an.endsWith(".jar")) {
                    name = an;
                    url = a.has("browser_download_url") ? a.get("browser_download_url").getAsString() : null;
                    break;
                }
            }
        }
        plugin.getLogger().info("[等价交换] 发现新版本 " + latest + "（当前 " + current + "）"
                + (url == null ? "" : " · " + url));

        if (!download || url == null || name == null) {
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
        HttpURLConnection d = (HttpURLConnection) new URL(url).openConnection();
        d.setRequestProperty("User-Agent", "XinZhiExchange/" + current);
        d.setConnectTimeout(10000);
        d.setReadTimeout(30000);
        try (InputStream in = d.getInputStream()) {
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        pendingJar = out;
        plugin.getLogger().info("[等价交换] 新版 " + latest + " 已下载到 plugins/update/" + name
                + (plugin.getConfig().getBoolean("update.auto-install", false)
                        ? "，关服后自动替换，下次启动生效"
                        : "，需手动替换（update.auto-install = false）"));
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
        if (next == null || !next.isFile()) return;
        try {
            if (!plugin.getConfig().getBoolean("update.auto-install", false)) return;
            File self = selfJar(plugin);
            if (self == null || !self.isFile()) {
                plugin.getLogger().warning("[等价交换] 找不到插件自己的 jar，跳过自动安装");
                return;
            }
            if (self.getCanonicalFile().equals(next.getCanonicalFile())) return;

            File dir = next.getParentFile();
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            File script = new File(dir, win ? "xzex-install.cmd" : "xzex-install.sh");
            Files.write(script.toPath(), (win ? WIN_SCRIPT : UNIX_SCRIPT).getBytes(StandardCharsets.UTF_8));

            File log = new File(dir, "install.log");
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd.exe", "/c", script.getAbsolutePath(),
                            next.getAbsolutePath(), self.getAbsolutePath())
                    : new ProcessBuilder("/bin/sh", script.getAbsolutePath(),
                            next.getAbsolutePath(), self.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            pb.start();
            plugin.getLogger().info("[等价交换] 已安排关服后替换插件 jar：" + next.getName()
                    + " -> " + self.getName() + "（日志 plugins/update/install.log）");
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
            + "timeout /t 6 /nobreak >nul\r\n"
            + "for /L %%i in (1,1,45) do (\r\n"
            + "  if not exist \"%NEW%\" goto end\r\n"
            + "  copy /Y \"%NEW%\" \"%OLD%\" >nul 2>&1\r\n"
            + "  if not errorlevel 1 (\r\n"
            + "    del \"%NEW%\" >nul 2>&1\r\n"
            + "    echo [xzex-install] done, next start uses new jar\r\n"
            + "    goto end\r\n"
            + "  )\r\n"
            + "  timeout /t 2 /nobreak >nul\r\n"
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
            + "  if cp -f \"$NEW\" \"$OLD\" 2>/dev/null; then rm -f \"$NEW\"; exit 0; fi\n"
            + "  i=$((i+1)); sleep 2\n"
            + "done\n"
            + "exit 1\n";
}
