package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.util.Dialogs;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 兑换界面的点击处理。
 *
 * <h2>规矩</h2>
 * <ol>
 *   <li><b>用 Holder 认领</b>自己的界面（不靠标题字符串）；</li>
 *   <li>上半屏（我们的界面）一律取消 —— 格子是展示用的；</li>
 *   <li>玩家背包区域放行普通拿取，<b>Shift+左键 = 卖掉那一叠</b>；</li>
 *   <li>光标上有物品时点格子 = <b>卖掉光标上那一叠</b>；没价的不卖。</li>
 * </ol>
 */
public final class ExchangeGuiListener implements Listener {

    private final JavaPlugin plugin;
    private final ExchangeManager manager;
    private final ExchangeGui gui;
    private final PlayerViewStore store;

    public ExchangeGuiListener(JavaPlugin plugin, ExchangeManager manager, ExchangeGui gui,
                               PlayerViewStore store) {
        this.plugin = plugin;
        this.manager = manager;
        this.gui = gui;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof ExchangeGui.MainHolder) && !(holder instanceof ExchangeGui.DetailHolder)) {
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0) {                       // 点在外面 / 特殊值
            e.setCancelled(true);
            return;
        }
        if (raw >= top.getSize()) {          // 玩家自己的背包
            if (e.getClick().isShiftClick() && e.isLeftClick()) {
                e.setCancelled(true);
                sellFromBackpack(p, holder, top, e.getSlot());
            } else if (e.getClick().isShiftClick()) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);

        if (holder instanceof ExchangeGui.MainHolder mh) handleMain(p, mh, raw, e);
        else handleDetail(p, (ExchangeGui.DetailHolder) holder, raw, e);
    }

    // ============================================================
    // 背包里 Shift+左键卖出
    // ============================================================

    private void sellFromBackpack(Player p, InventoryHolder holder, Inventory top, int slot) {
        ItemStack stack = p.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) return;
        ItemKey key = ItemKey.of(stack);
        if (key == null) return;
        if (!manager.canExchange(key)) {
            p.sendMessage("§c[等价交换] " + key.getDisplayName() + " §c没有价格，不收");
            return;
        }
        if (manager.overLimit(key)) {
            p.sendMessage("§c[等价交换] " + key.getDisplayName() + " §c" + manager.limitMessage(key));
            return;
        }
        ItemStack held = p.getInventory().getItem(slot);
        if (held == null) return;
        int have = held.getAmount();
        long cap = capOf(holder);
        // 超过这台终端的显示上限：卖出去就买不回来 —— 腐竹 2026-10-27 要二次确认
        if (cap != TradeCap.UNLIMITED && TradeCap.blocks(cap, manager.engine().priceOf(key))) {
            java.util.List<String> body = java.util.List.of(
                    "§7这个物品 §e" + Text.num(manager.engine().priceOf(key)) + " EMC§7，"
                            + "这台终端只显示 §e" + Text.num(cap) + " EMC§7 以下的物品",
                    "§c卖掉以后，你就没法从这个清单里再买回它了。");
            Dialogs.amountConfirm(plugin, p, "§c§l确认卖出（超出显示上限）",
                    "§7" + Text.plain(key.getDisplayName()) + " §8· 背包里 " + have + " 个",
                    body, "§c确认卖出", have, 1, manager.config().quickAmountMax, have,
                    null, n -> doSellFromBackpack(p, holder, top, slot, n));
            return;
        }
        doSellFromBackpack(p, holder, top, slot, have);
    }

    /** 当前界面带的显示上限（主界面看 state.cap，详情页看 backCap） */
    private static long capOf(InventoryHolder holder) {
        if (holder instanceof ExchangeGui.MainHolder mh) return mh.state.cap;
        if (holder instanceof ExchangeGui.DetailHolder dh) return dh.backCap;
        return TradeCap.UNLIMITED;
    }

    private void doSellFromBackpack(Player p, InventoryHolder holder, Inventory top, int slot, long amount) {
        ItemStack stack = p.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) return;
        ItemKey key = ItemKey.of(stack);
        if (key == null) return;
        ExchangeResult r = manager.sellFromSlot(p, p.getInventory(), slot, amount);
        if (!r.ok) {
            p.sendMessage("§c[等价交换] " + r.message);
            return;
        }
        store.markSold(p.getUniqueId(), key);
        p.sendMessage("§a换入 §f" + r.amount + " × " + key.getDisplayName()
                + " §7(+" + Text.num(r.total) + ") §8余额 " + Text.num(manager.balance(p.getUniqueId())));
        refreshBalance(p, holder, top);
    }

    private void refreshBalance(Player p, InventoryHolder holder, Inventory top) {
        if (holder instanceof ExchangeGui.MainHolder mh) {
            top.setItem(mh.navStart + ExchangeGui.NAV_BALANCE, gui.balance(p));
        }
    }

    // ============================================================
    // 主界面
    // ============================================================

    private void handleMain(Player p, ExchangeGui.MainHolder mh, int raw, InventoryClickEvent e) {
        ExchangeGui.MenuState st = mh.state;

        // 物品区
        if (raw < st.grid && raw < mh.visible.size()) {
            ItemKey key = mh.visible.get(raw);
            // ★ 光标上有物品 = 先卖它（腐竹 2026-10-18 第四轮）
            if (sellCursorIfHolding(p, mh, key)) return;
            gui.itemClicked(p, st, key, e.isRightClick() ? 1 : 0, e.getClick().isShiftClick());
            return;
        }

        // 导航栏
        int nav = raw - mh.navStart;
        if (nav >= 0 && nav < ExchangeGui.NAV_SLOTS) {
            gui.navClicked(p, st, nav);
        }
    }

    /**
     * 光标上有物品时，点格子 = 换入（卖掉光标那一叠）。
     * 具体数量交给 {@link ExchangeGui#sellCursor}：超过 1 个会弹「滑条 + 输入框」。
     */
    private boolean sellCursorIfHolding(Player p, ExchangeGui.MainHolder mh, ItemKey clicked) {
        ItemStack cursor = p.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) return false;
        if (ItemKey.of(cursor) == null) return false;
        gui.sellCursor(p, mh.state);
        return true;
    }

    // ============================================================
    // 详情
    // ============================================================

    private void handleDetail(Player p, ExchangeGui.DetailHolder dh, int raw, InventoryClickEvent e) {
        if (raw == ExchangeGui.SLOT_DETAIL_CLOSE) {
            p.closeInventory();
            return;
        }
        if (raw == ExchangeGui.SLOT_DETAIL_MATERIALS) {
            if (TradeCap.blocks(dh.backCap, manager.engine().priceOf(dh.target))) {
                p.sendMessage("§c[等价交换] 这台终端只显示 §e" + Text.num(dh.backCap) + " §cEMC 以下的物品");
                return;
            }
            gui.openMaterialBuy(p, dh.target, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap);
            return;
        }
        if (raw == ExchangeGui.SLOT_DETAIL_FAV) {
            boolean on = gui.store().toggleFav(p.getUniqueId(), dh.target);
            p.sendMessage(on
                    ? "§a已收藏 §f" + dh.target.getDisplayName()
                    : "§7已取消收藏 §f" + dh.target.getDisplayName());
            gui.openDetail(p, dh.target, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap);
            return;
        }
        if (raw == ExchangeGui.SLOT_DETAIL_BACK) {
            gui.open(p, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap);
            return;
        }
        // 详情里点目标物品 = 换出（左键 1 个 / Shift+左键 数量）
        if (raw == ExchangeGui.SLOT_DETAIL_TARGET) {
            ItemKey key = dh.target;
            if (TradeCap.blocks(dh.backCap, manager.engine().priceOf(key))
                    && (e.isLeftClick() || e.isRightClick())) {
                p.sendMessage("§c[等价交换] 这台终端只显示 §e" + Text.num(dh.backCap)
                        + " §cEMC 以下的物品（潜行右键终端可升级上限）");
                return;
            }
            if (e.isLeftClick() && e.getClick().isShiftClick()) {
                Dialogs.amount(plugin, p, "§8✦ 换出数量 ✦",
                        "§7" + Text.plain(key.getDisplayName()) + " §8（单价 "
                                + Text.num(manager.buyUnit(key)) + "）",
                        1, 1, manager.config().quickAmountMax, 0,
                        () -> gui.openDetail(p, key, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap),
                        n -> {
                            ExchangeResult r = manager.buy(p, key, n);
                            p.sendMessage(r.ok
                                    ? "§a换出 §f" + r.amount + " × " + key.getDisplayName()
                                      + " §7(-" + Text.num(r.total) + ")"
                                    : "§c" + r.message);
                            gui.openDetail(p, key, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap);
                        });
            } else if (e.isLeftClick()) {
                ExchangeResult r = manager.buy(p, key, 1);
                if (!r.ok) p.sendMessage("§c[等价交换] " + r.message);
                gui.openDetail(p, key, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap);
            }
            return;
        }
        // 材料格：点材料 → 继续下钻它的材料链
        if (raw >= ExchangeGui.DETAIL_INPUT_START && raw < ExchangeGui.DETAIL_INPUT_START + 9) {
            var d = manager.engine().derive(dh.target);
            if (d == null || d.recipe == null) return;
            int idx = raw - ExchangeGui.DETAIL_INPUT_START;
            int i = 0;
            for (ItemKey k : d.recipe.getInputs().keySet()) {
                if (i++ == idx) {
                    if (k.equals(dh.target)) return;
                    gui.openDetail(p, k, dh.backPage, dh.backSort, dh.backKeyword, dh.backMode, dh.backCap);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof ExchangeGui.MainHolder) && !(holder instanceof ExchangeGui.DetailHolder)) {
            return;
        }
        for (int slot : e.getRawSlots()) {
            if (slot < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
