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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    // 旧版把「物品 → 单价」表存在方块数据里（xz-pv 版本号 / xz-pc 整串表），每次查价都要整串重解析。
    // 2026-10-30 起单价改成进程内缓存，这两个键不再读写；老服里残留的数据是无害垃圾。
    // 注意：不能顺手去删它们 —— 删方块数据属于不可逆操作，留着也不影响新逻辑。

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

    /** 昵称缓存：机器每周期都要显示机主名，别每次都去查档案 */
    private static final Map<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * 机主昵称（腐竹 2026-10-30：「需要将机主的名称标出来，注意不是 uid」）。
     *
     * <p>只显示名字：在线直接取；离线查一次档案并缓存；查不到就退回「未知(前 8 位)」，
     * 免得界面上把一整串 UUID 铺出来。
     */
    public static String ownerName(Location loc) {
        return ownerName(owner(loc));
    }

    public static String ownerName(UUID id) {
        if (id == null) return "未登记";
        String cached = NAME_CACHE.get(id);
        if (cached != null) return cached;
        String name = null;
        Player online = Bukkit.getPlayer(id);
        if (online != null && online.isOnline()) {
            name = online.getName();
        } else {
            try {
                OfflinePlayer off = Bukkit.getOfflinePlayer(id);
                if (off != null) name = off.getName();
            } catch (Throwable ignored) {
            }
        }
        if (name == null || name.isBlank()) return "未知(" + id.toString().substring(0, 8) + ")";
        NAME_CACHE.put(id, name);
        return name;
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

    // ---------------- 单价（进程内缓存） ----------------

    /**
     * 单价进程内缓存（腐竹 2026-10-30：「首先要减少查询次数」）。
     *
     * <p>旧实现把整张「物品 → 单价」表按方块存在 BlockStorage（{@code xz-pc}），每查一次价
     * 都要 {@code split(";")} 把整串重解析一遍 —— 45 格就是 45 次全表解析，而且缓存 miss 时
     * 还要整串写回方块数据。现在整张表放内存，用 {@link ExchangeService#priceVersionTag()}
     * 当版本：版本一变整表作废。一次查价 = 一次 hash 查表，也不再往方块数据写任何东西。
     *
     * <p>⚠ 单价是全局的（只跟 {@link ItemKey} 有关，跟放在哪台机器、谁放的无关），
     * 所以可以全服共用一张表。
     */
    private static final Map<String, Double> PRICE_CACHE = new ConcurrentHashMap<>();
    private static volatile String priceVersion;

    private static void ensurePriceVersion(String version) {
        if (version.equals(priceVersion)) return;
        synchronized (MachineUtil.class) {
            if (version.equals(priceVersion)) return;
            PRICE_CACHE.clear();
            priceVersion = version;
        }
    }

    public static double sellUnit(Location loc, ItemKey k) {
        return unit(k, true);
    }

    public static double buyUnit(Location loc, ItemKey k) {
        return unit(k, false);
    }

    /**
     * @param loc 只为保持调用方签名：单价是全局的，不看机器位置。
     */
    private static double unit(ItemKey k, boolean sell) {
        ExchangeService svc = ExchangeService.get();
        if (svc == null || k == null) return 0;
        ensurePriceVersion(svc.priceVersionTag());
        String key = (sell ? "s|" : "b|") + k;
        Double cached = PRICE_CACHE.get(key);
        if (cached != null) return cached;
        double fresh = sell ? svc.manager().sellUnit(k) : svc.manager().buyUnit(k);
        PRICE_CACHE.put(key, fresh);
        return fresh;
    }

    /** 配置重载 / 价表重建后清一次（版本号本来就会变，这里只是双保险） */
    public static void clearPriceCache() {
        PRICE_CACHE.clear();
        priceVersion = null;
    }
}
