package net.me.xinzhi.exchange.price;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.recipe.Recipe;
import net.me.xinzhi.exchange.recipe.RecipeRegistry;
import net.me.xinzhi.exchange.util.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 定价引擎：基础锚点 + 材料推导，<b>不考虑环</b>。
 *
 * <h2>规则（就三条）</h2>
 * <ol>
 *   <li><b>有锚点就用锚点</b>（prices.yml / 内置表），锚点定死，不再被配方改；</li>
 *   <li><b>没锚点的按配方算</b>：{@code 单价 = Σ(材料单价 × 数量) ÷ 产出数量}，
 *       只取「所有材料都已有价」的配方里最便宜的那条；</li>
 *   <li><b>结算后永不下调</b>（最小堆，定一个是一个）。</li>
 * </ol>
 *
 * <h2>⭐ 为什么"不处理环"反而是对的</h2>
 * <p>腐竹原话：「其实就不应该考虑环。环是因为制作这个用这个材料产出的物品
 * 能制作这个材料才导致不停降价。但其实不考虑这个环直接跳过反而接近真实定价。」
 *
 * <p>本引擎按上面第 3 条天然满足这一点：
 * 环里的物品只会经由<b>环外某条真实配方</b>拿到价（或者它自己有锚点）；
 * 一旦某个物品被定价就<b>不再回头降价</b>，所以「A 造 B、B 又造 A」不会把价格
 * 一轮轮往下压 —— 那条环边在结算时根本不会被用到（它的输入当时还没有价）。
 * 因此<b>不做 SCC、不做迭代、不做环剥离</b>，也就没有"价格取决于求解顺序"的问题。
 *
 * <h2>机器成本</h2>
 * <p>不算。机器的价值属于"玩家怎么更省"，不属于"这个物品值多少"；
 * 主附属那套机器价值分档 / 使用成本会让价格偏离材料真实成本，
 * 本附属只表达「材料 → 物品」。
 *
 * <h2>⭐ 机器必须先有价（机器依赖）</h2>
 * <p>腐竹原话：「按理说，在真实定价中，最先得出此材料的物品，肯定不是由该材料制作的，
 * 也就是机器的配方链条上没有它。现在的情况是，一些高级机器的配方让普通材料的价格变得超级低。」
 *
 * <p>所以一条配方只有在<b>它的机器本身已经算出价</b>时才能用来定价；
 * 虚拟机器 {@code RT_*} / 没有实物的配方类型算「随手可得」，不设门槛。
 * 最小堆按价格从小到大结算，机器先结算 ⇒ 机器的材料链里不可能含有它的产出，
 * 于是顺手禁掉了「用自己造出来的高级机器把自己造得更便宜」这种<b>隐性环</b>
 * （原来的图里机器不是输入，这条边根本不存在，所以 1 × X 的高级机器能把 X 压到地板价）。
 *
 * <p>注意这<b>不是</b>机器成本：机器价<b>不加</b>进产物单价，
 * 只当「这条配方能不能用」的开关；机器算不出价 ⇒ 它的配方一律跳过（宁可不报价）。
 */
public final class PriceEngine {

    /** 严格顺序重定价最多迭代几轮（每轮抬一批价，下游跟着抬） */
    private static final int ORDER_ROUNDS = 5;

    /** 价格来源：锚点（定价表/手工价）/ 配方推导 / 地理资源默认价 / 生物掉落兜底价 */
    public enum Origin { ANCHOR, RECIPE, GEO, DROP }

    /** 一个物品的价格推导过程（界面 / 命令溯源用） */
    public static final class Derivation {
        public final Origin origin;
        public final double unit;
        public final Recipe recipe;
        public final ItemKey machine;

        Derivation(Origin origin, double unit, Recipe recipe) {
            this.origin = origin;
            this.unit = unit;
            this.recipe = recipe;
            this.machine = recipe == null ? null : recipe.getMachine();
        }
    }

    private final Map<ItemKey, Double> prices = new HashMap<>(16384);
    private final Map<ItemKey, Derivation> derivations = new HashMap<>(16384);
    /**
     * 卖价表（换入用）：和买价不同——压缩物品封顶、生成器产物按机器价比例，
     * 并且<b>只降不升</b>。腐竹 2026-10-18 第八轮：「只降卖价不降买价」。
     */
    private final Map<ItemKey, Double> sellPrices = new HashMap<>(16384);

    private int anchorCount;
    private int geoCount;
    /** 按「生物掉落物兜底价」补上的物品数（腐竹 2026-10-26） */
    private int dropCount;
    private int sellAdjusted;
    private int recipeCount;
    private int unpricedCount;
    private int recipeTotal;
    private long lastBuildMillis;
    private int lastSteps;
    private int stage1Count;
    private int stage2Count;
    private int machineBlocked;
    private int orderForced;
    /** 机器引导开关（见 {@link #bootstrapMachines}）；默认开 */
    private boolean machineBootstrap = true;
    private int machineBootstrapped;

