package net.me.xinzhi.exchange.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.exchange.TerminalItem;
import net.me.xinzhi.exchange.exchange.TradeCap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 机器公用的小工具：主人登记、单价缓存、数字解析。
 *
 * <p>「把 EMC 值写入机器」就是这里的 {@link #unit} 缓存：每台机器在
 * BlockStorage 里存一张「物品 → 单价」表 + 价表版本号。价表重建（重扫 / 改上限 /
 * 重新读 prices.yml）后版本号变了，旧缓存自动作废重查。
 */
public final class MachineUtil {

    /** 机器手续费：买卖都 1%（腐竹 2026-10-19） */
    public static final double FEE = 0.01;

    private static final String KEY_OWNER = "xz-owner";
    private static final String KEY_VERSION = "xz-pv";
    private static final String KEY_CACHE = "xz-pc";
    private static final int CACHE_MAX = 200;

    private MachineUtil() { }

    // ---------------- BlockStorage 读写 ----------------

    public static String info(Location loc, String key) {
        try {
            return BlockStorage.getLocationInfo(loc, key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void set(Location loc, String key, String value) {
        try {
            BlockStorage.addBlockInfo(loc, key, value);
        } catch (Throwable ignored) {
        }
    }

    public static int intInfo(Location loc, String key, int def) {
        String s = info(loc, key);
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static double doubleInfo(Location loc, String key, double def) {
        String s = info(loc, key);
        if (s == null) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static long longInfo(Location loc, String key, long def) {
        String s = info(loc, key);
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ---------------- 主人 ----------------

    public static UUID owner(Location loc) {
        String s = info(loc, KEY_OWNER);
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setOwner(Location loc, Player p) {
        if (p == null || loc == null) return;
        set(loc, KEY_OWNER, p.getUniqueId().toString());
    }

    /** 放置者没登记过（老机器 / 别人放的）时，用第一个打开的人当主人 */
    public static UUID ownerOrClaim(Location loc, Player p) {
        UUID id = owner(loc);
        if (id == null && p != null) {
            setOwner(loc, p);
            id = p.getUniqueId();
        }
        return id;
    }

    public static Player onlineOwner(Location loc) {
        UUID id = owner(loc);
        if (id == null) return null;
        Player p = Bukkit.getPlayer(id);
        return p != null && p.isOnline() ? p : null;
    }

    // ---------------- 等价道具上限槽 ----------------

    /** 槽里没有等价道具（或放错东西）时返回这个哨兵值 */
    public static final long CAP_MISSING = Long.MIN_VALUE;

    /**
     * 读某个「等价道具槽」的显示上限。
     *
     * <p>腐竹 2026-10-26 m00819：「不放等价道具就是无法使用的意思」⇒ 没放 / 放的不是等价
     * 道具，一律返回 {@link #CAP_MISSING}，机器待机。m01476 把这条推广到合成台和运输机：
     * 只要涉及「用 EMC 买东西」，就必须先有等价道具，否则玩家能靠机器绕过显示上限。
     */
    public static long capOf(BlockMenu menu, int slot) {
        try {
            ItemStack it = menu == null ? null : menu.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) return CAP_MISSING;
            // 兜底：老道具 / 刚做出来还没右键过的终端，PDC 里没有上限，按道具自带默认值算
            long def = CAP_MISSING;
            SlimefunItem sf = SlimefunItem.getByItem(it);
            if (sf instanceof TerminalItem ti) def = ti.defaultCap();
            if (def == CAP_MISSING) return CAP_MISSING;
            long cap = TradeCap.capOf(it, def);
            return cap > 0 ? cap : CAP_MISSING;
        } catch (Throwable t) {
            return CAP_MISSING;
        }
    }

    /** 这个价格能不能买（上限槽没道具 = 一律不能买） */
    public static boolean buyAllowed(long cap, double price) {
        if (cap == CAP_MISSING) return false;
        return !TradeCap.blocks(cap, price);
    }

    // ---------------- 单价（写入机器缓存） ----------------

    public static double sellUnit(Location loc, ItemKey k) {
        return unit(loc, k, true);
    }

    public static double buyUnit(Location loc, ItemKey k) {
        return unit(loc, k, false);
    }

    private static double unit(Location loc, ItemKey k, boolean sell) {
        ExchangeService svc = ExchangeService.get();
        if (svc == null || k == null) return 0;
        String version = svc.priceVersionTag();
        Map<String, Double> cache;
        if (version.equals(info(loc, KEY_VERSION))) {
            cache = parse(info(loc, KEY_CACHE));
        } else {
            cache = new LinkedHashMap<>();
            set(loc, KEY_VERSION, version);
        }
        String key = (sell ? "s|" : "b|") + k;
        Double v = cache.get(key);
        if (v == null) {
            v = sell ? svc.manager().sellUnit(k) : svc.manager().buyUnit(k);
            if (cache.size() < CACHE_MAX) {
                cache.put(key, v);
                set(loc, KEY_CACHE, dump(cache));
            }
        }
        return v;
    }

    private static Map<String, Double> parse(String raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(";")) {
            int i = part.lastIndexOf('|');
            if (i <= 0) continue;
            try {
                out.put(part.substring(0, i), Double.parseDouble(part.substring(i + 1)));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String dump(Map<String, Double> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('|').append(e.getValue());
        }
        return sb.toString();
    }
}
