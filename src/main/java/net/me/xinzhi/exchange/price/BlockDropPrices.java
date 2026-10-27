package net.me.xinzhi.exchange.price;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 破坏方块概率掉落物的兜底价（腐竹 2026-10-28）。
 *
 * <p>为什么需要：RSC（RykenSlimefunCustomizer）子附属的物品常常没有合成配方，
 * 只在 {@code items.yml} 里声明「挖掉某个方块时有概率掉它」——
 * 按配方链永远算不出价，等价界面里根本不会出现。这些字段是<b>明文</b>，可以直接读：
 * <pre>
 * drop_from: blue_ice      # 原版方块（大小写随意）
 * drop_chance: 5           # 百分比整数
 * drop_amount: 1           # 数量，可为 "1-3" 区间
 * </pre>
 *
 * <h3>口径（腐竹 m00970 原话）</h3>
 * 「可重复的按概率划分，30%以上5emc，30以下15emc，5以下30emc。无法再生的资源按100emc。
 *   还有就是那个下界传送门算是可再生。」
 *
 * <p>为什么不能拿「方块价 ÷ 概率」去算（腐竹 m00966 原话：「其实，信标可以反复放置刷取这个玉的，
 * 唯一值得关注的是无法采集的方块。就比如那个紫水晶母岩。」）：
 * 方块挖掉会掉回自身，玩家可以摆了再挖、无限刷 —— 成本≈0，方块价（信标 1360）是<b>合成成本</b>，
 * 不是采集成本。真正稀缺的只有「挖掉不会掉回自身、玩家也造不出来」的方块（紫水晶母岩）。
 *
 * <p>所以分两类：
 * <ul>
 *   <li><b>可再生</b>（{@code Material.isItem()}，即能拿在手上再摆回去；外加
 *       {@code price.block-drop.renewable-blocks} 里手写的，例如下界传送门）→ 按概率分档；</li>
 *   <li><b>不可再生</b>（没有物品形态、也造不出来）→ {@code non-renewable-emc}。</li>
 * </ul>
 *
 * <p>和 {@link MobDropPrices} 一样<b>只补空缺</b>：已经由定价表 / 配方推导算出价的物品不受影响。
 */
public final class BlockDropPrices {

    private BlockDropPrices() {}

    /** 一条掉落记录：某方块掉某物品，概率 / 数量与原版方块是否可以反复刷取。 */
    public record Entry(String addon, String itemId, ItemKey item, String displayName,
                        String blockName, int chance, String amount, boolean renewable, double price) {}

    /** 定价规则（全部来自 {@code price.block-drop}，代码里的这份是兜底默认值）。 */
    public record Rules(int highChance, double highEmc, int midChance, double midEmc,
                        double lowEmc, double nonRenewableEmc, Set<String> renewableBlocks) {
        public static Rules defaults() {
            return new Rules(30, 5.0, 5, 15.0, 30.0, 100.0, Set.of("NETHER_PORTAL"));
        }
    }

    /**
     * 按「能不能反复刷 + 概率」算价。
     *
     * @param chance    0~100 的整数百分比
     * @param renewable 方块能不能被玩家摆回去（{@code Material.isItem()} 或配置里点名）
     */
    public static double priceFor(int chance, boolean renewable, Rules r) {
        if (!renewable) return r.nonRenewableEmc();
        if (chance >= r.highChance()) return r.highEmc();
        if (chance >= r.midChance()) return r.midEmc();
        return r.lowEmc();
    }

    /** 扫一遍 RSC 的 addons 目录；失败只返回已读到的部分。 */
    public static List<Entry> collect(File pluginsDir, Rules rules) {
        List<Entry> out = new ArrayList<>();
        if (pluginsDir == null || rules == null) return out;
        File addons = new File(pluginsDir, "RykenSlimefunCustomizer" + File.separator + "addons");
        if (!addons.isDirectory()) return out;
        List<File> files = new ArrayList<>();
        collectYml(addons, files, 0);
        Map<String, SlimefunItem> byLowerId = itemIndex();
        for (File f : files) {
            try {
                parse(f, byLowerId, rules, out);
            } catch (Throwable ignored) {
                // 单个附属的 items.yml 坏了不影响其它附属
            }
        }
        return out;
    }

    private static void collectYml(File dir, List<File> out, int depth) {
        File[] es;
        try {
            es = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (es == null) return;
        for (File f : es) {
            if (f == null) continue;
            String n = f.getName();
            if (f.isDirectory()) {
                if (depth < 3) collectYml(f, out, depth + 1);
            } else if (n.equalsIgnoreCase("items.yml")) {
                out.add(f);
            }
        }
    }

    private static void parse(File file, Map<String, SlimefunItem> byLowerId,
                              Rules rules, List<Entry> out) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        File parent = file.getParentFile();
        String addon = parent == null ? "?" : parent.getName();
        for (String key : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(key);
            if (sec == null) continue;
            ConfigurationSection src = hasDrop(sec) ? sec : sec.getConfigurationSection("item");
            if (src == null || !hasDrop(src)) continue;
            String from = src.getString("drop_from");
            if (from == null || from.isBlank()) continue;
            Material mat = Material.matchMaterial(from.trim().toUpperCase(Locale.ROOT));
            String blockName = mat == null ? from.trim() : mat.name();
            boolean renewable = mat != null && (mat.isItem()
                    || rules.renewableBlocks().contains(mat.name().toUpperCase(Locale.ROOT)));
            int chance = Math.max(0, Math.min(100, src.getInt("drop_chance", 100)));
            Object amt = src.get("drop_amount");
            String amount = amt == null ? "1" : String.valueOf(amt);
            SlimefunItem sf = byLowerId.get(key.toLowerCase(Locale.ROOT));
            if (sf == null) continue;   // 物品没注册（附属没装 / 已删）→ 不给价
            ItemKey item = ItemKey.of(sf);
            if (item == null) continue;
            double price = priceFor(chance, renewable, rules);
            if (!(price > 0)) continue;
            String display;
            try {
                display = sf.getItemName();
            } catch (Throwable t) {
                display = key;
            }
            out.add(new Entry(addon, key, item, display, blockName, chance, amount, renewable, price));
        }
    }

    private static boolean hasDrop(ConfigurationSection sec) {
        return sec != null && sec.isSet("drop_from");
    }

    /** 物品 id（小写）→ 已注册的粘液物品，用来把 yml 的顶层键换成真正的 {@link ItemKey}。 */
    private static Map<String, SlimefunItem> itemIndex() {
        Map<String, SlimefunItem> map = new HashMap<>(1 << 14);
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (item == null || item.getId() == null) continue;
                map.putIfAbsent(item.getId().toLowerCase(Locale.ROOT), item);
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    /**
     * 归并成「物品 → 兜底价」。
     *
     * <p>同一个物品可能被好几个方块掉落（尤其是装了两份同一个附属的情况），
     * 取<b>最便宜</b>的那条 —— 最容易拿到的方式决定价。
     */
    public static Map<ItemKey, Double> defaults(List<Entry> entries) {
        Map<ItemKey, Double> out = new LinkedHashMap<>();
        if (entries == null) return out;
        for (Entry e : entries) {
            if (e == null || e.item() == null || !(e.price() > 0)) continue;
            out.merge(e.item(), e.price(), Math::min);
        }
        return out;
    }
}
