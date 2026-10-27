package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.recipe.Recipe;
import net.me.xinzhi.exchange.util.GuiItems;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动合成台：54 格真实容器（9×6）。
 *
 * <p>本轮接上「搜索目标 + 递归报价 + 配方树」；执行（扣料/扣钱/产出）下一步。
 */
public final class CraftGui implements Listener {

    public static final int SIZE = 54;
    private static final String TITLE = "§6§l自动合成台 §8· §79 机器 / 15 输入 / 15 输出";

    public static final int[] MACHINE = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    public static final int[] INPUT = {
            9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38, 45, 46, 47
    };
    public static final int[] OUTPUT = {
            15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44, 51, 52, 53
    };

    public static final int TARGET = 12, AMOUNT = 13, START = 14;
    public static final int TOTAL = 21, FEE = 22, DEPTH = 23;
    public static final int OFFSET = 30, BALANCE = 31, SPACE = 32;
    public static final int TREE = 39, MISSING = 40, CLEAR = 41;
    public static final int STATUS = 48, MACHINES = 49, CLOSE = 50;

    private static final Set<Integer> FUNC = Set.of(
            TARGET, AMOUNT, START, TOTAL, FEE, DEPTH,
            OFFSET, BALANCE, SPACE, TREE, MISSING, CLEAR, STATUS, MACHINES, CLOSE);

    private final ExchangeService svc;

    public CraftGui(ExchangeService svc) {
        this.svc = svc;
    }

    public void open(Player p) {
        Holder holder = new Holder(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE, TITLE);
        holder.inv = inv;
        fillStatic(inv);
        refresh(p, holder);
        p.openInventory(inv);
    }

    /** 从搜索/切换回来时重开同一个容器 */
    void openMain(Player p, Holder holder) {
        if (p == null || !p.isOnline()) return;
        p.openInventory(holder.inv);
        refresh(p, holder);
    }

    private void fillStatic(Inventory inv) {
        inv.setItem(TARGET, GuiItems.of(Material.COMPASS, "§e目标物品",
                "§7点击搜索要合成的物品",
                "§8只列当前机器能造的超上限物品"));
        inv.setItem(AMOUNT, GuiItems.of(Material.PAPER, "§e数量 §f1",
                "§7点击输入数量（下一步）"));
        inv.setItem(START, GuiItems.of(Material.LIME_DYE, "§a开始合成",
                "§7先报价，再确认扣料/扣余额",
                "§8执行下一步接"));
        inv.setItem(TOTAL, info(Material.GOLD_INGOT, "§6总价", "§7等待选择目标"));
        inv.setItem(FEE, info(Material.EXPERIENCE_BOTTLE, "§6手续费", "§75% / 层", "§7已含在总价里"));
        inv.setItem(DEPTH, info(Material.BOOK, "§6深度 / 叶子", "§7—"));
        inv.setItem(OFFSET, info(Material.HOPPER, "§6材料抵扣", "§7左格放材料，按买价抵"));
        inv.setItem(BALANCE, info(Material.EMERALD, "§6余额", "§7—"));
        inv.setItem(SPACE, info(Material.CHEST, "§6输出空间", "§7—"));
        inv.setItem(TREE, info(Material.OAK_SAPLING, "§6配方树", "§7点击在聊天栏展开"));
        inv.setItem(MISSING, info(Material.BARRIER, "§6缺机器 / 断链", "§7—"));
        inv.setItem(CLEAR, info(Material.LAVA_BUCKET, "§c清空输入", "§7材料退回背包"));
        inv.setItem(STATUS, info(Material.CLOCK, "§7状态", "§7空闲"));
        inv.setItem(MACHINES, info(Material.DISPENSER, "§7机器", "§7已装 0 / 9"));
        inv.setItem(CLOSE, GuiItems.of(Material.ARROW, "§c关闭"));
    }

    private static ItemStack info(Material mat, String name, String... lore) {
        return GuiItems.of(mat, name, lore);
    }

    // ============================================================
    // 刷新报价
    // ============================================================

