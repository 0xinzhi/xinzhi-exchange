package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.config.Config;
import net.me.xinzhi.exchange.core.ItemGuard;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.price.PriceEngine;
import net.me.xinzhi.exchange.recipe.Recipe;
import net.me.xinzhi.exchange.recipe.RecipeRegistry;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交易层：物品 ⇄ 价值。
 *
 * <p>默认买卖同价（只有手续费 {@code price.tax} 的差）；第八轮起「压缩物品封顶 / 生成器产物」
 * 只压卖价：买价走 {@code priceOf}，卖价走 {@code sellPriceOf}，卖价永远 ≤ 买价。
 * 主附属把"买入取最高 / 卖出取最低"两套方向删掉过一轮，最后也收敛到同价 ——
 * 本附属直接就这么做，少一套价表就少一类对不上的数。
 *
 * <p>背包满时<b>不换出</b>（先扣空间的账、再动余额），因为本附属没有万象库可以兜底。
 * 宁可提示"空间不足"，也不把物品掉在地上。
 */
public final class ExchangeManager {

    private final JavaPlugin plugin;
    private final Config config;
    private final PriceEngine engine;
    private final BalanceStore balances;
    private final RecipeRegistry recipes;

    private volatile List<ItemKey> catalog = List.of();
    /** 兑换上限（可用 /xzex limit 在运行期改，不必重启；0 = 不限） */
    private volatile double tradeMax;

    public ExchangeManager(JavaPlugin plugin, Config config, PriceEngine engine,
                           BalanceStore balances, RecipeRegistry recipes) {
        this.plugin = plugin;
        this.config = config;
        this.engine = engine;
        this.balances = balances;
        this.recipes = recipes;
        this.tradeMax = config.tradeMax;
    }

    public double tradeMax() { return tradeMax; }

    public void setTradeMax(double v) { this.tradeMax = Math.max(0.0, v); }

    public PriceEngine engine() { return engine; }
    public BalanceStore balances() { return balances; }
    public RecipeRegistry recipes() { return recipes; }
    public Config config() { return config; }

    /** 可兑换清单（有价的物品，按名字排序） */
    public List<ItemKey> catalog() { return catalog; }

    public void refreshCatalog() {
        List<ItemKey> list = new ArrayList<>(engine.pricedCount());
        for (ItemKey k : engine.pricedKeys()) {
            if (!config.allowUnpriced && !engine.hasPrice(k)) continue;
            if (overLimit(k)) continue;                // 超过兑换上限 = 不进清单（不能交易）
            if (k.toTemplate() == null) continue;      // 物品已不存在（附属被卸了）
            list.add(k);
        }
        list.sort(Comparator.comparing(ItemKey::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ItemKey::toString));
        this.catalog = List.copyOf(list);
    }

    // ============================================================
    // 价格
    // ============================================================

    /** 换入单价（玩家交出 1 个 → 得到多少价值）；卖价可能被压缩封顶/生成器规则压低 */
    public double sellUnit(ItemKey key) {
        if (overLimit(key)) return 0;
        double v = engine.sellPriceOf(key);
        if (!(v > 0)) return 0;
        return v * (1 - config.tax);
    }

    /** 换出单价（玩家付出多少价值 → 拿到 1 个） */
    public double buyUnit(ItemKey key) {
        if (overLimit(key)) return 0;
        double v = engine.priceOf(key);
        if (!(v > 0)) return 0;
        return v * (1 + config.tax);
    }

    /**
     * 单价是否超过兑换上限 {@code price.trade-max}（腐竹 2026-10-19：高于上限的物品一律不准交易）。
     * 上限取「EMC 值」= 换出价（买价），与手续费无关；0 / 负数 = 不限。
     */
    public boolean overLimit(ItemKey key) {
        if (tradeMax <= 0 || key == null) return false;
        double v = engine.priceOf(key);
        return v > tradeMax;
    }

    /** 既要有价、又没超过兑换上限 */
    public boolean tradeable(ItemKey key) {
        return canExchange(key) && !overLimit(key);
    }

    /** 超过上限的提示（买家/卖家都用这一句） */
    public String limitMessage(ItemKey key) {
        return "该物品价值 " + Text.num(engine.priceOf(key))
                + " 超过兑换上限 " + Text.num(tradeMax) + "，已禁止交易";
    }

    /** 当前被上限拦住的物品数（/xzex info 用） */
    public int overLimitCount() {
        if (tradeMax <= 0) return 0;
        int n = 0;
        for (ItemKey k : engine.pricedKeys()) if (overLimit(k)) n++;
        return n;
    }

