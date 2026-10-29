package net.me.xinzhi.exchange.price;

import net.me.xinzhi.exchange.api.EmcRegistry;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础定价表（EMC 基准价）。
 *
 * <h2>三层来源（优先级从低到高）</h2>
 * <ol>
 *   <li><b>内置兜底</b>：所有原版物品 = 1.0（技术方块除外）；</li>
 *   <li><b>jar 内种子数据</b> {@code prices-builtin.tsv}
 *       （= 主附属那张内置基础定价表，圆石 = 1 为价值单位）；</li>
 *   <li><b>服务器上的 {@code prices.yml}</b> —— 首次启动自动导出全表，
 *       之后<b>以它为准</b>。其它附属作者把自家物品写进 {@code slimefun:} 段即可定价。</li>
 * </ol>
 *
 * <h2>prices.yml 格式</h2>
 * <pre>
 * vanilla:            # 原版：材质名（大写）→ 价格
 *   OAK_LOG: 1
 * slimefun:           # 粘液物品：id（不带 SF: 前缀）→ 价格
 *   IRON_INGOT: 24
 *   MY_ADDON_ITEM: 100
 * manual:             # /xzex set 写入的手工价，优先级最高
 *   slimefun:
 *     MY_ADDON_ITEM: 120
 * </pre>
 *
 * <p>没写的键回落到上两层；因此删掉一行不会"没有价"，只是回到内置值。
 */
public final class PriceBook {

    private static final String FILE_NAME = "prices.yml";
    private static final String BUILTIN = "prices-builtin.tsv";
    /** 服主手工价的内置默认值（新装服务器开箱即用；与 manual 同层，prices.yml 可覆盖） */
    private static final String BUILTIN_MANUAL = "prices-builtin-manual.tsv";
    /** 只压卖价的定价表（买价不受影响）：铁轨、可刷钱的东西都放这里 */
    private static final String BUILTIN_SELL = "prices-builtin-sell.tsv";

    private final JavaPlugin plugin;
    private final File file;

    private final Map<ItemKey, Double> builtin = new LinkedHashMap<>(2048);
    private final Map<ItemKey, Double> anchors = new LinkedHashMap<>(2048);
    private final Map<ItemKey, String> origin = new LinkedHashMap<>(2048);
    /** manual: 段里的条目（定制机 / 批量定价面板直接操作这一层） */
    private final Map<ItemKey, Double> manual = new LinkedHashMap<>(256);
    /** 内置手工价默认值（prices-builtin-manual.tsv），优先级高于 prices.yml 的 vanilla/slimefun 段、低于 yml 的 manual 段 */
    private final Map<ItemKey, Double> builtinManual = new LinkedHashMap<>(256);
    /** 只作用于卖价的定价（prices.yml 的 sell: 段 + 内置 prices-builtin-sell.tsv），买价不读它 */
    private final Map<ItemKey, Double> sell = new LinkedHashMap<>(64);

