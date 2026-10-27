package net.me.xinzhi.exchange.api;

import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.Material;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 附属内嵌 EMC（价值）注册表 —— 与主附属 {@code xinzhitech.api.EmcRegistry} 同形。
 *
 * <h2>给第三方附属用</h2>
 * <p>在自己的 {@code onEnable} 里调用，独立版会把值当成「默认锚点」：
 * <pre>{@code
 * EmcRegistry.register("MY_ITEM", 5000.0);
 * EmcRegistry.registerVanilla(Material.DIAMOND, 3000.0);
 * EmcRegistry.registerAll(Map.of(
 *         ItemKey.slimefun("MY_INGOT"), 120.0,
 *         ItemKey.slimefun("MY_MACHINE"), 8000.0));
 * }</pre>
 *
 * <p><b>优先级</b>：服主手工价（{@code manual:} / {@code /xzex set}）&gt;
 * {@code prices.yml} 的 {@code vanilla/slimefun} 段 &gt; <b>本注册表</b> &gt; 内置表 &gt; 配方推导。
 * 也就是说附属给的是默认值，服主随时能覆盖；覆盖不会写回附属。
 *
 * <p><b>加载顺序无关</b>：静态表，附属先于/晚于独立版 enable 都行；
 * 独立版运行中注册会触发防抖重建（1 秒合并一次）。
 *
 * <p><b>线程安全</b>；值必须 &gt; 0（0 / 负数 / NaN = 忽略，等于「没价」）。
 *
 * <p><b>多附属冲突</b>：同一个物品被两个及以上附属注册不同值时 <b>价高者胜</b>
 * （腐竹 2026-10-19「价高者胜吧」）—— 只会往高的方向改，先注册的高价不会被后注册的低价盖掉。
 */
public final class EmcRegistry {

    /** 注册事件监听器（价表重建用） */
    public interface ChangeListener {
        void onEmcRegistered(Map<ItemKey, Double> added);
    }

    private static final Map<ItemKey, Double> EMBEDDED = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ChangeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private EmcRegistry() {}

    // ============================================================
    // 注册
    // ============================================================

    /** 注册 Slimefun 物品（id 不带 {@code SF:} 前缀） */
    public static void register(String sfId, double value) {
        if (sfId == null || sfId.isBlank() || !valid(value)) return;
        register(ItemKey.slimefun(sfId), value);
    }

    /** 注册原版物品 */
    public static void registerVanilla(Material mat, double value) {
        if (mat == null || !valid(value)) return;
        register(ItemKey.vanilla(mat), value);
    }

    /** 通用注册（key 可为 Slimefun / 原版） */
    public static void register(ItemKey key, double value) {
        if (key == null || !valid(value)) return;
        Double prev = EMBEDDED.merge(key, value, Math::max);   // 价高者胜
        if (prev == null || prev < value) notifyListeners(Map.of(key, value));
    }

    /** 批量注册（null / 非法值跳过；只通知一次） */
    public static void registerAll(Map<ItemKey, Double> map) {
        if (map == null || map.isEmpty()) return;
        Map<ItemKey, Double> added = new ConcurrentHashMap<>();
        for (Map.Entry<ItemKey, Double> e : map.entrySet()) {
            ItemKey k = e.getKey();
            Double v = e.getValue();
            if (k == null || v == null || !valid(v)) continue;
            Double prev = EMBEDDED.merge(k, v, Math::max);      // 价高者胜
            if (prev == null || prev < v) added.put(k, v);
        }
        if (!added.isEmpty()) notifyListeners(added);
    }

    /** 移除（一般不用；附属重载时重新注册即可） */
    public static void unregister(ItemKey key) {
        if (key != null) EMBEDDED.remove(key);
    }

    // ============================================================
    // 查询
    // ============================================================

    public static boolean contains(ItemKey key) {
        return key != null && EMBEDDED.containsKey(key);
    }

    /** 未注册返回 -1 */
    public static double get(ItemKey key) {
        if (key == null) return -1;
        Double v = EMBEDDED.get(key);
        return v == null ? -1 : v;
    }

    /** 只读快照 */
    public static Map<ItemKey, Double> all() {
        return Collections.unmodifiableMap(EMBEDDED);
    }

    public static int size() { return EMBEDDED.size(); }

    // ============================================================
    // 监听器
    // ============================================================

    public static void addListener(ChangeListener l) {
        if (l != null) LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(ChangeListener l) {
        if (l != null) LISTENERS.remove(l);
    }

    private static boolean valid(double v) {
        return v > 0 && !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static void notifyListeners(Map<ItemKey, Double> added) {
        for (ChangeListener l : LISTENERS) {
            try {
                l.onEmcRegistered(added);
            } catch (Throwable ignored) {
            }
        }
    }
}