    public boolean canExchange(ItemKey key) {
        double v = engine.priceOf(key);
        return v > 0 && !Double.isNaN(v) && !Double.isInfinite(v);
    }

    // ============================================================
    // 余额
    // ============================================================

    public double balance(UUID id) { return balances.get(id); }

    public void setBalance(UUID id, double v) { balances.set(id, v); }

    public void addBalance(UUID id, double delta) { balances.add(id, delta); }

    // ============================================================
    // 换出：价值 → 物品
    // ============================================================

    public ExchangeResult buy(Player player, ItemKey key, long amount) {
        if (player == null || key == null) return ExchangeResult.fail("参数错误");
        if (amount <= 0) return ExchangeResult.fail("数量必须大于 0");
        if (amount > config.maxPerTransaction) {
            return ExchangeResult.fail("单笔上限 " + config.maxPerTransaction + " 个");
        }
        if (overLimit(key)) return ExchangeResult.fail(limitMessage(key));
        double unit = buyUnit(key);
        if (!(unit > 0)) {
            return ExchangeResult.fail("该物品没有价格（材料链算不通，也还没有手工价）");
        }
        ItemStack proto = key.toTemplate();
        if (proto == null) return ExchangeResult.fail("无法生成该物品（附属可能已被卸载）");

        // ★ 先算空间，再扣钱 —— 不允许"钱扣了东西掉地上"
        int space = freeSpace(player, proto);
        if (space < amount) {
            return ExchangeResult.fail("背包空间不足（还能放下 " + space + " 个）");
        }
        double total = unit * amount;
        double bal = balance(player.getUniqueId());
        if (bal < total) {
            return ExchangeResult.fail("价值不足（需要 " + Text.num(total)
                    + "，现有 " + Text.num(bal) + "）");
        }
        setBalance(player.getUniqueId(), bal - total);

        int max = Math.max(1, proto.getMaxStackSize());
        long left = amount;
        while (left > 0) {
            int give = (int) Math.min(max, left);
            ItemStack stack = proto.clone();
            stack.setAmount(give);
            player.getInventory().addItem(stack);
            left -= give;
        }
        player.updateInventory();

        return ExchangeResult.ok(amount, unit, total);
    }

    // ============================================================
    // 换入：物品 → 价值
    // ============================================================

    /** 点哪个槽位就卖哪个槽位（交接书 D §56.5 的语义） */
    public ExchangeResult sellFromSlot(Player player, Inventory inv, int slot, long amount) {
        if (player == null || inv == null) return ExchangeResult.fail("参数错误");
        ItemStack current = inv.getItem(slot);
        if (current == null || current.getType().isAir()) return ExchangeResult.fail("那一格是空的");
        if (amount <= 0) return ExchangeResult.fail("数量必须大于 0");

        ItemKey key = ItemKey.of(current);
        if (key == null) return ExchangeResult.fail("无法识别该物品");
        if (config.rejectModified && !ItemGuard.pristine(key, current)) {
            return ExchangeResult.fail("这个物品被改过（名称 / Lore / 附魔 / 耐久等），不收");
        }

        if (overLimit(key)) return ExchangeResult.fail(limitMessage(key));
        double unit = sellUnit(key);
        if (!(unit > 0)) return ExchangeResult.fail("该物品没有价格，物品不会被扣除");

        long take = Math.min(amount, current.getAmount());
        if (take > config.maxPerTransaction) take = config.maxPerTransaction;
        if (take <= 0) return ExchangeResult.fail("数量为 0");

        if (take == current.getAmount()) inv.setItem(slot, null);
        else {
            current.setAmount(current.getAmount() - (int) take);
            inv.setItem(slot, current);
        }
        player.updateInventory();

        double total = unit * take;
        addBalance(player.getUniqueId(), total);
        return ExchangeResult.ok(take, unit, total);
    }

    /** 卖掉光标上的那一叠 */
    public ExchangeResult sellCursor(Player player, long amount) {
        if (player == null) return ExchangeResult.fail("参数错误");
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return ExchangeResult.fail("请把要换入的物品拿在光标上");
        }
        ItemKey key = ItemKey.of(cursor);
        if (key == null) return ExchangeResult.fail("无法识别该物品");
        if (config.rejectModified && !ItemGuard.pristine(key, cursor)) {
            return ExchangeResult.fail("这个物品被改过（名称 / Lore / 附魔 / 耐久等），不收");
        }

        if (overLimit(key)) return ExchangeResult.fail(limitMessage(key));
        double unit = sellUnit(key);
        if (!(unit > 0)) return ExchangeResult.fail("该物品没有价格，物品不会被扣除");

