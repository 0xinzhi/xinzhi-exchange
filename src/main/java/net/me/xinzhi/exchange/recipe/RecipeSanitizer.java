package net.me.xinzhi.exchange.recipe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 配方净化：丢掉「输入与输出有交集」的自环配方。
 *
 * <p>例如 {@code 1 × 圆石 → 666,666 × 圆石}。这类条目要么是放大器的展示、
 * 要么是手动工作台镜像整张合成表时的空转条目，对定价只有害处。
 *
 * <p>只判断自环，<b>不做别的判断</b>：{@code 9 × 铁锭 → 1 × 铁块} 这种
 * 键不同的正常配方一律保留。
 */
public final class RecipeSanitizer {

    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile boolean enabled = true;

    private RecipeSanitizer() {}

    public static void setEnabled(boolean v) { enabled = v; }
    public static long dropped() { return DROPPED.get(); }
    public static void resetCount() { DROPPED.set(0); }

    public static boolean keep(Recipe r) {
        if (r == null) return false;
        if (!enabled) return true;
        if (r.isSelfLoop()) {
            DROPPED.incrementAndGet();
            return false;
        }
        return true;
    }
}