    // ============================================================

    public Map<ItemKey, Double> prices() { return Collections.unmodifiableMap(prices); }
    public int anchorCount() { return anchorCount; }
    /** 有多少个物品是按「地理资源默认价」补的（不来自定价表） */
    public int geoCount() { return geoCount; }
    /** 有多少个物品是按「生物掉落物兜底价」补的（只补配方算不出的缺口） */
    public int dropCount() { return dropCount; }
    /** 有多少个物品的「卖价」被压缩封顶 / 生成器规则压低 */
    public int sellAdjustedCount() { return sellAdjusted; }
    public int recipeCount() { return recipeCount; }
    public int unpricedCount() { return unpricedCount; }
    public int recipeTotal() { return recipeTotal; }
    public long lastBuildMillis() { return lastBuildMillis; }
    public int lastSteps() { return lastSteps; }
    public int stage1Count() { return stage1Count; }
    public int stage2Count() { return stage2Count; }
    /** 有多少条配方因为「机器本身没算出价」而不能用于定价 */
    public int machineBlockedRecipes() { return machineBlocked; }
    /** 有多少个物品因为「读取顺序更靠前的附属也造得出」而被抬到早期配方的成本（腐竹 m01739） */
    public int orderForcedCount() { return orderForced; }
    /** 机器引导出来的机器价台数（0.4.8） */
    public int machineBootstrapped() { return machineBootstrapped; }
    /** 是否在定价前先给「当机器用的那些物品」算一轮价（默认开） */
    public void setMachineBootstrap(boolean v) { this.machineBootstrap = v; }
    public int pricedCount() { return prices.size(); }

    public boolean hasPrice(ItemKey k) { return k != null && prices.containsKey(k); }

    /** 单价；没有价返回 0 */
    public double priceOf(ItemKey k) {
        if (k == null) return 0;
        Double v = prices.get(k);
        return v == null ? 0 : v;
    }

    public Derivation derive(ItemKey k) { return k == null ? null : derivations.get(k); }

    // ============================================================

    private static final class Offer implements Comparable<Offer> {
        final double unit;
        final ItemKey output;
        final Recipe recipe;
        Offer(double unit, ItemKey output, Recipe recipe) {
            this.unit = unit;
            this.output = output;
            this.recipe = recipe;
        }
        @Override public int compareTo(Offer o) { return Double.compare(unit, o.unit); }
    }

    /**
     * 重建价表（单阶段：所有配方一起算，不设机器门槛）。
     */
    public synchronized void rebuild(RecipeRegistry registry, Map<ItemKey, Double> anchors) {
        rebuild(registry, anchors, false, null, null);
    }

    /**
     * 重建价表（不设机器门槛）。
     */
    public synchronized void rebuild(RecipeRegistry registry, Map<ItemKey, Double> anchors,
                                     boolean staged, MachineTier tier) {
        rebuild(registry, anchors, staged, tier, null);
    }

    /**
     * 重建价表。
     *
     * @param staged 两阶段（腐竹 2026-10-18 要求）：<b>先</b>只用「粘液本体 / 原版」机器的配方定价，
     *               <b>再</b>用全部配方（含其它附属）给剩下的物品定价；已定的价永不下调。
     *               这样附属机器<b>改不动</b>本体物品的价 —— 附属只负责补齐本体算不出的物品。
     * @param tier   阶段判定器；{@code staged=false} 时可传 null
     * @param freeMachine 机器门槛（{@code null} = 不设门槛）：返回 true 表示这台机器
     *               「随手可得」（原版方块 / 虚拟 {@code RT_*} / 没有实物），不需要先有价；
     *               返回 false 的机器必须<b>先被定价</b>，它的配方才能拿来给别的物品定价。
     */
    public synchronized void rebuild(RecipeRegistry registry, Map<ItemKey, Double> anchors,
                                     boolean staged, MachineTier tier,
                                     Predicate<ItemKey> freeMachine) {
        rebuild(registry, anchors, staged, tier, freeMachine, null);
    }

    /**
     * 重建价表。
     *
     * @param geoDefaults 地理资源默认价（腐竹 2026-10-18：「除非有手动定价否则地理资源都按30算」）：
     *                    这些物品没有合成配方，按固定值补一条价；<b>定价表/手工价里的条目优先</b>
     *                    （不会覆盖锚点）。{@code null} = 不补。
     */
    public synchronized void rebuild(RecipeRegistry registry, Map<ItemKey, Double> anchors,
                                     boolean staged, MachineTier tier,
                                     Predicate<ItemKey> freeMachine,
                                     Map<ItemKey, Double> geoDefaults) {
        rebuild(registry, anchors, staged, tier, freeMachine, geoDefaults, null);
    }

