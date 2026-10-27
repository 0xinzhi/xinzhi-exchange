package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.price.PriceEngine;
import net.me.xinzhi.exchange.recipe.Recipe;
import net.me.xinzhi.exchange.recipe.RecipeRegistry;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * 自动合成台的报价引擎：在「只有已装机器 + 终端能买的叶子」的约束下，
 * 找一棵最便宜、深度/规模有上限的配方树。
 *
 * <p>规则（腐竹 2026-10-19）：
 * <ul>
 *   <li>目标必须是超上限物品；低于上限的物品永远按买价当叶子，不用机器造；</li>
 *   <li>每层 5% 手续费；每个节点收费不低于该物品标价（防锚点倒挂）；</li>
 *   <li>机器只认第一排放进去的那些（原版 / RT_ 虚拟机器视为随手可得）；</li>
 *   <li>深度 ≤10、叶子 ≤100,000。</li>
 * </ul>
 *
 * <p>每个节点算「单位成本」，叶子 = 终端买价。环用递归栈挡；
 * 同一物品只算一次（memo），整棵图一次报价是 O(配方数)。
 */
public final class CraftPlanner {

    public static final double FEE = 0.05;
    public static final int MAX_DEPTH = 10;
    public static final long MAX_LEAVES = 100_000;

    private final PriceEngine engine;
    private final RecipeRegistry registry;
    private final DoubleSupplier tradeMax;

    public CraftPlanner(PriceEngine engine, RecipeRegistry registry, DoubleSupplier tradeMax) {
        this.engine = engine;
        this.registry = registry;
        this.tradeMax = tradeMax;
    }

    // ============================================================
    // 对外结果
    // ============================================================

    public static final class Plan {
        public final ItemKey target;
        public final boolean feasible;
        public final String reason;
        /** 一次顶层合成的总价（含 5%/层；已按 max(成本, 标价) 兜底） */
        public final double cost;
        /** 一次顶层合成实际产出多少个目标 */
        public final int outputAmount;
        public final int depth;
        /** 一次顶层合成要买的叶子件数（向上取整） */
        public final long leaves;
        /** 一次顶层合成每种叶子要多少（已乘产出数，可能带小数） */
        public final Map<ItemKey, Double> leafNeeds;
        /** 一次顶层合成的副产物（数量） */
        public final Map<ItemKey, Integer> byproducts;
        public final Recipe recipe;
        public final ItemKey blocker;
        /** 整棵树的节点 → 选中的配方（叶子不在里面） */
        public final Map<ItemKey, Recipe> tree;
        /** 这棵树要用到的真实机器（原版 / RT_ 虚拟机器不算） */
        public final Set<ItemKey> machines;

        Plan(ItemKey target, boolean feasible, String reason, double cost, int outputAmount,
             int depth, long leaves, Map<ItemKey, Double> leafNeeds,
             Map<ItemKey, Integer> byproducts, Recipe recipe, ItemKey blocker,
             Map<ItemKey, Recipe> tree, Set<ItemKey> machines) {
            this.target = target;
            this.feasible = feasible;
            this.reason = reason;
            this.cost = cost;
            this.outputAmount = outputAmount;
            this.depth = depth;
            this.leaves = leaves;
            this.leafNeeds = leafNeeds;
            this.byproducts = byproducts;
            this.recipe = recipe;
            this.blocker = blocker;
            this.tree = tree;
            this.machines = machines;
        }

        static Plan fail(ItemKey target, String reason, ItemKey blocker) {
            return new Plan(target, false, reason, 0, 0, 0, 0,
                    Map.of(), Map.of(), null, blocker, Map.of(), Set.of());
        }
    }

    // ============================================================
    // 一次报价会话（memo / 栈都在这，不跨机器集合复用）
    // ============================================================

    public Plan plan(ItemKey target, Set<ItemKey> machines) {
        if (target == null) return Plan.fail(null, "没有目标", null);
        return new Session(machines, tradeMax.getAsDouble()).plan(target);
    }

    /** 全部「有价机器」—— 搜索列表按这个集合算「理论上能不能造、要哪几台」 */
    public Set<ItemKey> allMachines() {
        Set<ItemKey> out = new LinkedHashSet<>();
        for (ItemKey m : registry.machines()) {
            if (engine.priceOf(m) > 0) out.add(m);
        }
        return out;
    }

