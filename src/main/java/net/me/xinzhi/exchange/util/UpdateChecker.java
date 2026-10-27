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
 * <p>两条线分开看：<b>出包端</b>是 {@code .github/workflows/release.yml}
 * （推 {@code v*} tag 由 Actions 编译并发 Release）；<b>检查端</b>就是这里。
 *
 * <p>刻意<b>不</b>热替换正在运行的 jar：新 jar 只下载到 {@code plugins/update/}，
 * 下次重启才装进服。版本和 Slimefun 核心不配套时，热替换会让服直接起不来。
 *
 * <p>配置（config.yml）：{@code update.check}（默认开）、{@code update.repo}、
 * {@code update.download}（默认关 = 只提示不下载）。
 */
public final class UpdateChecker {

    private UpdateChecker() {}

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
        // 只落到 plugins/update/，重启时服务端/插件再装它；绝不碰正在跑的 jar
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
        plugin.getLogger().info("[等价交换] 新版已下载到 plugins/update/" + name + "，重启服务器后生效");
    }
}