        long take = Math.min(amount <= 0 ? cursor.getAmount() : amount, cursor.getAmount());
        if (take > config.maxPerTransaction) take = config.maxPerTransaction;

        if (take >= cursor.getAmount()) player.setItemOnCursor(null);
        else {
            cursor.setAmount(cursor.getAmount() - (int) take);
            player.setItemOnCursor(cursor);
        }
        player.updateInventory();

        double total = unit * take;
        addBalance(player.getUniqueId(), total);
        return ExchangeResult.ok(take, unit, total);
    }

    // ============================================================

    // ============================================================
    // 换出：材料抵扣（腐竹 2026-10-19）
    // ============================================================

    /**
     * 一次「用材料抵扣」的报价：这个物品用<b>定价时选中的那条配方</b>，
     * 玩家背包里有的材料按<b>买价</b>折成价值，剩下的才从余额扣。
     *
     * <p>为什么按买价：物资本身就值这个钱，按卖价折等于让玩家白亏一道手续费，
     * 而按配方成本折出来的总价正好等于物品单价 —— 等价交换的字面意思。
     */
    public static final class MaterialQuote {
        public final long amount;
        public final double unit;
        public final double total;
        /** 每种材料需要多少（已按 amount 放大） */
        public final Map<ItemKey, Integer> need;
        /** 背包里实际能拿出来抵扣的数量 */
        public final Map<ItemKey, Integer> consume;
        /** 交材料能抵掉的价值（= 总价 × 提交比例） */
        public final double offset;
        public final double remainder;
        /** 配方一共要多少件材料 / 玩家实际能交多少件 */
        public final long requiredTotal;
        public final long providedTotal;
        /** 提交比例 0~1（材料交齐 = 1 ⇒ 余额一分不扣） */
        public final double fraction;
        /** 拿出来后能腾出的背包格数（最多能放多少个产物要算它） */
        public final int freedSlots;

        MaterialQuote(long amount, double unit, double total, Map<ItemKey, Integer> need,
                      Map<ItemKey, Integer> consume, long requiredTotal, long providedTotal,
                      double offset, int freedSlots) {
            this.amount = amount;
            this.unit = unit;
            this.total = total;
            this.need = need;
            this.consume = consume;
            this.requiredTotal = requiredTotal;
            this.providedTotal = providedTotal;
            this.fraction = requiredTotal <= 0 ? 0
                    : Math.min(1.0, providedTotal / (double) requiredTotal);
            this.offset = Math.min(total, offset);   // 抵不过总价，多出来的不退
            this.freedSlots = freedSlots;
            this.remainder = Math.max(0, total - this.offset);
        }
    }

    /**
     * 报价：直接拿<b>预览里看到的那条配方</b>（也就是给这个物品定价用的配方）来抵
     * —— 腐竹 2026-10-19：「最终得到的价格不是能预览配方吗？直接按这个配方上有的物品进行抵扣即可」。
     *
     * <p>例：配方 2×B + 3×C，玩家有 4×C + 1×B ⇒ 只扣 1×B + 3×C，余额只付缺的那 1×B 的价。
     * 材料交齐 ⇒ 余额 0；没有配方（纯锚点价）⇒ 不提供材料抵扣。
     */
    public MaterialQuote quoteMaterials(Player p, ItemKey key, long amount) {
        if (p == null || key == null || amount <= 0) return null;
        PriceEngine.Derivation dv = engine.derive(key);
        if (dv == null || dv.recipe == null) return null;
        Recipe r = dv.recipe;
        int out = Math.max(1, r.getOutputs().getOrDefault(key, 1));

        Map<ItemKey, Integer> need = new LinkedHashMap<>();
        long required = 0;
        for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
            ItemKey in = e.getKey();
            if (in == null || in.equals(key)) continue;
            long n = (long) Math.ceil(e.getValue() * (double) amount / out - 1e-9);
            if (n <= 0) continue;
            need.put(in, (int) Math.min(Integer.MAX_VALUE, n));
            required += n;
        }
        if (need.isEmpty() || required <= 0) return null;

        double unit = buyUnit(key);
        if (!(unit > 0)) return null;

        // 背包里各有多少（装材料的格子随便，只要种类对得上）
        Map<ItemKey, Integer> have = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) continue;
            ItemKey k = ItemKey.of(s);
            if (k == null || !need.containsKey(k)) continue;
            have.merge(k, s.getAmount(), Integer::sum);
        }

        // 能吃多少吃多少；吃到的记进 consume，没吃到的按材料价付余额
        Map<ItemKey, Integer> consume = new LinkedHashMap<>();
        long provided = 0;
        double pay = 0;
        for (Map.Entry<ItemKey, Integer> e : need.entrySet()) {
            int want = e.getValue();
            int take = Math.min(want, have.getOrDefault(e.getKey(), 0));
            if (take > 0) {
                consume.put(e.getKey(), take);
                provided += take;
            }
            int miss = want - take;
            if (miss > 0) {
                double mv = engine.priceOf(e.getKey());
                if (mv > 0) pay += miss * mv;
            }
        }
        double total = unit * amount;
        double remainder = Math.min(total, pay);      // 缺料价可能比总价高（锚点价），封顶
        int freed = freedSlots(p, consume);
        return new MaterialQuote(amount, unit, total, need, consume, required, provided,
                total - remainder, freed);
    }

    /**
     * 换出：先用背包里的配方材料抵扣，剩下的扣余额。
     *
     * <p>材料只在<b>确认兑换</b>时一次性扣（不托管、不用完不退），所以不会出现"东西被吞"。
     */
    public ExchangeResult buyWithMaterials(Player p, ItemKey key, long amount) {
        if (p == null || key == null) return ExchangeResult.fail("参数错误");
        if (amount <= 0) return ExchangeResult.fail("数量必须大于 0");
        if (amount > config.maxPerTransaction) {
            return ExchangeResult.fail("单笔上限 " + config.maxPerTransaction + " 个");
        }
        if (overLimit(key)) return ExchangeResult.fail(limitMessage(key));
        MaterialQuote q = quoteMaterials(p, key, amount);
        if (q == null) {
            return ExchangeResult.fail("这个物品没有配方（纯基础价），不能用材料抵扣");
        }
        double bal = balance(p.getUniqueId());
        if (bal < q.remainder) {
            return ExchangeResult.fail("材料抵扣 " + Text.num(q.offset) + " 后还差 "
                    + Text.num(q.remainder) + "，现有 " + Text.num(bal));
        }
        ItemStack proto = key.toTemplate();
        if (proto == null) return ExchangeResult.fail("无法生成该物品（附属可能已被卸载）");

        // ★ 先算空间（含材料腾出来的格子），再动物品和钱
        int space = freeSpace(p, proto) + q.freedSlots;
        if (space < amount) {
            return ExchangeResult.fail("背包空间不足（还能放下 " + space + " 个）");
        }
        consumeMaterials(p, q.consume);
        setBalance(p.getUniqueId(), bal - q.remainder);

        int max = Math.max(1, proto.getMaxStackSize());
        long left = amount;
        while (left > 0) {
            int give = (int) Math.min(max, left);
            ItemStack stack = proto.clone();
            stack.setAmount(give);
            p.getInventory().addItem(stack);
            left -= give;
        }
        p.updateInventory();
        return ExchangeResult.ok(amount, q.unit, q.remainder);
    }

    /** 从背包里扣掉这些材料（按格子顺序，数量不足时能拿多少拿多少） */
    private static void consumeMaterials(Player p, Map<ItemKey, Integer> consume) {
        if (consume.isEmpty()) return;
        for (int i = 0; i < p.getInventory().getStorageContents().length; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s == null || s.getType().isAir()) continue;
            ItemKey k = ItemKey.of(s);
            if (k == null) continue;
            Integer left = consume.get(k);
            if (left == null || left <= 0) continue;
            int take = Math.min(left, s.getAmount());
            int rest = s.getAmount() - take;
            if (rest <= 0) p.getInventory().setItem(i, null);
            else {
                s.setAmount(rest);
                p.getInventory().setItem(i, s);
            }
            consume.put(k, left - take);
        }
    }

    /** 这些材料拿走后能空出几个格子（用来判断换出后背包够不够） */
    private static int freedSlots(Player p, Map<ItemKey, Integer> consume) {
        if (consume.isEmpty()) return 0;
        Map<ItemKey, Integer> left = new LinkedHashMap<>(consume);
        int freed = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) continue;
            ItemKey k = ItemKey.of(s);
            if (k == null) continue;
            Integer l = left.get(k);
            if (l == null || l <= 0) continue;
            if (s.getAmount() <= l) freed++;
            left.put(k, l - s.getAmount());
        }
        return freed;
    }

    /** 背包还能放下多少个该物品 */
    public static int freeSpace(Player player, ItemStack proto) {
        int max = Math.max(1, proto.getMaxStackSize());
        int free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.getType() == Material.AIR) free += max;
            else if (ItemKey.of(s) != null && ItemKey.of(s).equals(ItemKey.of(proto))) {
                free += Math.max(0, max - s.getAmount());
            }
        }
        return free;
    }
}
