package net.me.xinzhi.exchange.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import net.me.xinzhi.exchange.config.Config;
import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 配方扫描 / 落盘 / 读回。
 *
 * <h2>扫描顺序（两遍）</h2>
 * <ol>
 *   <li><b>第一遍</b>：遍历全部 Slimefun 物品，抓真实机器配方（API / 多方块 / 反射）；</li>
 *   <li><b>第二遍</b>：只对<b>没有任何真实配方产出它</b>的物品，补一条「物品自身配方」兜底。</li>
 * </ol>
 * 第二遍必须放在第一遍之后 —— 这就是修掉「RT_ 产出数写死 1」那个坑的关键：
 * 有真实配方（产出 N 个）的物品，绝不能再被一条写死 1 个的兜底配方顶掉。
 */
public final class RecipeService {

    private final JavaPlugin plugin;
    private final Config config;
    private final RecipeRegistry registry = new RecipeRegistry();
    private final RecipeStorage storage;

    private volatile boolean scanning = false;
    private volatile long lastScanMillis = 0;
    private volatile int lastScanCount = 0;
    /** 本次扫描的附属读取顺序摘要（写进日志 / 扫描报告，方便核对顺序表是否生效） */
    private volatile String lastOrderSummary = "";
    /** 物品 → 读取优先级（0 = 最先读）；由 {@link #ordered(List)} 在主线程算好，doScan 直接查 */
    private volatile java.util.IdentityHashMap<SlimefunItem, Integer> lastPriority = new java.util.IdentityHashMap<>();
    /**
     * 粘液本体（Slimefun 核心）注册的机器 id 集合 —— 增强工作台 / 远古祭坛 / 冶炼炉这些。
     * 腐竹 2026-10-27：「优先本体就能读到的配方……优先级要比附属自定义机器高」：
     * 同一个附属内，本体机器的配方排在该附属自定义机器的配方之前（即使后者更便宜）。
     */
    private volatile java.util.Set<ItemKey> lastCoreMachines = java.util.Set.of();
    /** 物品 id → 该物品所属附属的读取档位（0 = 最先读）；本体机器上的配方按「产出物品的附属」计档 */
    private volatile java.util.Map<ItemKey, Integer> lastItemRank = java.util.Map.of();

    public RecipeService(JavaPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.storage = new RecipeStorage(plugin);
    }

    public RecipeRegistry registry() { return registry; }
    public RecipeStorage storage() { return storage; }
    public boolean isScanning() { return scanning; }
    public long lastScanMillis() { return lastScanMillis; }
    public int lastScanCount() { return lastScanCount; }

    /** 从磁盘读回（读回后按黑名单裁一遍，名单改了不用等重扫） */
    public int load() {
        int n = storage.loadAll(registry);
        int dropped = registry.removeWhere(this::blacklisted);
        if (dropped > 0) {
            plugin.getLogger().info("[等价交换] 按黑名单（机器/材料）裁掉磁盘配方 " + dropped + " 条");
        }
        plugin.getLogger().info("[等价交换] 配方库已加载: " + registry.size() + " 条 / "
                + registry.machines().size() + " 台机器");
        return registry.size();
    }

