package net.me.xinzhi.exchange.util;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * 「让玩家输入点东西」的统一入口 —— 首选原版 Dialog 输入框，老客户端退回聊天栏。
 *
 * <h2>为什么默认走输入框</h2>
 * 腐竹 2026-10-18：「输入尽量使用输入框」。聊天栏输入会先关掉界面、输完还得重开；
 * Dialog 弹窗<b>不会顶掉底下那个箱子界面</b>（交接书 B §17.5），关掉就回去。
 *
 * <p>协议号按 Paper 的 {@code Player#getProtocolVersion()} 判：1.21.6 = 771。
 * ⚠ 每次 MC 大版本升级都要重新核对这个数。
 *
 * <p>两条通道语义不同：Dialog 下底层容器保留、ESC 只是关框、点「取消」触发 onCancel；
 * 聊天栏下必须先 {@code closeInventory()}，输完由调用方自己重开界面。
 */
public final class Dialogs {

    /** 客户端支持原版 Dialog 的最低协议号（1.21.6 = 771） */
    public static final int MIN_PROTOCOL = 771;

    private static final String KEY = "xzex_value";
    private static final String SLIDER_KEY = "xzex_quick";
    private static final int WIDTH = 260;
    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder().uses(16).build();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private Dialogs() {}

    public static boolean canDialog(Player p) {
        if (p == null) return false;
        try {
            return p.getProtocolVersion() >= MIN_PROTOCOL;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================
    // 文本
    // ============================================================

    public static void text(JavaPlugin plugin, Player p, String title, String label,
                            String initial, int maxLength,
                            Runnable onCancel, Consumer<String> onValue) {
        if (!canDialog(p)) {
            ChatInput.request(plugin, p, "§b" + label + " §7（输入 §fcancel §7取消）", onCancel, onValue);
            return;
        }
        show(plugin, p, title, label,
                List.of(io.papermc.paper.registry.data.dialog.input.DialogInput.text(KEY, WIDTH, legacy(label), true,
                        initial == null ? "" : initial, Math.max(1, maxLength), null)),
                onCancel,
                resp -> {
                    String s = resp.getText(KEY);
                    return s == null ? "" : s.trim();
                },
                onValue,
                onValue);
    }

    // ============================================================
    // 整数
    // ============================================================

    public static void number(JavaPlugin plugin, Player p, String title, String label,
                              int initial, int min, int max,
                              Runnable onCancel, Consumer<Integer> onValue) {
        // max <= 0 = 不设上限（腐竹 2026-10-18：「这个输入不需要有限制」）
        boolean bounded = max > 0;
        int init = bounded ? Math.max(min, Math.min(max, initial)) : Math.max(min, initial);
        String hint = label + " §8（" + (bounded ? min + " ~ " + max : "≥ " + min) + "）";

        // ⚠ 不要用 DialogInput.numberRange：它在客户端渲染成一根**滑条**，
        //    精确值根本滑不到（腐竹 2026-10-18：「精确输入要用输入框啊，用滑条我甚至都滑不到100以内」）。
        //    统一走文本框，自己解析；越界只提示不改值，别偷偷夹取。
        text(plugin, p, title, hint, String.valueOf(init), 12, onCancel, raw -> {
            String s = raw == null ? "" : raw.trim();
            if (s.isEmpty()) {
                p.sendMessage("§c请输入整数");
                return;
            }
            int v;
            try {
                v = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                p.sendMessage("§c请输入整数：§f" + s);
                return;
            }
            if (v < min) {
                p.sendMessage("§c不能小于 " + min + "（你输入的是 " + v + "）");
                return;
            }
            if (bounded && v > max) {
                p.sendMessage("§c不能大于 " + max + "（你输入的是 " + v + "）");
                return;
            }
            onValue.accept(v);
        });
    }

    // ============================================================
    // 数量（滑条 + 输入框）
    // ============================================================

    /**
     * 选数量：<b>滑条供快速选择</b>（腐竹 2026-10-18 第七轮：「对于换入，给予上限为128的滑条供快速选择」），
     * 同一张框里再给一个文本框，填了数字就以文本框为准（精确值优先）。
     *
     * <p>⚠ 为什么两个控件都放：滑条只能"大概选"，但腐竹要精确输入时还是要输入框
     * （2026-10-18：「精确输入要用输入框啊，用滑条我甚至都滑不到100以内」）。
     * 文本框初始留空，所以默认值是滑条的初始位置；拉了滑条就按滑条走。
     *
     * @param initial   滑条初始值
     * @param min       下限（≥1）
     * @param sliderMax 滑条上限（配置 {@code gui.quick-amount-max}，默认 128）
     * @param hardMax   实际可用上限（比如手里只有 37 个）；0 = 不另设上限
     */
    public static void amount(JavaPlugin plugin, Player p, String title, String label,
                              long initial, long min, long sliderMax, long hardMax,
                              Runnable onCancel, LongConsumer onValue) {
        amountImpl(plugin, p, title, label, List.of(), "§a确定", 120,
                initial, min, sliderMax, hardMax, onCancel, onValue);
    }

    /**
     * 数量 + 二次确认：同一个框里放「滑条 + 输入框」，底部一个大按钮。
     *
     * <p>腐竹 2026-10-27：「超过上限的物品卖出要弹出输入框类型的大按键来进行二次确认。」
     * 按键宽度用 {@link #WIDTH}（260），比普通框的 120 宽一倍，一眼能看出这是「要不要真卖」。
     *
     * @param extraLines 追加在提示语下面的正文（可以多行，每行一个 § 色码字符串）
     * @param okLabel    大按钮上的字（如「§c确认卖出」）
     */
    public static void amountConfirm(JavaPlugin plugin, Player p, String title, String label,
                                     List<String> extraLines, String okLabel,
                                     long initial, long min, long sliderMax, long hardMax,
                                     Runnable onCancel, LongConsumer onValue) {
        amountImpl(plugin, p, title, label, extraLines, okLabel, WIDTH,
                initial, min, sliderMax, hardMax, onCancel, onValue);
    }

    private static void amountImpl(JavaPlugin plugin, Player p, String title, String label,
                                   List<String> extraLines, String okLabel, int okWidth,
                                   long initial, long min, long sliderMax, long hardMax,
                                   Runnable onCancel, LongConsumer onValue) {
        long lo = Math.max(1L, min);
        long cap = sliderMax > 0 ? Math.max(lo, sliderMax) : lo;
        long hi = hardMax > 0 ? Math.min(hardMax, cap) : cap;
        if (hi < lo) hi = lo;
        long init = Math.min(hi, Math.max(lo, initial));
        String hint = label + " §8（滑条快速选 " + lo + "~" + hi
                + "；输入框填数字则以输入框为准）";
        List<String> body = new java.util.ArrayList<>();
        body.add(hint);
        if (extraLines != null) body.addAll(extraLines);

        LongConsumer apply = n -> {
            if (n < lo) {
                p.sendMessage("§c不能小于 " + lo + "（你填的是 " + n + "）");
                return;
            }
            if (hardMax > 0 && n > hardMax) {
                p.sendMessage("§c最多 " + hardMax + "（你填的是 " + n + "）");
                return;
            }
            onValue.accept(n);
        };

        Consumer<String> byText = raw -> {
            String s = raw == null ? "" : raw.trim().replace(",", "");
            if (s.isEmpty()) {
                p.sendMessage("§c请输入整数");
                return;
            }
            long v;
            try {
                v = Long.parseLong(s);
            } catch (NumberFormatException e) {
                p.sendMessage("§c请输入整数：§f" + s);
                return;
            }
            apply.accept(v);
        };

        if (!canDialog(p)) {
            ChatInput.request(plugin, p, "§b" + hint + " §7（输入 §fcancel §7取消）", onCancel, byText);
            return;
        }

        try {
            io.papermc.paper.registry.data.dialog.input.DialogInput slider =
                    io.papermc.paper.registry.data.dialog.input.DialogInput.numberRange(
                            SLIDER_KEY, 200, legacy("§7快速选择"), "options.generic_value",
                            (float) lo, (float) hi, (float) init, 1f);
            io.papermc.paper.registry.data.dialog.input.DialogInput box =
                    io.papermc.paper.registry.data.dialog.input.DialogInput.text(
                            KEY, WIDTH, legacy("§7精确数量（留空 = 用滑条）"), true,
                            "", 12, null);
            show(plugin, p, title, body, List.of(slider, box), onCancel,
                    resp -> {
                        String s = resp.getText(KEY);
                        if (s != null && !s.isBlank()) {
                            try {
                                return (Long) Long.parseLong(s.trim().replace(",", ""));
                            } catch (NumberFormatException e) {
                                p.sendMessage("§c请输入整数：§f" + s.trim());
                                return null;
                            }
                        }
                        Float f = resp.getFloat(SLIDER_KEY);
                        return f == null ? null : (Long) (long) Math.round(f);
                    },
                    v -> apply.accept(v),
                    byText,
                    okLabel, okWidth);
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().warning("[等价交换] 弹数量框失败，退回聊天栏: " + t);
            }
            ChatInput.request(plugin, p, "§b" + hint + " §7（输入 §fcancel §7取消）", onCancel, byText);
        }
    }

    // ============================================================
    // 价格（两位小数）
    // ============================================================

    /**
     * 填价格：照样是文本框，但按两位小数解析（腐竹 2026-10-18：「精度为两位小数」）。
     *
     * <p>输入 {@code 12.5} 存成 {@code 12.50}，输入 {@code 0.004} 会提示低于 0.01 ——
     * 不偷偷夹取，玩家/管理员看到的数就是他填进去的那个数。
     */
    public static void price(JavaPlugin plugin, Player p, String title, String label,
                             double initial, double min,
                             Runnable onCancel, Consumer<Double> onValue) {
        String init = BigDecimal.valueOf(Text.round2(initial)).toPlainString();
        String hint = label + " §8（两位小数，≥ " + Text.money2(min) + "；填 0 = 免费）";
        text(plugin, p, title, hint, init, 16, onCancel, raw -> {
            String s = raw == null ? "" : raw.trim().replace(",", "");
            if (s.isEmpty()) {
                p.sendMessage("§c请输入价格");
                return;
            }
            double v;
            try {
                v = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                p.sendMessage("§c价格必须是数字：§f" + s);
                return;
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                p.sendMessage("§c价格必须是有限的数字");
                return;
            }
            if (v < min) {
                p.sendMessage("§c价格不能小于 " + Text.money2(min) + "（你输入的是 " + s + "）");
                return;
            }
            double rounded = Text.round2(v);
            if (rounded != v) p.sendMessage("§7已按两位小数取整: §f" + Text.money2(rounded)
                    + " §8（你输入 " + s + "）");
            onValue.accept(rounded);
        });
    }

    // ============================================================

    private static <T> void show(JavaPlugin plugin, Player p, String title, String label,
                                 List<io.papermc.paper.registry.data.dialog.input.DialogInput> inputs,
                                 Runnable onCancel,
                                 Function<DialogResponseView, T> reader,
                                 Consumer<T> onValue,
                                 Consumer<String> chatFallback) {
        show(plugin, p, title, List.of(label), inputs, onCancel, reader, onValue, chatFallback,
                "§a确定", 120);
    }

    private static <T> void show(JavaPlugin plugin, Player p, String title, List<String> body,
                                 List<io.papermc.paper.registry.data.dialog.input.DialogInput> inputs,
                                 Runnable onCancel,
                                 Function<DialogResponseView, T> reader,
                                 Consumer<T> onValue,
                                 Consumer<String> chatFallback,
                                 String okLabel, int okWidth) {
        String flat = String.join(" §7· ", body);
        List<DialogBody> bodyLines = new java.util.ArrayList<>(body.size());
        for (String line : body) bodyLines.add(DialogBody.plainMessage(legacy(line), 300));
        ActionButton ok = ActionButton.create(legacy(okLabel), null, okWidth,
                DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player who)) return;
                    T value = null;
                    try {
                        value = reader.apply(response);
                    } catch (Throwable ignored) {}
                    who.closeDialog();
                    // ⚠ 回调里通常会重开箱子界面。跟 closeDialog 同一 tick 里 openInventory
                    //    会被客户端吞掉（症状：跳过一次页之后再点就没反应）—— 延后一个 tick。
                    if (value != null) {
                        T captured = value;
                        runNext(plugin, () -> onValue.accept(captured));
                    }
                }, OPTIONS));

        ActionButton cancel = ActionButton.create(legacy("§7取消"), null, 120,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player who) who.closeDialog();
                    if (onCancel != null) runNext(plugin, onCancel);
                }, OPTIONS));

        try {
            Dialog dialog = Dialog.create(b -> b.empty()
                    .base(DialogBase.builder(legacy(title))
                            .canCloseWithEscape(true)
                            // ⚠ afterAction=NONE 时 pause 必须显式 false，否则 Paper 直接拒
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.NONE)
                            .body(bodyLines)
                            .inputs(inputs)
                            .build())
                    // columns=1 = 按钮竖着排、各占一整行（大按键才撑得开）
                    .type(DialogType.multiAction(List.of(ok), cancel, 1)));
            p.showDialog(dialog);
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().warning("[等价交换] 弹输入框失败，退回聊天栏: " + t);
            }
            ChatInput.request(plugin, p, "§b" + flat + " §7（输入 §fcancel §7取消）",
                    onCancel, chatFallback);
        }
    }

    /** 关框之后要重开界面：一律推到下一个 tick，避免被客户端吞掉 */
    private static void runNext(JavaPlugin plugin, Runnable r) {
        if (r == null) return;
        if (plugin == null || !plugin.isEnabled()) { r.run(); return; }
        org.bukkit.Bukkit.getScheduler().runTask(plugin, r);
    }

    private static Component legacy(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }
}