    /**
     * 搜索列表用：把<b>所有</b>超上限的有价物品都算一遍（可行不可行都给），
     * 结果按「全部有价机器」算，所以每条的 {@link Plan#machines} 就是理论所需机器。
     *
     * <p>一次 DP 会话算完所有目标，并按（机器集合, 上限）缓存 —— 翻页/重开不再算。
     */
    public Map<ItemKey, Plan> catalogOverCap(Collection<ItemKey> candidates) {
        Set<ItemKey> machines = allMachines();
        double cap = tradeMax.getAsDouble();
        Map<ItemKey, Plan> cached = catalogCache;
        if (cached != null && cap == catalogCap && machines.equals(catalogMachines)) return cached;

        Session s = new Session(machines, cap);
        Map<ItemKey, Plan> out = new LinkedHashMap<>();
        for (ItemKey k : candidates) {
            if (!(engine.priceOf(k) > cap)) continue;
            out.put(k, s.plan(k));
        }
        catalogMachines = machines;
        catalogCap = cap;
        catalogCache = out;
        return out;
    }

    /** 缓存（价格没重建、上限没改时可复用） */
    private volatile Map<ItemKey, Plan> catalogCache;
    private volatile Set<ItemKey> catalogMachines = Set.of();
    private volatile double catalogCap = Double.NaN;

    /** 价表重建后清缓存 */
    public void invalidate() {
        catalogCache = null;
        catalogMachines = Set.of();
        catalogCap = Double.NaN;
    }

    /** 批量可行性：给搜索列表用；同一份 memo 只算一遍 */
    public Map<ItemKey, Plan> catalog(Set<ItemKey> machines, Collection<ItemKey> candidates) {
        Session s = new Session(machines, tradeMax.getAsDouble());
        Map<ItemKey, Plan> out = new LinkedHashMap<>();
        for (ItemKey k : candidates) {
            if (!(engine.priceOf(k) > s.cap)) continue;
            Plan p = s.plan(k);
            if (p.feasible) out.put(k, p);
        }
        return out;
    }

    private final class Session {
        private final Set<ItemKey> machines;
        private final double cap;
        private final Map<ItemKey, Node> memo = new HashMap<>(4096);
        private final Set<ItemKey> stack = new HashSet<>(64);

        Session(Set<ItemKey> machines, double cap) {
            this.machines = machines == null ? Set.of() : machines;
            this.cap = cap;
        }