    /**
     * 这条配方该不该拉黑：机器黑名单（腐竹 2026-10-18 点名 LOGITECH_CARD_MAKER）
     * 或材料黑名单（腐竹 2026-10-28：替代卡这类「身份靠 nbt 决定」的物品不能被当成普通材料）。
     */
    private boolean blacklisted(Recipe r) {
        if (r == null) return false;
        net.me.xinzhi.exchange.core.ItemKey machine = r.getMachine();
        if (machine != null && machine.isSlimefun()) {
            String id = machine.getSfId();
            java.util.Set<String> black = config.machineBlacklist;
            if (id != null && !black.isEmpty() && black.contains(id.toUpperCase(Locale.ROOT))) return true;
        }
        java.util.Set<String> matBlack = config.materialBlacklist;
        if (matBlack.isEmpty()) return false;
        java.util.Map<net.me.xinzhi.exchange.core.ItemKey, Integer> inputs = r.getInputs();
        if (inputs == null || inputs.isEmpty()) return false;
        for (net.me.xinzhi.exchange.core.ItemKey k : inputs.keySet()) {
            if (k == null || !k.isSlimefun()) continue;
            String id = k.getSfId();
            if (id != null && matBlack.contains(id.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * 异步全量扫描（主线程预热 → 异步提取 → 主线程回调）。
     *
     * @param done 扫描结束后的回调（主线程）；参数 = 注册条数
     */
    public CompletableFuture<Integer> scanAsync(Consumer<Integer> done) {
        return scanAsync(done, null);
    }

    /**
     * 异步全量扫描。
     *
     * @param done   扫描成功后的回调（主线程）；参数 = 注册条数
     * @param onFail 扫描抛异常时的回调（主线程），可为 null —— 用于回退到磁盘配方库
     */
    public CompletableFuture<Integer> scanAsync(Consumer<Integer> done, Runnable onFail) {
        if (scanning) return CompletableFuture.completedFuture(0);
        scanning = true;
        List<SlimefunItem> items;
        try {
            items = ordered(collectAllSlimefunItems());
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 收集物品列表失败，退回未排序顺序: " + t);
            items = collectAllSlimefunItems();
        }
        int warmed;
        try {
            warmed = RecipeExtractor.warmUp(items);
        } catch (Throwable t) {
            // 预热只是「提前触发静态初始化」的优化，失败绝不能挡住整轮扫描
            plugin.getLogger().warning("[等价交换] 主线程预热失败（已忽略，继续扫描）: " + t);
            warmed = 0;
        }
        plugin.getLogger().info("[等价交换] 扫描开始：遍历 " + items.size()
                + " 个 Slimefun 物品（主线程预热 " + warmed + " 个声明类"
                + (RecipeExtractor.broken() > 0 ? "，跳过 " + RecipeExtractor.broken() + " 个类加载失败的幽灵物品" : "")
                + "）");

        final List<SlimefunItem> scanItems = items;
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> doScan(scanItems));
        future.whenComplete((count, err) -> {
            scanning = false;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    plugin.getLogger().warning("[等价交换] 扫描失败: " + err);
                    if (onFail != null) onFail.run();
                    return;
                }
                lastScanMillis = System.currentTimeMillis();
                lastScanCount = count == null ? 0 : count;
                if (done != null) done.accept(lastScanCount);
            });
        });
        return future;
    }

