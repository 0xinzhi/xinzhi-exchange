package net.me.xinzhi.exchange.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/** 文本 / 数字格式。 */
public final class Text {

    /** 通用数字：最多 4 位小数（界面、命令用） */
    private static final DecimalFormat FMT = new DecimalFormat("#,##0.####");
    /** 定价道具用：固定两位小数（腐竹 2026-10-18：「emc 定价道具精度是两位小数」） */
    private static final DecimalFormat FMT2 = new DecimalFormat("#,##0.00");

    private Text() {}

    /** 价格 / 数量：最多 4 位小数，千分位；NaN / Inf 显式写出来 */
    public static String num(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+inf" : "-inf";
        synchronized (FMT) { return FMT.format(v); }
    }

    /** 定价道具显示用：固定两位小数（12.5 → 12.50） */
    public static String money2(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+inf" : "-inf";
        synchronized (FMT2) { return FMT2.format(v); }
    }

    /**
     * 四舍五入到两位小数。
     *
     * <p>正数最小 0.01：取整不能把「有价」变成 0，否则物品会变成
     * 「界面说有价、卖出去给 0 价值」这种更糟的状态。
     */
    public static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        double r = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
        if (r == 0 && v > 0) return 0.01;
        return r;
    }

    /** 去掉颜色符号 */
    public static String plain(String s) {
        return s == null ? "" : s.replaceAll("(?i)§[0-9a-fk-or]", "");
    }
}
