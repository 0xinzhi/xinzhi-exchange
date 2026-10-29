package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
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

/** 两个管理面板的点击处理（价格定制 / 批量定价 / 筛选）。 */
public final class PricingAdminListener implements Listener {

    private final PricingAdminGui gui;

    public PricingAdminListener(PricingAdminGui gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof PricingAdminGui.Holder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0) { e.setCancelled(true); return; }
        if (raw >= top.getSize()) {
            // 背包：Shift+左键快速加进批量清单
            if (h.kind == PricingAdminGui.Kind.BATCH && e.getClick().isShiftClick() && e.isLeftClick()) {
                e.setCancelled(true);
                ItemStack it = p.getInventory().getItem(e.getSlot());
                if (it == null || it.getType().isAir()) return;
                ItemKey key = ItemKey.of(it);
                if (key == null) return;
                gui.session(p).putIfAbsent(key, null);
                gui.openBatch(p, h.page);
            } else if (e.getClick().isShiftClick()) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);

        if (h.kind == PricingAdminGui.Kind.CUSTOM) handleCustom(p, h, raw, e);
        else if (h.kind == PricingAdminGui.Kind.BATCH) handleBatch(p, h, raw, e);
        else handleFilter(p, raw);
    }

    private void handleCustom(Player p, PricingAdminGui.Holder h, int raw, InventoryClickEvent e) {
        switch (raw) {
            case PricingAdminGui.C_CLOSE -> p.closeInventory();
            case PricingAdminGui.C_PREV -> gui.openCustom(p, h.page - 1);
            case PricingAdminGui.C_NEXT -> gui.openCustom(p, h.page + 1);
            case PricingAdminGui.C_ADD -> gui.addHeld(p, false, h.page);
            case PricingAdminGui.C_EXPORT -> gui.exportCustom(p);
            case PricingAdminGui.C_INFO -> { }
            default -> {
                ItemKey key = gui.customKeyAt(h.page, raw);
                if (key == null) return;
                if (e.isRightClick()) gui.removeOne(p, key, false, h.page);
                else gui.setOne(p, key, false, h.page);
            }
        }
    }

    private void handleBatch(Player p, PricingAdminGui.Holder h, int raw, InventoryClickEvent e) {
        switch (raw) {
            case PricingAdminGui.B_PREV -> gui.openBatch(p, h.page - 1);
            case PricingAdminGui.B_NEXT -> gui.openBatch(p, h.page + 1);
            case PricingAdminGui.B_FILTER -> gui.openFilter(p);
            case PricingAdminGui.B_SORT -> gui.toggleSort(p);
            case PricingAdminGui.B_SCAN -> gui.scanInventory(p, h.page);
            case PricingAdminGui.B_ADD -> gui.addHeld(p, true, h.page);
            case PricingAdminGui.B_UNIFORM -> gui.uniform(p, h.page);
            case PricingAdminGui.B_SAVE -> gui.saveBatch(p, h.page);
            case PricingAdminGui.B_CLEAR -> {
                gui.session(p).clear();
                gui.openBatch(p, h.page);
            }
            case PricingAdminGui.B_EXPORT -> gui.exportBatch(p);
            case PricingAdminGui.B_INFO -> { }
            default -> {
                ItemKey key = gui.batchKeyAt(p, h.page, raw);
                if (key == null) return;
                if (e.isRightClick()) gui.removeOne(p, key, true, h.page);
                else gui.setOne(p, key, true, h.page);
            }
        }
    }

    private void handleFilter(Player p, int raw) {
        switch (raw) {
            case PricingAdminGui.F_UNPRICED -> gui.filter(p, 0);
            case PricingAdminGui.F_NO_ANCHOR -> gui.filter(p, 1);
            case PricingAdminGui.F_VANILLA -> gui.filter(p, 2);
            case PricingAdminGui.F_SLIMEFUN -> gui.filter(p, 3);
            case PricingAdminGui.F_NORECIPE -> gui.filter(p, 4);
            case PricingAdminGui.F_BACK -> gui.openBatch(p, 0);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof PricingAdminGui.Holder)) return;
        for (int slot : e.getRawSlots()) {
            if (slot < e.getView().getTopInventory().getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
