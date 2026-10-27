package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.XinZhiExchange;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 等价终端的「显示上限」（腐竹 2026-10-26）。
 *
 * <p>只限制<b>显示 / 买</b>：单价高于上限的物品不进清单、也不能被换出来；
 * 卖出路径完全不看这个值。状态存在物品自己的 PDC 里 —— 每台终端各自升级，
 * 丢给别人也带着它自己的上限。
 */
public final class TradeCap {

    /** 不限 */
    public static final long UNLIMITED = Long.MAX_VALUE;

    private static NamespacedKey key;

    private static NamespacedKey key() {
        if (key == null) {
            key = new NamespacedKey(XinZhiExchange.get(), "trade-cap");
        }
        return key;
    }

    /** 物品当前上限；没存过就用道具自带的默认值 */
    public static long capOf(ItemStack item, long def) {
        if (item == null || item.getType().isAir()) return def;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return def;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        try {
            Long v = pdc.get(key(), PersistentDataType.LONG);
            return v == null || v <= 0 ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 写入上限（同时刷新 Lore 里的那行说明由调用方负责） */
    public static void setCap(ItemStack item, long cap) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(key(), PersistentDataType.LONG, cap);
        item.setItemMeta(meta);
    }

    public static boolean blocks(long cap, double price) {
        return cap != UNLIMITED && price > cap;
    }

    public static String describe(long cap) {
        return cap == UNLIMITED ? "不解锁（不限）" : "§e" + (long) cap + " §7EMC";
    }
}