    /**
     * 重建价表（含「卖价」规则，腐竹 2026-10-18 第八轮）。
     *
     * <h3>为什么要有两张价表</h3>
     * <p>腐竹原话：「只降卖价不降买价」。压缩物品（9 合 1 链，魔法糖/圆石最夸张）卖价封顶；
     * 由它们做出来的东西卖价跟着降；生成器产物按机器价的 0.1% 卖。
     * 买价（换出）继续用正常价表，所以 {@link #priceOf} 是买价、{@link #sellPriceOf} 是卖价。
     *
     * <h3>怎么保证「只降」</h3>
     * <p>卖价 = 用「被压低的锚点集合」重跑一遍完整定价（第 2 趟），最后再逐项
     * {@code min(卖价, 买价)} 兜底。定价过程对输入价是单调的，所以下游会自动跟着降，
     * 无关物品的价不变。
     *
     * @param sellRules 卖价规则；{@code null} = 不做特殊卖价（买卖同价）
     */
    public synchronized void rebuild(RecipeRegistry registry, Map<ItemKey, Double> anchors,
                                     boolean staged, MachineTier tier,
                                     Predicate<ItemKey> freeMachine,
                                     Map<ItemKey, Double> geoDefaults,
                                     SellRules sellRules) {
        rebuild(registry, anchors, staged, tier, freeMachine, geoDefaults, null, sellRules);
    }