    /**
     * 按 {@code scan.addon-order} 重排物品，让扫描按「逻辑 → 无尽 → 乱序 → 魔法 → 陌陌 → … → RSC」的顺序走。
     *
     * <p>腐竹 2026-10-26：<i>「改顺序会改变价格的，比如rsc里有很多把配方改简单的附属。如果先读jar版本，
     * 也就是原附属的配方，就可以避免昂贵物品变白菜价了。」</i>
     *
     * <p>排序必须在<b>主线程</b>做（{@code getAddon()} 摸的是注册表里的物品对象），
     * 顺便把优先级算好带进异步的 {@link #doScan(List)}。
     */
    private List<SlimefunItem> ordered(List<SlimefunItem> items) {
        java.util.List<String> order = config.addonOrder;
        java.util.List<String> orderLast = config.addonOrderLast;
        int lastBase = order.size() + 1;
        java.util.IdentityHashMap<SlimefunItem, Integer> prio = new java.util.IdentityHashMap<>();
        java.util.TreeMap<Integer, java.util.Map<String, Integer>> byRank = new java.util.TreeMap<>();

        for (SlimefunItem it : items) {
            String name = addonName(it);
            String low = name.toLowerCase(Locale.ROOT);
            int rank = order.size();      // 未列出：垫在「优先读」之后
            for (int k = 0; k < order.size(); k++) {
                String token = order.get(k);
                if (!token.isEmpty() && low.contains(token)) { rank = k; break; }
            }
            for (int k = 0; k < orderLast.size(); k++) {
                String token = orderLast.get(k);
                if (!token.isEmpty() && low.contains(token)) { rank = lastBase + k; break; }
            }
            prio.put(it, rank);
            byRank.computeIfAbsent(rank, r -> new java.util.TreeMap<>()).merge(name, 1, Integer::sum);
        }

        java.util.Set<ItemKey> core = new java.util.HashSet<>();
        for (SlimefunItem it : items) {
            if ("slimefun".equalsIgnoreCase(addonName(it))) {
                ItemKey k = ItemKey.of(it);
                if (k != null) core.add(k);
            }
        }
        lastCoreMachines = core;
        java.util.Map<ItemKey, Integer> itemRank = new java.util.HashMap<>(items.size() * 2);
        for (SlimefunItem it : items) {
            ItemKey k = ItemKey.of(it);
            if (k != null) itemRank.put(k, prio.getOrDefault(it, order.size()));
        }
        lastItemRank = itemRank;
        plugin.getLogger().info("[等价交换] 本体（Slimefun 核心）机器 " + core.size() + " 台：它们的配方优先于附属自定义机器");

        List<SlimefunItem> out = new ArrayList<>(items);
        // List.sort 是稳定排序：同一优先级内保持原注册顺序
        out.sort(java.util.Comparator.comparingInt(it -> prio.getOrDefault(it, order.size())));
        lastPriority = prio;

        StringBuilder sb = new StringBuilder();
        for (var e : byRank.entrySet()) {
            String label = e.getKey() < order.size() ? order.get(e.getKey())
                    : (e.getKey() >= lastBase ? "最后/" + orderLast.get(Math.min(e.getKey() - lastBase, orderLast.size() - 1))
                                              : "未列出");
            if (sb.length() > 0) sb.append(" → ");
            sb.append(label).append('(');
            boolean first = true;
            for (var n : e.getValue().entrySet()) {
                if (!first) sb.append('/');
                sb.append(n.getKey()).append(' ').append(n.getValue());
                first = false;
            }
            sb.append(')');
        }
        lastOrderSummary = sb.toString();
        plugin.getLogger().info("[等价交换] 本次读取顺序: " + lastOrderSummary);
        return out;
    }

