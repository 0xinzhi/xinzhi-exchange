package net.me.xinzhi.exchange.core;

import org.bukkit.inventory.ItemStack;

/**
 * 卖入前的「物品是否原样」校验（腐竹 2026-10-18：「不允许卖改过 lore 或名称等的物品」）。
 *
 * <p>只比「物品本身」，不比数量：拿 {@link ItemKey#toTemplate()} 的模板做基准，
 * {@link ItemStack#isSimilar(ItemStack)} 会把名称、Lore、附魔、耐久、自定义模型数据、
 * 持久化数据等 ItemMeta 一起比 —— 改过任何一项都不相似。</p>
 *
 * <p>⚠ 为什么不用「只比名称和 Lore」：只改附魔 / 只改 PDC 同样能造出假物品；
 * 而本附属按 {@link ItemKey}（材质或粘液 id）定价，不看这些字段，所以必须在入口挡掉。
 * 代价是「用过的耐久工具」也会被拒 —— 这是宁可严格，不把改过的物品按原价收进来。</p>
 */
public final class ItemGuard {

    private ItemGuard() {
    }

    /**
     * 这个堆叠是否仍是该 key 的标准模板（数量不参与比较）。
     *
     * <p>⚠ 2026-10-30 提速：原来每次都要 {@code stack.clone()} + {@code toTemplate()}
     * （又 clone 一次模板）再 {@code setAmount(1)}，纯属重复分配 ——
     * {@link ItemStack#isSimilar(ItemStack)} 本来就不比数量，所以这里直接用模板的只读引用
     * {@link ItemKey#templateRef()} + 原堆叠比较，语义一模一样，但不再造两个临时对象。
     */
    public static boolean pristine(ItemKey key, ItemStack stack) {
        if (key == null || stack == null || stack.getType().isAir()) return false;
        ItemStack template = key.templateRef();
        if (template == null) return false;
        try {
            return stack.getType() == template.getType() && stack.isSimilar(template);
        } catch (Throwable t) {
            // 某些自定义 ItemMeta 序列化会抛：宁可拒收，也不放一个可能被改过的物品进来
            return false;
        }
    }
}
