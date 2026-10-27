
package net.me.xinzhi.exchange.price;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.RandomMobDrop;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

/**
 * 生物掉落物兜底价（腐竹 2026-10-26）。
 *
 * <p>为什么需要：附属注册的生物特殊掉落物既没有合成配方、也没有 EMC 锚点，
 * 按「配方推导」那条链永远算不出价 —— 它们在等价界面里根本不会出现。
 *
 * <p>口径（腐竹 m01261 原话）：
 * 「1，50%以上5emc，20以上20emc，10以上100emc，5以上200emc，5以下500emc。兜底按60计算吧。
 *   2，就是按现在已经有的价先计算，再补这个掉落物的缺口。…… 4，兜底60。」
 * ⇒
 * <ul>
 *   <li>概率 ≥ 50% → 5 EMC</li>
 *   <li>概率 ≥ 20% → 20 EMC</li>
 *   <li>概率 ≥ 10% → 100 EMC</li>
 *   <li>概率 ≥ 5%  → 200 EMC</li>
 *   <li>概率 &lt; 5% → 500 EMC</li>
 *   <li>读不到概率 → {@code unknownPrice}（默认 60）</li>
 * </ul>
 *
 * <p>而且这一层<b>只补缺口</b>：已经由定价表 / 配方推导算出价的物品不受影响。
 *
 * <h3>概率从哪来</h3>
 * <p>粘液只会把「注册进 {@code RecipeType.MOB_DROP}」的物品放进
 * {@link Slimefun#getRegistry()}.getMobDrops()；每个物品的概率看它有没有实现
 * {@link RandomMobDrop} —— {@code getMobDropChance()} 返回 <b>0~100 的整数百分比</b>，
 * 没实现的话粘液本体按「必掉」处理。
 *
 * <p>⚠ 这里<b>不</b>照抄 LogiTech 虚拟杀手的过滤（它只收
 * {@code BasicCircuitBoard} 或 {@code RandomMobDrop}，其它一律丢掉）—— 那正是
 * 腐竹记忆里「虚拟杀手拿不到非原版掉落物」的原因之一。本类把掉落表里的物品全收下。
 *
 * <p>⚠ 还有一大批附属（InfinityExpansion / LiteXpansion / TranscEndence / MomoTech /
 * SlimeAE …）根本不走 MOB_DROP，而是自己监听 {@code EntityDeathEvent}，
 * 概率在它们的代码里、读不到 ⇒ 那一批物品仍然算不出价（除非手工 /xzex set）。
 */
public final class MobDropPrices {

    private MobDropPrices() {}

    /** 一条掉落记录：某生物掉某物品，概率（-1 = 读不到）与按表算出的价。 */
    public record Entry(EntityType entity, ItemKey item, String displayName, int chance, double price) {}

    /** 概率档位（>= 该值生效，从高到低）与对应单价。 */
    private static final int[] TIERS = {50, 20, 10, 5};
    private static final double[] TIER_PRICES = {5.0, 20.0, 100.0, 200.0};
    /** 概率低于最后一档（< 5%）的价。 */
    private static final double BELOW_TIER_PRICE = 500.0;

    /**
     * 按概率算价。
     *
     * @param chance       0~100 的整数百分比；负数 = 读不到
     * @param unknownPrice 读不到概率时用的兜底价（<= 0 = 不给价）
     */
    public static double priceFor(int chance, double unknownPrice) {
        if (chance < 0) return unknownPrice;
        for (int i = 0; i < TIERS.length; i++) {
            if (chance >= TIERS[i]) return TIER_PRICES[i];
        }
        return BELOW_TIER_PRICE;
    }

    /** 扫一遍粘液的掉落注册表；失败只返回已读到的部分。 */
    public static List<Entry> collect(double unknownPrice) {
        List<Entry> out = new ArrayList<>();
        Map<EntityType, Set<ItemStack>> table;
        try {
            table = Slimefun.getRegistry().getMobDrops();
        } catch (Throwable t) {
            return out;
        }
        if (table == null) return out;
        for (Map.Entry<EntityType, Set<ItemStack>> e : table.entrySet()) {
            EntityType type = e.getKey();
            Set<ItemStack> drops = e.getValue();
            if (type == null || drops == null || drops.isEmpty()) continue;
            for (ItemStack stack : drops) {
                if (stack == null || stack.getType().isAir()) continue;
                ItemKey key;
                try {
                    key = ItemKey.of(stack);
                } catch (Throwable ignored) {
                    continue;
                }
                if (key == null) continue;
                int chance = chanceOf(stack);
                double price = priceFor(chance, unknownPrice);
                if (!(price > 0)) continue;
                out.add(new Entry(type, key, key.getDisplayName(), chance, price));
            }
        }
        return out;
    }

    /** 掉落概率：实现了 {@link RandomMobDrop} 才有；没实现 = 读不到（-1）。 */
    private static int chanceOf(ItemStack stack) {
        try {
            SlimefunItem item = SlimefunItem.getByItem(stack);
            if (item instanceof RandomMobDrop rmd) {
                return Math.max(0, Math.min(100, rmd.getMobDropChance()));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 归并成「物品 → 兜底价」。
     *
     * <p>同一个物品可能被多种生物掉落，概率还不一样 —— 取<b>最便宜</b>的那条
     * （最容易拿到的那种生物决定价），否则稀有掉落会把常见掉落物的价抬上去。
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