    /** 物品归属的附属名（{@code getAddon().getName()}，取不到就用插件名兜底） */
    private static String addonName(SlimefunItem item) {
        try {
            var addon = item.getAddon();
            if (addon != null) {
                String n = addon.getName();
                if (n != null && !n.isBlank()) return n;
            }
        } catch (Throwable ignored) {}
        try {
            if (item.getAddon() != null) {
                String n = item.getAddon().getJavaPlugin().getName();
                if (n != null && !n.isBlank()) return n;
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    /**
     * 配方优先级 = 附属读取顺序 × 2 + 机器档位（本体机器 0 / 附属自定义机器 1）。
     *
     * <p>×2 是为了给「本体 / 自定义」留出一档：同一附属下本体机器的配方永远排在
     * 自定义机器之前，而下一个附属（哪怕它的配方更贵）依旧排在后面 —— 附属顺序仍是第一位。
     */
    private int recipePriority(int addonRank, Recipe r) {
        if (addonRank >= (Integer.MAX_VALUE / 2)) return Integer.MAX_VALUE;
        if (!lastCoreMachines.contains(r.getMachine())) {
            return addonRank * 2 + 1;                       // 附属自定义机器：按机器所属附属的档位
        }
        // 本体机器（增强工作台 / 远古祭坛 / 冶炼炉…）：配方属于「产出物品的附属」，并排在该档最前
        int rank = Integer.MAX_VALUE;
        for (ItemKey out : r.getOutputs().keySet()) {
            Integer k = lastItemRank.get(out);
            if (k != null && k < rank) rank = k;
        }
        if (rank == Integer.MAX_VALUE) rank = addonRank;     // 产出全是原版物品时按机器所属附属算
        return Math.min(rank, addonRank) * 2;
    }

    private int priorityOf(SlimefunItem item) {
        Integer p = lastPriority.get(item);
        return p == null ? Integer.MAX_VALUE : p;
    }

    private int doScan(List<SlimefunItem> items) {
        RecipeSanitizer.setEnabled(config.dropSelfLoop);
        RecipeSanitizer.resetCount();
        RecipeExtractor.setMultiblockEnabled(config.extractMultiblock);
        RecipeExtractor.setOwnRecipesEnabled(config.extractOwnRecipes);

        int broken = 0;
        long start = System.currentTimeMillis();
        List<Recipe> all = new ArrayList<>(20000);
        Set<ItemKey> produced = new HashSet<>(8192);
        Map<String, Integer> bySource = new TreeMap<>();

        // ---- 第一遍：真实机器配方 ----
        for (SlimefunItem item : items) {
            try {
                ItemKey key = ItemKey.of(item);
                if (key == null) continue;
                List<Recipe> got = RecipeExtractor.machineRecipes(item, key);
                int prio = priorityOf(item);
                for (Recipe r : got) r.setPriority(recipePriority(prio, r));
                for (Recipe r : got) {
                    all.add(r);
                    produced.addAll(r.getOutputs().keySet());
                    bySource.merge(tag(r.getSource()), 1, Integer::sum);
                }
            } catch (Throwable t) {
                // 单个物品炸掉只丢它自己的配方，绝不能带崩整轮扫描
                if (++broken == 1) plugin.getLogger().warning("[等价交换] 跳过无法读取的物品: " + t);
            }
        }
        int realCount = all.size();

        // ---- 第二遍：物品自身配方（兜底） ----
        int ownCount = 0;
        Map<String, Integer> ownByType = new TreeMap<>();
        if (RecipeExtractor.isOwnRecipesEnabled()) {
            for (SlimefunItem item : items) {
                try {
                    ItemKey key = ItemKey.of(item);
                    if (key == null) continue;
                    if (produced.contains(key)) continue;      // 已有真实配方 → 不补
                    Recipe r = RecipeExtractor.ownRecipe(item, key);
                    if (r == null) continue;
                    r.setPriority(recipePriority(priorityOf(item), r));
                    all.add(r);
                    produced.add(key);
                    ownCount++;
                    bySource.merge(tag(r.getSource()), 1, Integer::sum);
                    ownByType.merge(tag(r.getSource()), 1, Integer::sum);
                } catch (Throwable t) {
                    if (++broken == 1) plugin.getLogger().warning("[等价交换] 跳过无法读取的物品: " + t);
                }
            }
        }

        // ---- 净化 + 注册 ----
        List<Recipe> kept = new ArrayList<>(all.size());
        int black = 0;
        for (Recipe r : all) {
            if (blacklisted(r)) { black++; continue; }
            if (RecipeSanitizer.keep(r)) kept.add(r);
        }

        registry.clear();
        int added = registry.registerAll(kept);
        storage.saveAll(registry);
        // 每次重扫都让磁盘与本次结果完全一致：机器没了 / 被拉黑的旧文件一并删掉
        int pruned = storage.purgeStale(registry);
        if (pruned > 0) {
            plugin.getLogger().info("[等价交换] 清理过期配方文件 " + pruned + " 个（本次扫描已不产出这些机器）");
        }

        long ms = System.currentTimeMillis() - start;
        int dup = kept.size() - added;
        plugin.getLogger().info("[等价交换] 扫描完成: 真实机器配方 " + realCount
                + " 条，兜底配方 " + ownCount + " 条，黑名单(机器/材料) " + black
                + " 条，重复丢掉 " + dup + " 条（保留读取顺序更靠前的附属版本）"
                + "，去自环 " + RecipeSanitizer.dropped()
                + (broken > 0 ? " 条，跳过坏物品 " + broken + " 个" : "")
                + "，最终入库 " + added + " 条 / " + registry.machines().size()
                + " 台机器，耗时 " + ms + " ms");
        plugin.getLogger().info("[等价交换] 来源分布: " + bySource);
        writeReport(items.size(), realCount, ownCount, added, bySource, ownByType, ms);
        return added;
    }

    private static String tag(String source) {
        if (source == null) return "unknown";
        int i = source.indexOf(']');
        return i > 0 ? source.substring(0, i + 1) : source;
    }

    /** 扫描报告：{@code plugins/XinZhiExchange/scan-report.txt}（只读诊断，不参与定价） */
    private void writeReport(int items, int real, int own, int added,
                             Map<String, Integer> bySource, Map<String, Integer> ownByType, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 等价交换（独立版）配方扫描报告\n");
        sb.append("# 遍历 Slimefun 物品 ").append(items).append(" 个\n");
        sb.append("# 真实机器配方 ").append(real).append(" 条；兜底（物品自身配方） ")
                .append(own).append(" 条；入库 ").append(added).append(" 条；")
                .append(registry.machines().size()).append(" 台机器；耗时 ").append(ms).append(" ms\n");
        sb.append("# 读取顺序: ").append(lastOrderSummary).append('\n');
        sb.append("# 配置: 多方块=").append(config.extractMultiblock ? "开" : "关")
                .append("，物品自身配方=").append(config.extractOwnRecipes ? "开" : "关")
                .append("，丢自环=").append(config.dropSelfLoop ? "开" : "关").append("\n");
        sb.append("#\n# 来源分布\n");
        for (var e : bySource.entrySet()) sb.append("#   ").append(e.getKey()).append("  ").append(e.getValue()).append("\n");
        sb.append("#\n# 兜底配方（只在物品没有真实配方时才会出现）按配方类型统计\n");
        for (var e : ownByType.entrySet()) sb.append("#   ").append(e.getKey()).append("  ").append(e.getValue()).append("\n");
        sb.append("#\n# 每台机器的配方条数\n");
        List<ItemKey> machines = new ArrayList<>(registry.machines());
        machines.sort((a, b) -> Integer.compare(registry.ofMachine(b).size(), registry.ofMachine(a).size()));
        for (ItemKey m : machines) {
            sb.append(m.toString()).append('\t').append(registry.ofMachine(m).size()).append('\n');
        }
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) return;
            Files.write(new File(dir, "scan-report.txt").toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 写扫描报告失败: " + t);
        }
    }

    /** 枚举全部 Slimefun 物品（与主附属同款三级兜底） */
    public static List<SlimefunItem> collectAllSlimefunItems() {
        try {
            var reg = io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getRegistry();
            List<SlimefunItem> list = reg.getAllSlimefunItems();
            if (list != null && !list.isEmpty()) return list;
        } catch (Throwable ignored) {}
        try {
            Class<?> cls = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
            Object reg = cls.getMethod("getRegistry").invoke(null);
            Object v = reg.getClass().getMethod("getAllSlimefunItems").invoke(reg);
            if (v instanceof Collection<?> col) {
                List<SlimefunItem> out = new ArrayList<>(col.size());
                for (Object o : col) if (o instanceof SlimefunItem sf) out.add(sf);
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable ignored) {}
        return List.of();
    }

    /** 统计（命令用） */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipes", registry.size());
        m.put("machines", registry.machines().size());
        m.put("lastScanMillis", lastScanMillis);
        m.put("lastScanCount", lastScanCount);
        m.put("scanning", scanning);
        return m;
    }

    /** 诊断：列出某种前缀的机器（命令用） */
    public List<ItemKey> machinesMatching(String needle) {
        String n = needle == null ? "" : needle.toUpperCase(Locale.ROOT);
        List<ItemKey> out = new ArrayList<>();
        for (ItemKey m : registry.machines()) {
            if (m.toString().toUpperCase(Locale.ROOT).contains(n)) out.add(m);
        }
        return out;
    }
}