    public PriceBook(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    public File file() { return file; }

    /** 全部锚点（builtin ∪ yml ∪ manual），只读 */
    public Map<ItemKey, Double> anchors() { return anchors; }

    /** 价格来源标记：builtin / prices.yml / manual */
    public String originOf(ItemKey key) { return origin.getOrDefault(key, "builtin"); }

    public int count() { return anchors.size(); }

    /** 卖价专用定价（只读）：key → 卖价 */
    public Map<ItemKey, Double> sellEntries() { return java.util.Collections.unmodifiableMap(sell); }

    /** manual: 段的全部条目（只读） */
    public Map<ItemKey, Double> manualEntries() { return java.util.Collections.unmodifiableMap(manual); }

    // ============================================================
    // 读取
    // ============================================================

    public synchronized void reload() {
        anchors.clear();
        origin.clear();
        manual.clear();
        builtinManual.clear();
        loadBuiltin();
        anchors.putAll(builtin);
        for (ItemKey k : builtin.keySet()) origin.put(k, "builtin");

        if (!file.isFile()) {
            writeSeed();
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        int n = 0;
        n += mergeSection(yml.getConfigurationSection("vanilla"), true, "prices.yml");
        n += mergeSection(yml.getConfigurationSection("slimefun"), false, "prices.yml");
        // 内置手工价：盖过 prices.yml 的 vanilla/slimefun 段，但下面的 manual 段仍能覆盖它
        for (Map.Entry<ItemKey, Double> e : builtinManual.entrySet()) {
            anchors.put(e.getKey(), e.getValue());
            origin.put(e.getKey(), "manual");
            manual.put(e.getKey(), e.getValue());
        }
        ConfigurationSection m = yml.getConfigurationSection("manual");
        int manualCount = 0;
        if (m != null) {
            manualCount += mergeSection(m.getConfigurationSection("vanilla"), true, "manual", manual);
            manualCount += mergeSection(m.getConfigurationSection("slimefun"), false, "manual", manual);
        }
        sell.clear();
        loadSell(yml);
        applyEmbedded();
        plugin.getLogger().info("[等价交换] 定价表: 内置 " + builtin.size()
                + " 条，prices.yml 覆盖 " + n + " 条，手工 " + manualCount + " 条，合计 " + anchors.size() + " 条"
                + "，附属内嵌 " + EmcRegistry.size() + " 条，仅卖价 " + sell.size() + " 条");
    }

    /**
     * 把第三方附属通过 {@link EmcRegistry} 注册的值并进锚点。
     *
     * <p>优先级：服主手工价（manual）与 prices.yml 的 vanilla/slimefun 段<b>更高</b>——
     * 已经由它们定义的 key 不覆盖；只有内置表 / 之前的内嵌值会被附属值顶掉。
     * 每次 reload 和每次重建价表都会调一次，所以运行期注册也能生效。
     */
    public synchronized void applyEmbedded() {
        Map<ItemKey, Double> embedded = EmcRegistry.all();
        if (embedded.isEmpty()) return;
        for (Map.Entry<ItemKey, Double> e : embedded.entrySet()) {
            ItemKey k = e.getKey();
            String org = origin.getOrDefault(k, "builtin");
            if ("prices.yml".equals(org) || "manual".equals(org)) continue;
            anchors.put(k, e.getValue());
            origin.put(k, "addon");
        }
    }

    /** 卖价专用定价：内置表打底，prices.yml 的 sell 段覆盖 */
    private void loadSell(YamlConfiguration yml) {
        try (InputStream is = plugin.getResource(BUILTIN_SELL)) {
            if (is != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        ItemKey ik = ItemKey.fromStored(line.substring(0, tab).trim());
                        if (ik == null) continue;
                        try { sell.put(ik, Double.parseDouble(line.substring(tab + 1).trim())); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读内置卖价表失败: " + t);
        }
        ConfigurationSection sec = yml.getConfigurationSection("sell");
        if (sec == null) return;
        for (String group : sec.getKeys(false)) {
            ConfigurationSection sub = sec.getConfigurationSection(group);
            if (sub == null) continue;
            boolean vanilla = "vanilla".equalsIgnoreCase(group);
            for (String k : sub.getKeys(false)) {
                double v = sub.getDouble(k, Double.NaN);
                if (Double.isNaN(v) || v < 0) continue;
                ItemKey key = vanilla ? vanillaKey(k) : ItemKey.slimefun(k);
                if (key == null) continue;
                sell.put(key, v);
            }
        }
    }

    /** 写一条卖价专用定价（/xzex setsell），落盘到 prices.yml 的 sell.vanilla / sell.slimefun */
    public synchronized boolean setSell(ItemKey key, double value) {
        if (key == null) return false;
        String name = key.isVanilla()
                ? (key.getMaterial() == null ? null : key.getMaterial().name())
                : key.getSfId();
        if (name == null) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set((key.isVanilla() ? "sell.vanilla." : "sell.slimefun.") + name, Text.round2(value));
        try { yml.save(file); } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 写卖价失败: " + t);
            return false;
        }
        sell.put(key, value);
        return true;
    }

    private int mergeSection(ConfigurationSection sec, boolean vanilla, String from) {
        return mergeSection(sec, vanilla, from, null);
    }

    /** @param record 非空时同时记进这张表（manual 段用） */
    private int mergeSection(ConfigurationSection sec, boolean vanilla, String from,
                             Map<ItemKey, Double> record) {
        if (sec == null) return 0;
        int n = 0;
        for (String k : sec.getKeys(false)) {
            double v = sec.getDouble(k, Double.NaN);
            if (Double.isNaN(v) || v < 0) continue;
            ItemKey key = vanilla ? vanillaKey(k) : ItemKey.slimefun(k);
            if (key == null) continue;
            anchors.put(key, v);
            origin.put(key, from);
            if (record != null) record.put(key, v);
            n++;
        }
        return n;
    }

    private static ItemKey vanillaKey(String materialName) {
        Material mat = Material.matchMaterial(materialName);
        return mat == null ? null : ItemKey.vanilla(mat);
    }

    private void loadBuiltin() {
        builtin.clear();
        // 1) 原版兜底：全部物品 = 1.0（技术方块不算）
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir()) continue;
            if (isTechnical(m)) continue;
            if (!hasItemForm(m)) continue;          // 见 ItemKey.templateRef 的同类坑
            builtin.put(ItemKey.vanilla(m), 1.0);
        }
        // 2) jar 内种子数据
        try (InputStream is = plugin.getResource(BUILTIN)) {
            if (is == null) {
                plugin.getLogger().warning("[等价交换] 缺少内置定价表资源 " + BUILTIN);
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String key = line.substring(0, tab).trim();
                    double v;
                    try { v = Double.parseDouble(line.substring(tab + 1).trim()); }
                    catch (NumberFormatException e) { continue; }
                    ItemKey ik = ItemKey.fromStored(key);
                    if (ik == null) continue;
                    builtin.put(ik, v);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读内置定价表失败: " + t);
        }
        // 3) 服主手工价的内置默认值（与 manual 同层）
        try (InputStream is = plugin.getResource(BUILTIN_MANUAL)) {
            if (is == null) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    ItemKey ik = ItemKey.fromStored(line.substring(0, tab).trim());
                    if (ik == null) continue;
                    double v;
                    try { v = Double.parseDouble(line.substring(tab + 1).trim()); }
                    catch (NumberFormatException e) { continue; }
                    builtinManual.put(ik, v);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读内置手工价失败: " + t);
        }
    }

    /** 有些材质 isItem() 为真但 new ItemStack(m) 会抛（1.21.8 的墙上悬挂告示牌） */
    private static boolean hasItemForm(Material m) {
        try {
            new org.bukkit.inventory.ItemStack(m);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 技术方块（没有「物品」意义的原版方块）。VanillaRecipes 也按这个口径过滤，所以是 public。 */
    public static boolean isTechnical(Material m) {
        return switch (m) {
            case AIR, CAVE_AIR, VOID_AIR, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK,
                 REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, STRUCTURE_VOID, BARRIER,
                 LIGHT, DEBUG_STICK, KNOWLEDGE_BOOK, JIGSAW, PETRIFIED_OAK_SLAB -> true;
            default -> false;
        };
    }

    // ============================================================
    // 首次导出
    // ============================================================

    private void writeSeed() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(java.util.List.of(
                "等价交换（独立版）基础定价表",
                "",
                "价值单位：圆石 = 1.0。",
                "第一次启动时由 jar 内置表导出；之后以本文件为准。",
                "没写的物品会回落到内置值（原版 = 1），所以删行不会让物品失去价格。",
                "",
                "其它附属的作者：把自家物品的 id 写进 slimefun: 段即可（不带 SF: 前缀）。",
                "例：  slimefun:",
                "        MY_ADDON_INGOT: 120",
                "",
                "改完执行 /xzex reload 生效，不需要重启。",
                "manual: 段由 /xzex set 自动维护，优先级最高。"));
        for (Map.Entry<ItemKey, Double> e : builtin.entrySet()) {
            ItemKey k = e.getKey();
            if (k.isVanilla()) {
                Material m = k.getMaterial();
                if (m != null) yml.set("vanilla." + m.name(), e.getValue());
            } else {
                yml.set("slimefun." + k.getSfId(), e.getValue());
            }
        }
        yml.set("manual.slimefun", new LinkedHashMap<String, Double>());
        yml.set("manual.vanilla", new LinkedHashMap<String, Double>());
        try {
            yml.save(file);
            plugin.getLogger().info("[等价交换] 已导出基础定价表: " + file.getName()
                    + "（" + builtin.size() + " 条）");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 导出 " + FILE_NAME + " 失败: " + t);
        }
    }

    // ============================================================
    // 手工价
    // ============================================================

    public synchronized boolean setManual(ItemKey key, double value) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String section = key.isVanilla() ? "manual.vanilla" : "manual.slimefun";
        String name = key.isVanilla() ? (key.getMaterial() == null ? null : key.getMaterial().name())
                : key.getSfId();
        if (name == null) return false;
        yml.set(section + "." + name, Text.round2(value));
        try { yml.save(file); } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 写手工价失败: " + t);
            return false;
        }
        return true;
    }

    /**
     * 批量写手工价（定制机 / 批量定价面板用）：一次解析 yml、一次落盘。
     *
     * @return 写进去的条数
     */
    public synchronized int setManualBatch(Map<ItemKey, Double> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        int n = 0;
        for (Map.Entry<ItemKey, Double> e : batch.entrySet()) {
            ItemKey key = e.getKey();
            if (key == null) continue;
            double v = e.getValue() == null ? Double.NaN : e.getValue();
            if (Double.isNaN(v) || v < 0) continue;
            String section = key.isVanilla() ? "manual.vanilla" : "manual.slimefun";
            String name = key.isVanilla()
                    ? (key.getMaterial() == null ? null : key.getMaterial().name())
                    : key.getSfId();
            if (name == null) continue;
            yml.set(section + "." + name, Text.round2(v));
            n++;
        }
        if (n == 0) return 0;
        try {
            yml.save(file);
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 批量写手工价失败: " + t);
            return 0;
        }
        return n;
    }

    public synchronized boolean unsetManual(ItemKey key) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String section = key.isVanilla() ? "manual.vanilla" : "manual.slimefun";
        String name = key.isVanilla() ? (key.getMaterial() == null ? null : key.getMaterial().name())
                : key.getSfId();
        if (name == null) return false;
        yml.set(section + "." + name, null);
        try { yml.save(file); } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 删手工价失败: " + t);
            return false;
        }
        return true;
    }
}