    /**
     * 重建价表（含「生物掉落物兜底价」，腐竹 2026-10-26）。
     *
     * <p>顺序很重要：<b>先</b>用现有价（锚点 → 地理默认 → 配方推导）算，
     * <b>再</b>给还算不出价的物品补掉落价（腐竹 m01261 第 2 条：
     * 「就是按现在已经有的价先计算，再补这个掉落物的缺口」）。
     * 所以掉落价永远不会推翻配方推导出来的价。
     *
     * @param dropDefaults 掉落物兜底价（{@code ItemKey → 单价}），只补空缺；{@code null} = 不补
     */
    public synchronized void rebuild(RecipeRegistry registry, Map<ItemKey, Double> anchors,
                                     boolean staged, MachineTier tier,
                                     Predicate<ItemKey> freeMachine,
                                     Map<ItemKey, Double> geoDefaults,
                                     Map<ItemKey, Double> dropDefaults,
                                     SellRules sellRules) {
        long start = System.nanoTime();
        sellPrices.clear();
        lastSteps = 0;
        sellAdjusted = 0;
        geoCount = 0;
        dropCount = 0;
        stage1Count = 0;
        stage2Count = 0;
        machineBlocked = 0;

        Predicate<ItemKey> gate = freeMachine == null ? k -> true : freeMachine;

        Map<ItemKey, Double> normal = new HashMap<>(16384);
        Map<ItemKey, Derivation> normalDer = new HashMap<>(16384);

        List<Recipe> all = new ArrayList<>(registry == null ? List.of() : registry.all());
        recipeTotal = all.size();

        // 0) 机器引导：先把「当机器用的那些物品」的价算出来，再按正常门槛跑
        machineBootstrapped = 0;
        Map<ItemKey, Double> machineSeed = machineBootstrap
                ? bootstrapMachines(all, gate, anchors, geoDefaults, dropDefaults) : Map.of();
        machineBootstrapped = machineSeed.size();

        // 1~1.7) 锚点 / 地理默认 → 配方推导，⭐ 严格按附属读取顺序迭代（见 orderForcedPrices）
        Map<ItemKey, Double> forced = new LinkedHashMap<>();
        Map<ItemKey, Recipe> forcedRecipes = new LinkedHashMap<>();
        Map<ItemKey, Double> floor = new HashMap<>(16384);   // 抬价前那一版价表：只许在这之上抬，绝不许降
        int normalStage1 = 0;
        int normalStage2 = 0;
        for (int round = 0; round <= ORDER_ROUNDS; round++) {
            normal.clear();
            normalDer.clear();
            if (forced.isEmpty()) {
                fillAnchors(normal, normalDer, anchors);
            } else {
                Map<ItemKey, Double> merged = new LinkedHashMap<>();
                if (anchors != null) merged.putAll(anchors);
                for (Map.Entry<ItemKey, Double> e : forced.entrySet()) {
                    merged.putIfAbsent(e.getKey(), e.getValue());
                }
                fillAnchors(normal, normalDer, merged);
            }
            fillGeo(normal, normalDer, geoDefaults);
            seedDrops(normal, normalDer, dropDefaults);          // ⭐ 掉落价先当种子，让下游配方能算出来
            seedMachinePrices(normal, normalDer, machineSeed);   // ⭐ 机器引导价：让它们的配方能参与报价
            buildAll(all, staged, tier, gate, normal, normalDer);
            if (staged) { normalStage1 = stage1Count; normalStage2 = stage2Count; }

            if (round == 0) floor.putAll(normal);
            Map<ItemKey, Double> next = new LinkedHashMap<>();
            Map<ItemKey, Recipe> nextRecipes = new LinkedHashMap<>();
            orderForcedPrices(all, gate, normal, normalDer, anchors, forced, floor, next, nextRecipes);
            boolean stable = sameValues(forced, next);
            forced = next;
            forcedRecipes = nextRecipes;
            if (stable) break;
        }
        stage1Count = normalStage1;
        stage2Count = normalStage2;
        // 这些物品的价就来自「读取顺序最靠前的那个附属的配方」——如实标成配方推导
        for (Map.Entry<ItemKey, Recipe> e : forcedRecipes.entrySet()) {
            Double v = normal.get(e.getKey());
            if (v != null) normalDer.put(e.getKey(), new Derivation(Origin.RECIPE, v, e.getValue()));
        }
        anchorCount = countAnchorsIn(anchors, normal);
        geoCount = countByOrigin(normalDer, Origin.GEO);
        orderForced = forced.size();

        machineBlocked = countMachineBlocked(all, gate, normal);

        // 1.8) 生物掉落物兜底价：只补「配方链算不通」的缺口
        fillDrops(normal, normalDer, dropDefaults);
        dropCount = countByOrigin(normalDer, Origin.DROP);

        recipeCount = normal.size() - anchorCount - geoCount - dropCount;
        unpricedCount = countUnpriced(registry, normal);

        // 2) 卖价：压缩封顶 + 生成器产物，只降不升
        Map<ItemKey, Double> overrides = sellOverrides(registry, normal, anchors, sellRules);
        sellAdjusted = overrides.size();
        if (overrides.isEmpty()) {
            sellPrices.putAll(normal);
        } else {
            Map<ItemKey, Double> sellAnchors = new LinkedHashMap<>();
            if (anchors != null) sellAnchors.putAll(anchors);
            for (Map.Entry<ItemKey, Double> e : overrides.entrySet()) {
                sellAnchors.putIfAbsent(e.getKey(), e.getValue());
            }
            for (Map.Entry<ItemKey, Double> e : forced.entrySet()) {
                sellAnchors.putIfAbsent(e.getKey(), e.getValue());
            }
            Map<ItemKey, Double> sell = new HashMap<>(16384);
            Map<ItemKey, Derivation> sellDer = new HashMap<>(16384);
            fillAnchors(sell, sellDer, sellAnchors);
            fillGeo(sell, sellDer, geoDefaults);
            seedDrops(sell, sellDer, dropDefaults);
            seedMachinePrices(sell, sellDer, machineSeed);
            for (Map.Entry<ItemKey, Double> e : forced.entrySet()) sell.putIfAbsent(e.getKey(), e.getValue());
            buildAll(all, staged, tier, gate, sell, sellDer);
            sell.replaceAll((k, v) -> {
                Double n = normal.get(k);
                return n == null ? v : Math.min(v, n);
            });
            sellPrices.putAll(sell);
            stage1Count = normalStage1;      // 进度数字只统计买价那一趟
            stage2Count = normalStage2;
        }

        // 3) 卖价也要有这一层：掉落物同样没有别的来源，买卖同价即可（只补空缺）
        if (dropDefaults != null) {
            for (Map.Entry<ItemKey, Double> e : dropDefaults.entrySet()) {
                ItemKey k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                double v = e.getValue();
                if (!(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
                sellPrices.putIfAbsent(k, v);
            }
        }

        prices.clear();
        prices.putAll(normal);
        derivations.clear();
        derivations.putAll(normalDer);
        lastBuildMillis = (System.nanoTime() - start) / 1_000_000;
    }

    /** 两阶段 / 单阶段结算（写进给定的两张表） */
    private void buildAll(List<Recipe> all, boolean staged, MachineTier tier,
                          Predicate<ItemKey> gate,
                          Map<ItemKey, Double> pm, Map<ItemKey, Derivation> dm) {
        if (staged && tier != null) {
            List<Recipe> core = new ArrayList<>(all.size());
            for (Recipe r : all) if (tier.isCore(r.getMachine())) core.add(r);
            int before = pm.size();
            runPass(core, gate, pm, dm);
            stage1Count = pm.size() - before;

            before = pm.size();
            runPass(all, gate, pm, dm);
            stage2Count = pm.size() - before;
        } else {
            runPass(all, gate, pm, dm);
        }
    }

    /**
     * ⭐ 严格按附属读取顺序定价（腐竹 m01739，2026-10-27）。
     *
     * <p>原话：「我还是建议严格按照列表顺序来，比如无尽中的毕业物品无尽锭，在原附属里造价是4w多，
     * 而现在因为被rsc附属里的粉羊科技先抢走，导致emc值才7000多。」
     *
     * <h3>规则</h3>
     * <p>每个物品先找「读取顺序最靠前、能产出它」的档位（{@link Recipe#getPriority()} 最小），
     * 只要那档有一条配方在当前价表下算得出来，这个物品的价就该用它的成本；
     * 早期档算不出来时才回落到后期附属（RSC 那些廉价配方只负责兜底）。
     *
     * <h3>为什么不在 runPass 里直接过滤</h3>
     * <p>试过「每个物品只让最早档位报价」，价链直接崩：有价 5857 → 1727、算不出 → 7204
     * （机器价一起断，13,674 条配方连坐）。所以改成：先完整定价，再把该抬的物品记进
     * {@code forced} 当锚点，重跑整张价表（下游跟着抬），每轮用新价表重算一遍 cost，
     * 直到 {@code forced} 不再变化（最多 {@link #ORDER_ROUNDS} 轮）。
     *
     * <p>真实锚点（prices.yml / 手工价 / 内置表）一律不动 —— 腐竹没定价的物品不走这条，
     * 地理默认价、掉落兜底价也不动。
     */
    private void orderForcedPrices(List<Recipe> all, Predicate<ItemKey> gate,
                                   Map<ItemKey, Double> pm, Map<ItemKey, Derivation> dm,
                                   Map<ItemKey, Double> anchors, Map<ItemKey, Double> prevForced,
                                   Map<ItemKey, Double> floor,
                                   Map<ItemKey, Double> out, Map<ItemKey, Recipe> outRecipes) {
        Map<ItemKey, Integer> minRank = new HashMap<>(all.size() * 2);
        for (Recipe r : all) {
            for (ItemKey outKey : r.getOutputs().keySet()) {
                minRank.merge(outKey, r.getPriority(), Math::min);
            }
        }
        for (Recipe r : all) {
            if (!usable(r, gate, pm)) continue;
            double cost;
            try {
                cost = unitCost(r, pm);
            } catch (IllegalStateException e) {
                continue;
            }
            for (Map.Entry<ItemKey, Integer> o : r.getOutputs().entrySet()) {
                ItemKey k = o.getKey();
                Integer rank = minRank.get(k);
                if (rank == null || rank != r.getPriority()) continue;   // 只看最早档位的配方
                if (anchors != null && anchors.containsKey(k)) continue; // 真锚点不动
                int qty = Math.max(1, o.getValue());
                double v = cost / qty;
                if (!(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
                Double cur = pm.get(k);
                Double base = floor.get(k);
                if (base != null && v <= base) continue;       // 不降到原价以下
                boolean wasForced = prevForced != null && prevForced.containsKey(k);
                Derivation d = dm.get(k);
                boolean fromLater = d != null && d.origin == Origin.RECIPE && d.recipe != null
                        && d.recipe.getPriority() > r.getPriority();
                if (!wasForced && !fromLater) continue;         // 价本来就是这个档位给的，不用动
                if (!wasForced && cur != null && v <= cur) continue;   // 只抬不降
                Double prev = out.get(k);
                if (prev == null || v < prev) {
                    out.put(k, v);
                    outRecipes.put(k, r);
                }
            }
        }
    }

    private static boolean sameValues(Map<ItemKey, Double> a, Map<ItemKey, Double> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<ItemKey, Double> e : a.entrySet()) {
            Double v = b.get(e.getKey());
            if (v == null || Math.abs(v - e.getValue()) > 1e-6) return false;
        }
        return true;
    }

    /** 配方在当前价表下能不能算：材料全有价 + 机器有价（或随手可得） */
    private boolean usable(Recipe r, Predicate<ItemKey> gate, Map<ItemKey, Double> pm) {
        for (ItemKey in : r.getInputs().keySet()) if (!pm.containsKey(in)) return false;
        ItemKey machine = r.getMachine();
        return machine == null || gate.test(machine) || pm.containsKey(machine);
    }

    /** 配方总成本（不含产出数除）；材料缺价时抛 IllegalStateException */
    private double unitCost(Recipe r, Map<ItemKey, Double> pm) {
        double total = 0;
        for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
            Double p = pm.get(e.getKey());
            if (p == null) throw new IllegalStateException("input unpriced");
            total += p * e.getValue();
        }
        return total;
    }

    private static int countAnchorsIn(Map<ItemKey, Double> anchors, Map<ItemKey, Double> pm) {
        if (anchors == null) return 0;
        int c = 0;
        for (ItemKey k : anchors.keySet()) if (pm.containsKey(k)) c++;
        return c;
    }

    private static int countByOrigin(Map<ItemKey, Derivation> dm, Origin origin) {
        int c = 0;
        for (Derivation d : dm.values()) if (d.origin == origin) c++;
        return c;
    }

    /**
     * 生物掉落物兜底价：<b>只补空缺</b>（锚点 / 地理默认 / 配方推导已有的价一律不动）。
     * 见 {@link MobDropPrices} 的口径说明。
     */
    /**
     * 掉落兜底价「当种子」用：必须在配方推导<b>之前</b>就写进已定价集合。
     * 道理：只能靠挖方块 / 打怪拿到的物品，没有配方能算出它的价，
     * 如果等推导跑完再补，那些「拿它做材料」的物品就永远缺一条依赖边，链子断在那儿。
     */
    private void seedDrops(Map<ItemKey, Double> sink, Map<ItemKey, Derivation> der,
                           Map<ItemKey, Double> dropDefaults) {
        if (dropDefaults == null || dropDefaults.isEmpty()) return;
        for (Map.Entry<ItemKey, Double> e : dropDefaults.entrySet()) {
            ItemKey k = e.getKey();
            Double v = e.getValue();
            if (k == null || v == null || sink.containsKey(k)) continue;
            if (!(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
            sink.put(k, v);
            der.put(k, new Derivation(Origin.DROP, v, null));
        }
    }

    private void fillDrops(Map<ItemKey, Double> sink, Map<ItemKey, Derivation> der,
                           Map<ItemKey, Double> dropDefaults) {
        if (dropDefaults == null || dropDefaults.isEmpty()) return;
        for (Map.Entry<ItemKey, Double> e : dropDefaults.entrySet()) {
            ItemKey k = e.getKey();
            Double v = e.getValue();
            if (k == null || v == null || sink.containsKey(k)) continue;
            if (!(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
            sink.put(k, v);
            der.put(k, new Derivation(Origin.DROP, v, null));
            dropCount++;
        }
    }

    private static int fillAnchors(Map<ItemKey, Double> sink, Map<ItemKey, Derivation> der,
                                   Map<ItemKey, Double> anchors) {
        if (anchors == null) return 0;
        for (Map.Entry<ItemKey, Double> e : anchors.entrySet()) {
            double v = e.getValue() == null ? Double.NaN : e.getValue();
            ItemKey k = e.getKey();
            if (k == null || !(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
            sink.put(k, v);
            der.put(k, new Derivation(Origin.ANCHOR, v, null));
        }
        return sink.size();
    }

    private static void fillGeo(Map<ItemKey, Double> sink, Map<ItemKey, Derivation> der,
                                Map<ItemKey, Double> geoDefaults) {
        if (geoDefaults == null) return;
        for (Map.Entry<ItemKey, Double> e : geoDefaults.entrySet()) {
            ItemKey k = e.getKey();
            double v = e.getValue() == null ? Double.NaN : e.getValue();
            if (k == null || sink.containsKey(k)) continue;
            if (!(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
            sink.put(k, v);
            der.put(k, new Derivation(Origin.GEO, v, null));
        }
    }

    /**
     * 机器引导（0.4.8）：先把「当机器用的那些物品」的价算出来。
     *
     * <p>为什么需要：附属机器的造价配方通常挂在<b>别的</b>机器上，而那些机器自己也没价 ——
     * 于是「机器没价 ⇒ 它的配方不报价」把所有附属配方一起锁死（测试服实测
     * 14,145 条里被跳过 13,934 条、算不出 3,153 个物品，连粘液盔甲都成了 0）。
     *
     * <p>做法：只忽略机器门槛跑一趟，<b>只取机器键的价</b>当种子（不碰别的物品的定价），
     * 再让正常那一趟带上这些种子。种子价永远输给真实锚点（{@code putIfAbsent}）。
     */
    private Map<ItemKey, Double> bootstrapMachines(List<Recipe> all, Predicate<ItemKey> gate,
                                                   Map<ItemKey, Double> anchors,
                                                   Map<ItemKey, Double> geoDefaults,
                                                   Map<ItemKey, Double> dropDefaults) {
        Set<ItemKey> machines = new HashSet<>(64);
        for (Recipe r : all) {
            ItemKey m = r.getMachine();
            if (m != null && !gate.test(m)) machines.add(m);
        }
        if (machines.isEmpty()) return Map.of();
        Map<ItemKey, Double> tmp = new HashMap<>(16384);
        Map<ItemKey, Derivation> tmpDer = new HashMap<>(16384);
        fillAnchors(tmp, tmpDer, anchors);
        fillGeo(tmp, tmpDer, geoDefaults);
        seedDrops(tmp, tmpDer, dropDefaults);
        runPass(all, k -> true, tmp, tmpDer);
        Map<ItemKey, Double> out = new LinkedHashMap<>();
        for (ItemKey m : machines) {
            Double v = tmp.get(m);
            if (v != null && v > 0) out.put(m, v);
        }
        return out;
    }

    /** 把机器引导价塞进价表（只补空缺：真实锚点 / 已定的价优先） */
    private void seedMachinePrices(Map<ItemKey, Double> pm, Map<ItemKey, Derivation> dm,
                                   Map<ItemKey, Double> machineSeed) {
        if (machineSeed == null || machineSeed.isEmpty()) return;
        for (Map.Entry<ItemKey, Double> e : machineSeed.entrySet()) {
            if (pm.containsKey(e.getKey())) continue;
            pm.put(e.getKey(), e.getValue());
            dm.put(e.getKey(), new Derivation(Origin.RECIPE, e.getValue(), null));
        }
    }

    /** 一趟最小堆结算：材料全有价、且机器已有价（或随手可得）的配方才能报价，定下就不再改 */
    private void runPass(List<Recipe> recipes, Predicate<ItemKey> freeMachine,
                         Map<ItemKey, Double> pm, Map<ItemKey, Derivation> dm) {
        int n = recipes.size();
        int[] remaining = new int[n];
        Map<ItemKey, List<Integer>> waitingOn = new HashMap<>(n * 4);
        for (int i = 0; i < n; i++) {
            Recipe r = recipes.get(i);
            int miss = 0;
            for (ItemKey in : r.getInputs().keySet()) {
                if (!pm.containsKey(in)) {
                    miss++;
                    waitingOn.computeIfAbsent(in, k -> new ArrayList<>(2)).add(i);
                }
            }
            // ⭐ 机器也是一条依赖边（不加价，只决定"能不能用这条配方"）
            ItemKey machine = r.getMachine();
            if (machine != null && !freeMachine.test(machine) && !pm.containsKey(machine)) {
                miss++;
                waitingOn.computeIfAbsent(machine, k -> new ArrayList<>(2)).add(i);
            }
            remaining[i] = miss;
        }

        PriorityQueue<Offer> heap = new PriorityQueue<>();
        for (int i = 0; i < n; i++) if (remaining[i] == 0) pushOffers(heap, recipes.get(i), pm);

        while (!heap.isEmpty()) {
            Offer best = heap.poll();
            lastSteps++;
            if (pm.containsKey(best.output)) continue;      // 已定，永不下调
            pm.put(best.output, best.unit);
            dm.put(best.output, new Derivation(Origin.RECIPE, best.unit, best.recipe));

            List<Integer> waiters = waitingOn.get(best.output);
            if (waiters != null) {
                for (int idx : waiters) {
                    if (--remaining[idx] == 0) pushOffers(heap, recipes.get(idx), pm);
                }
            }
        }
    }

    private void pushOffers(PriorityQueue<Offer> heap, Recipe r, Map<ItemKey, Double> pm) {
        if (r.getOutputs().isEmpty()) return;
        double total = 0;
        for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
            Double p = pm.get(e.getKey());
            if (p == null) return;                  // 不该发生：只对"全部有价"的配方调用
            total += p * e.getValue();
            if (Double.isInfinite(total) || Double.isNaN(total)) return;
        }
        if (!(total > 0)) return;
        for (Map.Entry<ItemKey, Integer> out : r.getOutputs().entrySet()) {
            int qty = Math.max(1, out.getValue());
            heap.offer(new Offer(total / qty, out.getKey(), r));
        }
    }

    /** 有多少条配方的机器最终没算出价（这些配方一律不参与定价） */
    private int countMachineBlocked(List<Recipe> recipes, Predicate<ItemKey> freeMachine,
                                    Map<ItemKey, Double> pm) {
        int c = 0;
        for (Recipe r : recipes) {
            ItemKey m = r.getMachine();
            if (m == null || freeMachine.test(m)) continue;
            if (!pm.containsKey(m)) c++;
        }
        return c;
    }

    private int countUnpriced(RecipeRegistry registry, Map<ItemKey, Double> pm) {
        if (registry == null) return 0;
        Set<ItemKey> all = new HashSet<>(16384);
        for (Recipe r : registry.all()) {
            all.addAll(r.getInputs().keySet());
            all.addAll(r.getOutputs().keySet());
        }
        int c = 0;
        for (ItemKey k : all) if (!pm.containsKey(k)) c++;
        return c;
    }

    // ============================================================
    // 卖价规则（腐竹 2026-10-18 第八轮）
    // ============================================================

    /** 卖价规则：压缩物品封顶 + 生成器产物按机器价比例（都只降不升） */
    public record SellRules(double compressedCap, List<String> compressedKeywords,
                            double generatorRate, List<String> generatorKeywords) {}

    /**
     * 哪些物品的「卖价」要压：压缩物品（id/名字命中关键词，封顶 100）与生成器产物
     * （机器 id 命中 GENERATOR、且输出不在机器自身配方链里 ⇒ 机器价的 0.1%，不按产出数除）。
     * 锚点（定价表 / 手工价）里的物品一律不动。
     */
    private Map<ItemKey, Double> sellOverrides(RecipeRegistry registry,
                                               Map<ItemKey, Double> normal,
                                               Map<ItemKey, Double> anchors,
                                               SellRules rules) {
        if (rules == null || normal.isEmpty()) return Map.of();
        Map<ItemKey, Double> out = new HashMap<>();

        if (rules.compressedCap() > 0 && rules.compressedKeywords() != null) {
            Set<ItemKey> compressionOutputs = compressionOutputs(registry);
            for (Map.Entry<ItemKey, Double> e : normal.entrySet()) {
                ItemKey k = e.getKey();
                if (anchors != null && anchors.containsKey(k)) continue;
                if (!isCompressed(k, rules.compressedKeywords(), compressionOutputs)) continue;
                double capped = Math.min(e.getValue(), rules.compressedCap());
                if (capped < e.getValue()) out.merge(k, capped, Math::min);
            }
        }

        if (rules.generatorRate() > 0 && registry != null && rules.generatorKeywords() != null) {
            Map<ItemKey, Set<ItemKey>> chains = new HashMap<>();
            for (Recipe r : registry.all()) {
                ItemKey machine = r.getMachine();
                if (machine == null || !matchesId(machine, rules.generatorKeywords())) continue;
                double machinePrice = normal.getOrDefault(machine, 0.0);
                if (!(machinePrice > 0)) continue;
                double target = machinePrice * rules.generatorRate();
                if (!(target > 0)) continue;
                Set<ItemKey> chain = chains.computeIfAbsent(machine, k -> machineChain(registry, k, 6));
                for (ItemKey product : r.getOutputs().keySet()) {
                    if (chain.contains(product)) continue;       // 机器配方链里就有它 ⇒ 不是"生成"
                    if (anchors != null && anchors.containsKey(product)) continue;
                    Double cur = normal.get(product);
                    if (cur == null || !(cur > target)) continue;
                    out.merge(product, target, Math::min);
                }
            }
        }
        return out;
    }

    /**
     * 是不是「压缩物品」：id 命中关键词直接算；只有<b>显示名</b>命中的，
     * 还必须真的是「9 合 1 / N 合 1」那条压缩配方的产物 —— 否则一台叫「压缩机」的机器
     * 也会被当成压缩物品封顶卖价。
     */
    private static boolean isCompressed(ItemKey k, List<String> keywords, Set<ItemKey> compressionOutputs) {
        String id = k.getSfId();
        String name = null;
        try { name = k.getDisplayName().replaceAll("§.", ""); } catch (Throwable ignored) {}
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            if (id != null && id.toUpperCase(Locale.ROOT).contains(kw.toUpperCase(Locale.ROOT))) return true;
            if (name != null && name.contains(kw) && compressionOutputs.contains(k)) return true;
        }
        return false;
    }

    /** 所有「单一材料 × N → 1 个」配方的产物（压缩链的结构特征） */
    private static Set<ItemKey> compressionOutputs(RecipeRegistry registry) {
        Set<ItemKey> out = new HashSet<>();
        if (registry == null) return out;
        for (Recipe r : registry.all()) {
            if (r.getInputs().size() != 1 || r.getOutputs().size() != 1) continue;
            int in = r.getInputs().values().iterator().next();
            if (in < 2) continue;
            Map.Entry<ItemKey, Integer> o = r.getOutputs().entrySet().iterator().next();
            if (o.getValue() == 1) out.add(o.getKey());
        }
        return out;
    }

    private static boolean matchesId(ItemKey k, List<String> keywords) {
        String id = k.getSfId();
        if (id == null) return false;
        String upper = id.toUpperCase(Locale.ROOT);
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && upper.contains(kw.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 机器自身的配方链（造这台机器要用到的材料，最多看 6 层） */
    private static Set<ItemKey> machineChain(RecipeRegistry registry, ItemKey machine, int maxDepth) {
        Set<ItemKey> seen = new HashSet<>();
        List<ItemKey> frontier = new ArrayList<>();
        frontier.add(machine);
        for (int depth = 0; depth < maxDepth; depth++) {
            List<ItemKey> next = new ArrayList<>();
            for (ItemKey cur : frontier) {
                for (Recipe r : registry.producersOf(cur)) {
                    for (ItemKey in : r.getInputs().keySet()) {
                        if (seen.add(in)) next.add(in);
                    }
                }
            }
            if (next.isEmpty()) break;
            frontier = next;
        }
        return seen;
    }

    /** 卖价（换入）；没有特殊规则时等于买价 */
    public double sellPriceOf(ItemKey k) {
        if (k == null) return 0;
        Double v = sellPrices.get(k);
        return v == null ? priceOf(k) : v;
    }

    /** 全量价表快照（命令 / 导出用） */
    public Map<ItemKey, Double> snapshotPrices() {
        return new LinkedHashMap<>(prices);
    }

    /** 诊断：这条配方现在值多少（材料全有价才算得出来） */
    public double costOf(Recipe r) {
        double total = 0;
        for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
            Double p = prices.get(e.getKey());
            if (p == null) return Double.NaN;
            total += p * e.getValue();
        }
        return total;
    }

    public Collection<ItemKey> pricedKeys() { return Collections.unmodifiableCollection(prices.keySet()); }
}