        Plan plan(ItemKey target) {
            double p = engine.priceOf(target);
            if (!(p > 0)) return Plan.fail(target, "这个物品没有价", target);
            if (p <= cap) return Plan.fail(target, "低于兑换上限，直接去终端买", null);

            Node n = solve(target);
            if (!n.feasible) return Plan.fail(target, n.reason, n.blocker);

            int outQty = n.recipe == null ? 1
                    : Math.max(1, n.recipe.getOutputs().getOrDefault(target, 1));
            double cost = n.unitCost * outQty;

            Map<ItemKey, Double> needs = new LinkedHashMap<>();
            for (Map.Entry<ItemKey, Double> e : n.leafNeeds.entrySet()) {
                needs.put(e.getKey(), e.getValue() * outQty);
            }
            Map<ItemKey, Integer> by = new LinkedHashMap<>();
            if (n.recipe != null) {
                for (Map.Entry<ItemKey, Integer> e : n.recipe.getOutputs().entrySet()) {
                    if (!e.getKey().equals(target)) by.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
            long leaves = 0;
            for (double v : needs.values()) leaves += (long) Math.ceil(v - 1e-9);
            Map<ItemKey, Recipe> tree = new LinkedHashMap<>();
            collect(target, tree);

            Set<ItemKey> used = new LinkedHashSet<>();
            for (Recipe r : tree.values()) {
                ItemKey m = r.getMachine();
                if (m != null && !freeMachine(m)) used.add(m);
            }
            return new Plan(target, true, null, cost, outQty, n.depth, leaves,
                    needs, by, n.recipe, null, tree, used);
        }

        private void collect(ItemKey x, Map<ItemKey, Recipe> tree) {
            Node n = memo.get(x);
            if (n == null || !n.feasible || n.recipe == null) return;
            if (tree.putIfAbsent(x, n.recipe) != null) return;
            for (ItemKey in : n.recipe.getInputs().keySet()) collect(in, tree);
        }

        private Node solve(ItemKey x) {
            Node cached = memo.get(x);
            if (cached != null) return cached;
            double p = engine.priceOf(x);
            if (!(p > 0)) return memo(x, Node.fail("缺少有价材料：" + name(x), x));
            if (p <= cap) return memo(x, Node.leaf(p));
            if (!stack.add(x)) return Node.fail("配方环", x);

            Node best = null;
            ItemKey missingMachine = null;
            for (Recipe r : registry.producersOf(x)) {
                if (r.isSelfLoop()) continue;
                if (!allowed(r.getMachine())) {
                    if (missingMachine == null) missingMachine = r.getMachine();
                    continue;
                }
                Node cand = build(r, x, p);
                if (cand != null && (best == null || cand.unitCost < best.unitCost)) best = cand;
            }
            stack.remove(x);
            if (best == null) {
                best = Node.fail(missingMachine != null
                        ? "缺少机器：" + name(missingMachine)
                        : "没有可用配方：" + name(x), missingMachine != null ? missingMachine : x);
            }
            return memo(x, best);
        }

        private Node build(Recipe r, ItemKey out, double price) {
            double total = 0;
            int depth = 0;
            double leaves = 0;
            Map<ItemKey, Double> needs = new HashMap<>(8);

            for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
                ItemKey in = e.getKey();
                int qty = Math.max(1, e.getValue());
                Node n = solve(in);
                if (!n.feasible) return null;
                total += n.unitCost * qty;
                if (n.depth > depth) depth = n.depth;
                leaves += n.leaves * qty;
                for (Map.Entry<ItemKey, Double> le : n.leafNeeds.entrySet()) {
                    needs.merge(le.getKey(), le.getValue() * qty, Double::sum);
                }
            }

            int outQty = Math.max(1, r.getOutputs().getOrDefault(out, 1));
            double unit = total * (1 + FEE) / outQty;
            if (unit < price) unit = price;         // 每个节点都不低于标价
            depth += 1;
            leaves /= outQty;
            for (Map.Entry<ItemKey, Double> e : needs.entrySet()) {
                e.setValue(e.getValue() / outQty);
            }
            if (depth > MAX_DEPTH) return null;
            if (leaves > MAX_LEAVES) return null;
            return new Node(true, unit, depth, leaves, r, needs, null, null);
        }

        private boolean allowed(ItemKey machine) {
            if (machine == null || machine.isVanilla()) return true;
            String id = machine.getSfId();
            if (id != null && id.startsWith("RT_")) return true;   // 虚拟/多方块：随手可得
            return machines.contains(machine);
        }

        private Node memo(ItemKey x, Node n) {
            memo.put(x, n);
            return n;
        }
    }

    private static final class Node {
        final boolean feasible;
        final double unitCost;
        final int depth;
        final double leaves;
        final Recipe recipe;
        final Map<ItemKey, Double> leafNeeds;
        final String reason;
        final ItemKey blocker;

        Node(boolean feasible, double unitCost, int depth, double leaves, Recipe recipe,
             Map<ItemKey, Double> leafNeeds, String reason, ItemKey blocker) {
            this.feasible = feasible;
            this.unitCost = unitCost;
            this.depth = depth;
            this.leaves = leaves;
            this.recipe = recipe;
            this.leafNeeds = leafNeeds;
            this.reason = reason;
            this.blocker = blocker;
        }

        static Node leaf(double price) {
            return new Node(true, price, 0, 1, null, Map.of(), null, null);
        }

        static Node fail(String reason, ItemKey blocker) {
            return new Node(false, 0, 0, 0, null, Map.of(), reason, blocker);
        }
    }

    /** 原版方块 / RT_ 虚拟机器：不需要玩家装 */
    public static boolean freeMachine(ItemKey machine) {
        if (machine == null || machine.isVanilla()) return true;
        String id = machine.getSfId();
        return id != null && id.startsWith("RT_");
    }

    private static String name(ItemKey k) {
        if (k == null) return "?";
        try {
            return k.getDisplayName();
        } catch (Throwable t) {
            return k.toString();
        }
    }
}
