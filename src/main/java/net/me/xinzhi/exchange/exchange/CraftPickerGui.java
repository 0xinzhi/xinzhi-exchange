package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.util.GuiItems;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 目标搜索：把「当前机器能造、且超过上限」的物品列出来，点一个回合成台。
 *
 * <p>列表在打开时算一次（一次 DP 会话算完所有目标），翻页只重画。
 *
 * <p>⚠ 本界面是<b>纯展示</b>，列表里的物品是服务端放的展示副本。监听器必须由
 * {@link ExchangeService#enable()} 注册，否则整个界面等于一个普通箱子，
 * 物品能被直接拿走（2026-10-19 实测踩过）。点击、拖拽、双击全部取消。
 */
final class CraftPickerGui implements Listener {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    private static final int PREV = 45, BACK = 48, PAGE = 49, NEXT = 53;

    private final ExchangeService svc;
    private final CraftGui main;

    CraftPickerGui(ExchangeService svc, CraftGui main) {
        this.svc = svc;
        this.main = main;
    }

    void open(Player p, CraftGui.Holder holder, int page) {
        // 全部超上限物品都列出来（不是只列当前机器能造的），
        // 每条都带「需要哪几台机器 / 还缺哪几台」。
        Map<ItemKey, CraftPlanner.Plan> catalog = svc.planner().catalogOverCap(svc.engine().pricedKeys());
        List<ItemKey> keys = new ArrayList<>(catalog.keySet());
        keys.sort((a, b) -> {
            CraftPlanner.Plan pa = catalog.get(a), pb = catalog.get(b);
            boolean fa = pa != null && pa.feasible, fb = pb != null && pb.feasible;
            if (fa != fb) return fa ? -1 : 1;           // 能造的排前面
            return CraftGui.name(a).compareToIgnoreCase(CraftGui.name(b));
        });

        PickerHolder ph = new PickerHolder(holder, keys, catalog, holder.machines);
        int craftable = 0;
        for (CraftPlanner.Plan pl : catalog.values()) if (pl != null && pl.feasible) craftable++;
        Inventory inv = Bukkit.createInventory(ph, SIZE,
                "§8选择目标 §7· §f" + keys.size() + " §7超上限 / §a" + craftable + " §7可造");
        ph.inv = inv;
        int pages = Math.max(1, (keys.size() + PER_PAGE - 1) / PER_PAGE);
        ph.page = Math.max(0, Math.min(page, pages - 1));
        fill(ph);
        p.openInventory(inv);
    }

    private void fill(PickerHolder ph) {
        Inventory inv = ph.inv;
        for (int i = 0; i < SIZE; i++) inv.setItem(i, null);

        int page = ph.page;
        int pages = Math.max(1, (ph.keys.size() + PER_PAGE - 1) / PER_PAGE);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = start + i;
            if (index >= ph.keys.size()) break;
            ItemKey k = ph.keys.get(index);
            CraftPlanner.Plan plan = ph.catalog.get(k);
            inv.setItem(i, icon(k, plan, ph.machines));
        }
        if (ph.keys.isEmpty()) {
            inv.setItem(22, GuiItems.of(Material.BARRIER, "§c没有可造目标",
                    "§7先在第一排放机器物品",
                    "§7只列当前机器能造的超上限物品"));
        }

        if (page > 0) {
            inv.setItem(PREV, GuiItems.of(Material.ARROW, "§e上一页",
                    "§7第 " + page + " / " + pages + " 页"));
        }
        inv.setItem(PAGE, GuiItems.of(Material.PAPER, "§7第 §f" + (page + 1) + " §7/ §f" + pages + " §7页",
                "§7共 §f" + ph.keys.size() + " §7个超上限物品",
                "§7按当前第一排的机器算「还缺几台」"));
        if (page < pages - 1) {
            inv.setItem(NEXT, GuiItems.of(Material.ARROW, "§e下一页",
                    "§7第 " + (page + 2) + " / " + pages + " 页"));
        }
        inv.setItem(BACK, GuiItems.of(Material.OAK_DOOR, "§c返回合成台"));
    }

    private ItemStack icon(ItemKey k, CraftPlanner.Plan plan, java.util.Set<ItemKey> machines) {
        ItemStack it = k.toTemplate();
        if (it == null) it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + CraftGui.name(k));
            List<String> lore = new ArrayList<>();
            lore.add("§7标价 §f" + Text.num(svc.engine().priceOf(k)));
            if (plan == null) {
                lore.add("§c没有报价");
            } else if (!plan.feasible) {
                lore.add("§c造不了 §8· §7" + plan.reason);
            } else {
                lore.add("§7方案总价 §f" + Text.num(plan.cost)
                        + " §8/ §7产出 §f" + plan.outputAmount);
                lore.add("§7深度 §f" + plan.depth + " §8/ §7叶子 §f" + plan.leaves);
                if (!plan.byproducts.isEmpty()) lore.add("§7副产物 §f" + plan.byproducts.size() + " §7种");
            }

            // 需要的机器 + 你还缺哪几台
            java.util.Set<ItemKey> need = plan != null && plan.feasible
                    ? plan.machines : java.util.Set.of();
            if (!need.isEmpty()) {
                lore.add("");
                lore.add("§7需要机器 §f" + need.size() + " §7台：");
                int n = 0;
                for (ItemKey m : need) {
                    if (n++ >= 4) {
                        lore.add("§8  … 等 " + need.size() + " 台");
                        break;
                    }
                    lore.add("§8  ▪ §7" + CraftGui.name(m));
                }
                java.util.Set<ItemKey> missing = new java.util.LinkedHashSet<>(need);
                missing.removeAll(machines);
                if (!missing.isEmpty()) {
                    lore.add("§c还缺 §f" + missing.size() + " §c台");
                } else {
                    lore.add("§a机器齐了");
                }
            } else if (plan != null && plan.feasible) {
                lore.add("§a工作台 / 原版即可，不用机器");
            }
            lore.add("");
            lore.add("§e点击选择");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ============================================================
    // 锁定：本界面只认点击，物品一个都拿不走
    // ============================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PickerHolder ph)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;

        if (raw == PREV) {
            if (ph.page > 0) {
                ph.page--;
                fill(ph);
            }
            return;
        }
        if (raw == NEXT) {
            int pages = Math.max(1, (ph.keys.size() + PER_PAGE - 1) / PER_PAGE);
            if (ph.page < pages - 1) {
                ph.page++;
                fill(ph);
            }
            return;
        }
        if (raw == BACK) {
            p.closeInventory();
            return;
        }
        if (raw >= PER_PAGE) return;

        int index = ph.page * PER_PAGE + raw;
        if (index < 0 || index >= ph.keys.size()) return;
        ph.main.target = ph.keys.get(index);
        ph.main.page = ph.page;
        p.closeInventory();
    }

    /** 拖拽不是 click 事件，必须单独挡掉，否则能从列表拖进背包 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PickerHolder)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PickerHolder ph)) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!p.isOnline()) {
            // 关着搜索时退出：主容器已经因为 switching 没退还，这里补退
            if (ph.main.returned.compareAndSet(false, true)) {
                main.dump(p, ph.main.inv);
            }
            return;
        }
        Bukkit.getScheduler().runTask(svc.plugin(), () -> main.openMain(p, ph.main));
    }

    private static final class PickerHolder implements InventoryHolder {
        final CraftGui.Holder main;
        final List<ItemKey> keys;
        final Map<ItemKey, CraftPlanner.Plan> catalog;
        final java.util.Set<ItemKey> machines;
        Inventory inv;
        int page;

        PickerHolder(CraftGui.Holder main, List<ItemKey> keys,
                     Map<ItemKey, CraftPlanner.Plan> catalog,
                     java.util.Set<ItemKey> machines) {
            this.main = main;
            this.keys = keys;
            this.catalog = catalog;
            this.machines = machines;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
