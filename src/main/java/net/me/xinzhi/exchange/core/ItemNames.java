package net.me.xinzhi.exchange.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 原版物品中文名。
 *
 * <p>从 jar 内 {@code lang/zh_cn.json}（Minecraft 的 zh_cn 语言表）加载，
 * 只收 {@code block.minecraft.*} / {@code item.minecraft.*} 两类键。
 * 不读服务器外部文件 —— 附属自带一份，装上就有名字。
 */
public final class ItemNames {

    private static final Map<String, String> MAP = new HashMap<>();
    private static boolean loaded = false;

    private ItemNames() {}

    public static void load(JavaPlugin plugin) {
        if (loaded) return;
        try (InputStream is = plugin.getResource("lang/zh_cn.json")) {
            if (is == null) {
                plugin.getLogger().warning("[等价交换] 缺少 lang/zh_cn.json，原版物品名会用英文");
                return;
            }
            try (InputStreamReader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                for (var e : root.entrySet()) {
                    String k = e.getKey();
                    if (k.startsWith("block.minecraft.") || k.startsWith("item.minecraft.")) {
                        try { MAP.put(k, e.getValue().getAsString()); } catch (Throwable ignored) {}
                    }
                }
            }
            loaded = true;
            plugin.getLogger().info("[等价交换] 原版物品中文名表加载完成: " + MAP.size() + " 条");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 加载 lang/zh_cn.json 失败: " + t);
        }
    }

    public static String of(Material m) {
        if (m == null) return "空气";
        String key = (m.isBlock() ? "block.minecraft." : "item.minecraft.") + m.getKey().getKey();
        String name = MAP.get(key);
        if (name != null) return name;
        String lower = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