    void refresh(Player p, Holder h) {
        Inventory inv = h.inv;
        h.machines = machinesOf(inv);
        if (h.target != null) {
            h.plan = svc.planner().plan(h.target, h.machines);
        } else {
            h.plan = null;
        }
        CraftPlanner.Plan plan = h.plan;

        // 装着的机器造不出来时，用「全部有价机器」再算一次：
        // 能造 ⇒ 只差机器，把缺的机器直接告诉玩家；造不了 ⇒ 材料真的断链。
        h.fullPlan = null;
        if (plan != null && !plan.feasible) {
            // 全机器方案只跟目标有关，按目标缓存 —— 不然每次点击都要重跑一次 DP
            if (!h.target.equals(h.fullPlanTarget)) {
                h.fullPlanCache = svc.planner().plan(h.target, svc.planner().allMachines());
                h.fullPlanTarget = h.target;
            }
            h.fullPlan = h.fullPlanCache;
        }
        java.util.Set<ItemKey> missing = missingMachines(h);

        double offset = 0;
        if (plan != null && plan.feasible) {
            for (int slot : INPUT) {
                ItemStack it = inv.getItem(slot);
                if (empty(it)) continue;
                ItemKey k = ItemKey.of(it);
                if (k == null) continue;
                Double need = plan.leafNeeds.get(k);
                if (need == null || need <= 0) continue;
                double take = Math.min(it.getAmount(), Math.ceil(need - 1e-9));
                offset += take * svc.engine().priceOf(k);
            }
        }
        if (plan != null && plan.feasible) offset = Math.min(offset, plan.cost);
        double balance = svc.manager().balance(p.getUniqueId());
        double pay = plan != null && plan.feasible ? Math.max(0, plan.cost - offset) : 0;

        int free = 0;
        for (int slot : OUTPUT) if (empty(inv.getItem(slot))) free++;
        int needSlots = plan != null && plan.feasible
                ? 1 + (plan.byproducts.isEmpty() ? 0 : plan.byproducts.size()) : 1;

        // 目标
        if (h.target == null) {
            inv.setItem(TARGET, GuiItems.of(Material.COMPASS, "§e目标物品",
                    "§7点击搜索要合成的物品"));
        } else {
            ItemStack icon = h.target.toTemplate();
            if (icon == null) icon = new ItemStack(Material.BARRIER);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e目标: §f" + name(h.target));
                meta.setLore(java.util.List.of(
                        "§7标价 §f" + Text.num(svc.engine().priceOf(h.target)),
                        "§7点击重新搜索"));
                icon.setItemMeta(meta);
            }
            inv.setItem(TARGET, icon);
        }

        inv.setItem(TOTAL, plan != null && plan.feasible
                ? info(Material.GOLD_INGOT, "§6总价 §f" + Text.num(plan.cost),
                        "§7产出 §f" + plan.outputAmount + " §7个",
                        "§7抵扣后应付 §f" + Text.num(pay))
                : info(Material.GOLD_INGOT, "§6总价", "§7—"));
        inv.setItem(DEPTH, plan != null && plan.feasible
                ? info(Material.BOOK, "§6深度 / 叶子",
                        "§7深度 §f" + plan.depth + " §8/ §7上限 " + CraftPlanner.MAX_DEPTH,
                        "§7叶子 §f" + plan.leaves + " §8/ §7上限 " + CraftPlanner.MAX_LEAVES)
                : info(Material.BOOK, "§6深度 / 叶子", "§7—"));
        inv.setItem(OFFSET, info(Material.HOPPER, "§6材料抵扣 §f" + Text.num(offset),
                "§7输入格里的叶子材料按买价抵"));
        inv.setItem(BALANCE, info(Material.EMERALD, "§6余额 §f" + Text.num(balance)));
        inv.setItem(SPACE, info(Material.CHEST, "§6输出空间 §f" + free + " §7格",
                "§7本次需要 §f" + needSlots + " §7格"));
        inv.setItem(MACHINES, info(Material.DISPENSER, "§7机器 §f" + h.machines.size() + " / 9",
                "§7第一排放机器物品"));
        if (plan != null && !plan.feasible) {
            if (!missing.isEmpty()) {
                java.util.List<String> lore = new java.util.ArrayList<>();
                lore.add("§7这台机器造不了，但下面的机器凑齐就能造：");
                int n = 0;
                for (ItemKey m : missing) {
                    if (n++ >= 5) {
                        lore.add("§8  … 共 " + missing.size() + " 台");
                        break;
                    }
                    lore.add("§c  ✖ §f" + name(m));
                }
                lore.add("");
                lore.add("§e点击在聊天栏展开");
                inv.setItem(MISSING, GuiItems.of(Material.BARRIER, "§c缺 " + missing.size() + " 台机器", lore));
            } else {
                inv.setItem(MISSING, info(Material.BARRIER, "§c不可造",
                        "§7" + plan.reason,
                        h.fullPlan != null && !h.fullPlan.feasible
                                ? "§8换机器也造不了" : ""));
            }
        } else {
            inv.setItem(MISSING, info(Material.BARRIER, "§a—", "§7没有阻断"));
        }

