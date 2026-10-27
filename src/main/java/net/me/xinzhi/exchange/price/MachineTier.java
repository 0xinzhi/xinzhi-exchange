package net.me.xinzhi.exchange.price;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 「这条配方属于粘液本体还是附属」的判定器（腐竹 2026-10-18 的两阶段定价要求）。
 *
 * <p>判据是<b>配方的机器是谁注册的</b>：
 * <ol>
 *   <li><b>真实机器</b>：{@code SlimefunItem.getById(id).getAddon()} 是注册它的插件，
 *       等于 Slimefun 本体 ⇒ 阶段 1，否则阶段 2；</li>
 *   <li><b>虚拟机器 {@code SF:RT_*}（没有同名真机器时才保留）</b>：
 *       拿 {@code RecipeType} 的命名空间判 —— 本体/原版的命名空间是
 *       {@code slimefun}/{@code minecraft}。</li>
 * </ol>
 * 判定不出来的一律算<b>附属</b>（宁可晚一阶段定价，也不让来历不明的配方改本体价）。
 */
public final class MachineTier {

    private final JavaPlugin plugin;
    private final String coreNamespace;
    private volatile Set<String> coreTypeKeys;

    public MachineTier(JavaPlugin plugin) {
        this.plugin = plugin;
        String ns = "slimefun";
        try {
            ns = plugin.getServer().getPluginManager().getPlugin("Slimefun").getName();
        } catch (Throwable ignored) {
            try { ns = Slimefun.instance().getName(); } catch (Throwable ignored2) {}
        }
        this.coreNamespace = ns == null ? "slimefun" : ns.toLowerCase(Locale.ROOT);
    }

    public String coreNamespace() { return coreNamespace; }

    /** 1 = 粘液本体 / 原版，2 = 附属 */
    public int tierOf(ItemKey machine) {
        if (machine == null || !machine.isSlimefun()) return 2;
        String id = machine.getSfId();
        if (id == null || id.isEmpty()) return 2;

        if (id.startsWith("RT_")) {
            return coreTypeKeys().contains(id.substring(3).toUpperCase(Locale.ROOT)) ? 1 : 2;
        }

        SlimefunItem item;
        try {
            item = SlimefunItem.getById(id);
        } catch (Throwable t) {
            return 2;
        }
        if (item == null) return 2;
        try {
            SlimefunAddon addon = item.getAddon();
            if (addon == null) return 2;
            String name = addon.getName();
            return name != null && name.toLowerCase(Locale.ROOT).equals(coreNamespace) ? 1 : 2;
        } catch (Throwable t) {
            return 2;
        }
    }

    public boolean isCore(ItemKey machine) { return tierOf(machine) == 1; }

    /** 扫一遍已注册物品，收集本体 / 原版注册的 RecipeType key（只扫一次） */
    private Set<String> coreTypeKeys() {
        Set<String> cached = coreTypeKeys;
        if (cached != null) return cached;

        Set<String> set = new HashSet<>(64);
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (item == null) continue;
                RecipeType type;
                try {
                    type = item.getRecipeType();
                } catch (Throwable ignored) {
                    continue;
                }
                if (type == null || type.getKey() == null) continue;
                String ns, key;
                try {
                    ns = type.getKey().getNamespace();
                    key = type.getKey().getKey();
                } catch (Throwable ignored) {
                    continue;
                }
                if (ns == null || key == null || key.isEmpty()) continue;
                String lower = ns.toLowerCase(Locale.ROOT);
                if (lower.equals(coreNamespace) || lower.equals("minecraft")) {
                    set.add(key.toUpperCase(Locale.ROOT));
                }
            }
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().warning("[等价交换] 收集本体 RecipeType 失败，RT_ 机器一律按附属算: " + t);
            }
        }
        cached = Collections.unmodifiableSet(set);
        coreTypeKeys = cached;
        if (plugin != null) {
            plugin.getLogger().info("[等价交换] 本体/原版 RecipeType 识别到 " + cached.size()
                    + " 个（命名空间 " + coreNamespace + " / minecraft）");
        }
        return cached;
    }
}