        String status;
        if (h.target == null) status = "§7未选择目标";
        else if (plan == null) status = "§7计算中…";
        else if (!plan.feasible) status = missing.isEmpty() ? "§c不可造" : "§c缺机器";
        else if (balance < pay) status = "§c余额不足";
        else if (free < needSlots) status = "§c输出空间不足";
        else status = "§a可合成";
        inv.setItem(STATUS, info(Material.CLOCK, "§7状态", status));
        inv.setItem(TREE, info(Material.OAK_SAPLING, "§6配方树",
                plan != null && plan.feasible ? "§7点击在聊天栏展开" : "§7先选一个可造目标"));
    }

    // ============================================================
    // 点击
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;

        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < SIZE;
        ClickType click = event.getClick();

        // 双击会从整个视图（含功能格/输出格的展示物品）收集同款物品到光标，
        // 等于把展示格里的东西抽出来 —— 一律拦掉。
        if (click == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        if (!inTop) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }

        if (FUNC.contains(raw)) {
            event.setCancelled(true);
            onFunc(p, holder, raw);
            return;
        }

        if (isOutput(raw)) {
            event.setCancelled(true);
            if (!event.isShiftClick() && isEmpty(event.getCursor())) {
                ItemStack it = top.getItem(raw);
                if (!empty(it)) {
                    top.setItem(raw, null);
                    p.setItemOnCursor(it);
                    refreshLater(p, holder);
                }
            }
            return;
        }

        if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }

        if (isMachine(raw)) {
            ItemStack cursor = event.getCursor();
            if (!empty(cursor) && !validMachine(p, cursor)) {
                event.setCancelled(true);
                return;
            }
            refreshLater(p, holder);
            return;
        }

        if (isInput(raw)) {
            if (event.isShiftClick()) event.setCancelled(true);
            else refreshLater(p, holder);
            return;
        }

        event.setCancelled(true);
    }

    private void onFunc(Player p, Holder holder, int slot) {
        switch (slot) {
            case CLOSE -> p.closeInventory();
            case CLEAR -> {
                clearInputs(p, holder.inv);
                refresh(p, holder);
            }
            case TARGET -> openPicker(p, holder);
            case TREE -> printTree(p, holder);
            case MISSING -> printMissing(p, holder);
            case AMOUNT -> p.sendMessage("§7[合成台] 数量输入下一步接（原版 Dialog）");
            case START -> {
                CraftPlanner.Plan plan = holder.plan;
                if (plan == null || !plan.feasible) {
                    p.sendMessage("§c[合成台] 先选一个可造目标");
                    return;
                }
                p.sendMessage("§7[合成台] 报价已就绪；扣料/扣钱/产出下一步接");
            }
            default -> {
            }
        }
    }

    private void openPicker(Player p, Holder holder) {
        holder.machines = machinesOf(holder.inv);
        holder.switching = true;
        p.closeInventory();
        svc.craftPicker().open(p, holder, holder.page);
    }

    private void printTree(Player p, Holder holder) {
        CraftPlanner.Plan plan = holder.plan;
        if (plan == null || !plan.feasible) {
            p.sendMessage("§c[合成台] 还没有可展开的配方树");
            return;
        }
        p.sendMessage("§6§l✦ 配方树 ✦ §7" + name(plan.target) + " ×" + plan.outputAmount);
        p.sendMessage("§7总价 §f" + Text.num(plan.cost)
                + " §8| §7深度 §f" + plan.depth
                + " §8| §7叶子 §f" + plan.leaves);
        for (Map.Entry<ItemKey, Recipe> e : plan.tree.entrySet()) {
            Recipe r = e.getValue();
            StringBuilder in = new StringBuilder();
            for (Map.Entry<ItemKey, Integer> i : r.getInputs().entrySet()) {
                if (in.length() > 0) in.append("§7 + ");
                in.append("§f").append(name(i.getKey())).append("§7×").append(i.getValue());
            }
            p.sendMessage("§e" + name(e.getKey()) + " §8← §7[" + name(r.getMachine()) + "] §8"
                    + in);
        }
        p.sendMessage("§7叶子清单：");
        for (Map.Entry<ItemKey, Double> e : plan.leafNeeds.entrySet()) {
            long n = (long) Math.ceil(e.getValue() - 1e-9);
            p.sendMessage("  §f" + name(e.getKey()) + " §7×" + n
                    + " §8@ §7" + Text.num(svc.engine().priceOf(e.getKey())));
        }
        if (!plan.byproducts.isEmpty()) {
            p.sendMessage("§7副产物：");
            for (Map.Entry<ItemKey, Integer> e : plan.byproducts.entrySet()) {
                p.sendMessage("  §f" + name(e.getKey()) + " §7×" + e.getValue());
            }
        }
    }

    /** 「缺机器」槽：把还差的机器在聊天栏完整列出来 */
    private void printMissing(Player p, Holder holder) {
        if (holder.target == null) {
            p.sendMessage("§7[合成台] 先选一个目标");
            return;
        }
        java.util.Set<ItemKey> missing = missingMachines(holder);
        if (missing.isEmpty()) {
            if (holder.plan != null && holder.plan.feasible) {
                p.sendMessage("§a[合成台] 机器齐了，不需要再装");
            } else {
                p.sendMessage("§c[合成台] 换哪台机器都造不了：§7"
                        + (holder.plan == null ? "—" : holder.plan.reason));
            }
            return;
        }
        p.sendMessage("§6[合成台] 还缺 §f" + missing.size() + " §6台机器（第一排最多放 9 台）：");
        for (ItemKey m : missing) {
            p.sendMessage("  §c✖ §f" + name(m) + " §8(" + m + ")");
        }
        p.sendMessage("§7提示：搜索列表里每条都会写「需要哪几台机器」");
    }

    /** 当前装着的机器缺哪几台（用全部有价机器算出来的方案对比） */
    java.util.Set<ItemKey> missingMachines(Holder h) {
        if (h.target == null) return java.util.Set.of();
        CraftPlanner.Plan full = h.fullPlan;
        if (full == null) full = svc.planner().plan(h.target, svc.planner().allMachines());
        if (!full.feasible) return java.util.Set.of();
        java.util.Set<ItemKey> missing = new java.util.LinkedHashSet<>(full.machines);
        missing.removeAll(h.machines);
        return missing;
    }

    private void clearInputs(Player p, Inventory inv) {
        int n = 0;
        for (int slot : INPUT) {
            ItemStack it = inv.getItem(slot);
            if (empty(it)) continue;
            inv.setItem(slot, null);
            give(p, it);
            n += it.getAmount();
        }
        p.sendMessage(n > 0 ? "§a[合成台] 已退回 " + n + " 个材料" : "§7[合成台] 输入区是空的");
    }

    private boolean validMachine(Player p, ItemStack stack) {
        ItemKey k = ItemKey.of(stack);
        if (k == null) {
            reject(p, stack, k, "认不出这个物品（既不是原版物品也不是粘液物品）");
            return false;
        }
        if (!svc.registry().machines().contains(k)) {
            reject(p, stack, k, "配方库里没有这台机器的配方");
            return false;
        }
        if (!(svc.engine().priceOf(k) > 0)) {
            reject(p, stack, k, "这台机器没有价（没有锚点、也没有可推导的配方）");
            return false;
        }
        return true;
    }

    /**
     * 机器被拒绝：聊天栏给玩家原因，同时落一条 diag/craft-reject.txt。
     * 腐竹 2026-10-19：「我好多机器放进去告诉我这个机器没配方」—— 光说「没配方」
     * 没法定位是「扫描没读到」还是「这台机器本来就不产出物品」，所以把 id / 配方数 /
     * 价格一起打出来，服主回一条日志就能查。
     */
    private void reject(Player p, ItemStack stack, ItemKey k, String reason) {
        String id = k == null ? String.valueOf(stack.getType()) : k.toString();
        int recipes = k == null ? 0 : svc.registry().ofMachine(k).size();
        double price = k == null ? 0 : svc.engine().priceOf(k);
        if (k != null && recipes == 0) {
            // 腐竹 2026-10-19：这种情况多半不是「这台机器没有配方」，而是它的配方
            // 已经被先扫描到的另一台机器抢先占用（例：坍缩机先认领了「奇点」，
            // 奇点构造机就显得没配方）。别对玩家说「没配方」，要引导他去看正确组合。
            p.sendMessage("§c[合成台] 这台机器在配方库里没有独立配方");
            p.sendMessage("§7  常见原因：它的配方已经被先扫描到的别的机器抢先占用");
            p.sendMessage("§7  请点「§e目标物品§7」进选择界面，那里的「§e需要机器§7」才是正确的机器组合");
            p.sendMessage("§8  id=" + id + " §8配方=0 §8价=" + Text.num(price));
        } else {
            p.sendMessage("§c[合成台] 这台机器不能用：§7" + reason);
            p.sendMessage("§8  id=" + id + " §8配方=" + recipes + " §8价=" + Text.num(price));
        }
        try {
            java.io.File dir = new java.io.File(svc.plugin().getDataFolder(), "diag");
            if (!dir.exists()) dir.mkdirs();
            String line = java.time.LocalDateTime.now() + "\t" + p.getName() + "\t" + id
                    + "\t配方=" + recipes + "\t价=" + price + "\t" + reason + "\n";
            java.nio.file.Files.writeString(dir.toPath().resolve("craft-reject.txt"), line,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private void refreshLater(Player p, Holder h) {
        Bukkit.getScheduler().runTask(svc.plugin(), () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory() == h.inv) {
                refresh(p, h);
            }
        });
    }

    // ============================================================
    // 拖拽 / 关闭
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE && (FUNC.contains(raw) || isOutput(raw) || isMachine(raw))) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getWhoClicked() instanceof Player p) refreshLater(p, holder);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        if (holder.switching) {
            holder.switching = false;
            return;
        }
        if (!holder.returned.compareAndSet(false, true)) return;
        Player p = event.getPlayer() instanceof Player pl ? pl : Bukkit.getPlayer(holder.player);
        dump(p, holder.inv);
    }

    /** 把容器里的真实物品退还/掉落；同一个 holder 只执行一次 */
    void dump(Player p, Inventory inv) {
        if (inv == null) return;
        for (int i = 0; i < SIZE; i++) {
            if (FUNC.contains(i)) continue;
            ItemStack it = inv.getItem(i);
            if (empty(it)) continue;
            inv.setItem(i, null);
            if (p != null && p.isOnline()) give(p, it);
            else if (p != null) p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
    }

    private static void give(Player p, ItemStack it) {
        if (empty(it)) return;
        var left = p.getInventory().addItem(it);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    static boolean empty(ItemStack it) {
        return it == null || it.getType().isAir();
    }

    private static boolean isEmpty(ItemStack it) {
        return empty(it);
    }

    private static Set<ItemKey> machinesOf(Inventory inv) {
        Set<ItemKey> out = new HashSet<>();
        for (int slot : MACHINE) {
            ItemStack it = inv.getItem(slot);
            if (empty(it)) continue;
            ItemKey k = ItemKey.of(it);
            if (k != null) out.add(k);
        }
        return out;
    }

    private static boolean isMachine(int slot) {
        for (int s : MACHINE) if (s == slot) return true;
        return false;
    }

    private static boolean isInput(int slot) {
        for (int s : INPUT) if (s == slot) return true;
        return false;
    }

    private static boolean isOutput(int slot) {
        for (int s : OUTPUT) if (s == slot) return true;
        return false;
    }

    static String name(ItemKey k) {
        if (k == null) return "?";
        try {
            return k.getDisplayName();
        } catch (Throwable t) {
            return k.toString();
        }
    }

    /** 会话标记：同一个容器只退还一次，防止 close + quit 双退。 */
    static final class Holder implements InventoryHolder {
        final UUID player;
        final AtomicBoolean returned = new AtomicBoolean(false);
        Inventory inv;
        ItemKey target;
        CraftPlanner.Plan plan;
        /** 装着的机器造不出来时，用全部机器算的方案（判断「只差机器」） */
        CraftPlanner.Plan fullPlan;
        /** 全机器方案缓存（只跟目标有关） */
        CraftPlanner.Plan fullPlanCache;
        ItemKey fullPlanTarget;
        Set<ItemKey> machines = Set.of();
        boolean switching;
        int page;

        Holder(UUID player) {
            this.player = player;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
