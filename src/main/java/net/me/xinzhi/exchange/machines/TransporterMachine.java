package net.me.xinzhi.exchange.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import net.me.xinzhi.exchange.XinZhiExchange;
import net.me.xinzhi.exchange.core.ItemGuard;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.exchange.TradeCap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 等价运输机（腐竹 2026-10-26 m00819 / m00888 / m00896 / m00988）。
 *
 * <p>放在目标机器<b>下方</b>。主界面的 <b>0~44 槽是「输入槽」</b>——只用来存放可以被运输机
 * 利用的物品。
 *
 * <p><b>投料映射在另一个界面里设置</b>：点「设置投料映射」打开一个几乎和上方机器一样的
 * 模仿界面，玩家把物品<b>拖</b>进去 = 「这个槽位要这种物品、这么多」；<b>拖拽/点击后立刻
 * 记录</b>（不用等关界面），关界面时把模板物品退回玩家。上方机器已经有物品的槽位锁死。
 *
 * <p>周期（默认 2 tick）：按映射逐槽处理，上方该槽为空才投；物品<b>先看本机 0~44 输入槽
 * 有没有对应物品</b>，没有才按买价买。每成功一槽 100 EMC + 购买价，机主付。
 */
public final class TransporterMachine extends AbstractMachine {

    public static final String ID = "XZEX_TRANSPORTER";

    private static final String TARGET_KEY = "xz-tr-target";
    private static final String MAP_PREFIX = "xz-tr-map";

    /** 0~44：输入槽（放可被利用的物品） */
    static final int INPUT_SLOTS = 45;
    /** 映射最多记到第 53 槽（上方机器若是 54 格，最后一行也要能设置 —— 腐竹 m00988 第 2 条） */
    static final int MAP_SLOTS = 54;

    /** 0~44 的输入槽数组（物流取用；每次返回副本，别让人改到常量） */
    private static final int[] SLOTS_0_44 = new int[INPUT_SLOTS];

    static {
        for (int i = 0; i < INPUT_SLOTS; i++) SLOTS_0_44[i] = i;
    }

    /** 46 = 等价道具槽（腐竹 m01476：运输机买东西也要受显示上限约束），48/50/52 锁死 */
    private static final int OPEN_SETTING = 45, CAP = 46, BALANCE = 47, STATUS = 49, RUN_NOW = 51, REFRESH = 53;

    /** 周期计数放内存，别写盘 */
    private final Map<Location, Integer> counters = new HashMap<>();
    /** 最近一次结果，只在打开界面时显示（多个实例共用，键是坐标） */
    private static final Map<Location, String> LAST_STATUS = new HashMap<>();
    /** 上次画出去的内容：没变就不重建那 3 个带 lore 的物品（腐竹 2026-10-30 降载） */
    private static final Map<Location, String> LAST_RENDER = new ConcurrentHashMap<>();
    /**
     * 投料映射的内存缓存。
     *
     * <p>腐竹 2026-10-30「首先要减少查询次数」：以前每个周期都要读 54 次 {@code xz-tr-mapN}
     * 再从字符串解析，大多数机器其实只设了几个槽。现在只在 {@link #saveMappings} 与放置时失效。
     */
    private static final Map<Location, Map<Integer, Map.Entry<ItemKey, Integer>>> MAP_CACHE =
            new ConcurrentHashMap<>();

    public TransporterMachine(ItemGroup group, SlimefunItemStack item,
                              RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                MachineUtil.setOwner(e.getBlock().getLocation(), e.getPlayer());
                String above = SynthesisMachine.idOf(e.getBlock().getRelative(BlockFace.UP));
                if (above != null) MachineUtil.set(e.getBlock().getLocation(), TARGET_KEY, above);
                // 新机器：旧的映射缓存必须作废（同坐标可能拆了再放）
                invalidate(e.getBlock().getLocation());
            }
        });
    }

    @Override
    protected String menuTitle() {
        return "§b等价运输机 §8· §7输入槽 0~44";
    }

    @Override
    protected void initMenu(@Nonnull BlockMenuPreset preset) {
        preset.addItem(OPEN_SETTING, new CustomItemStack(Material.CRAFTING_TABLE, "§e设置投料映射",
                "§7打开上方机器的模仿界面", "§7把物品拖进去 = 那个槽要投什么"), (p, s, i, a) -> false);
        preset.addItem(BALANCE, new CustomItemStack(Material.EMERALD, "§6机主余额", "§7—"), (p, s, i, a) -> false);
        preset.addItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7待机"), (p, s, i, a) -> false);
        preset.addItem(RUN_NOW, new CustomItemStack(Material.LIME_DYE, "§a立即执行一次", "§7不等周期"), (p, s, i, a) -> false);
        preset.addItem(REFRESH, new CustomItemStack(Material.SPYGLASS, "§b刷新记录", "§7重新记录正上方的机器"), (p, s, i, a) -> false);
        // 46 是真·空槽（放等价道具），连玻璃板都不放；只锁 48、50、52
        for (int i : new int[]{48, 50, 52}) {
            preset.addItem(i, LOCKED_PANE, (p, s, item, action) -> false);
        }
    }

    @Override
    public void onMenuOpen(@Nonnull BlockMenu menu, @Nonnull Block b) {
        render(menu, b);
    }

    /** 供其它地方刷新主界面（腐竹 m00988：「注意刷新菜单来显示 emc」） */
    static void refresh(Location loc) {
        try {
            if (loc == null) return;
            LAST_RENDER.remove(loc);
            BlockMenu menu = MachineDrops.menu(loc);
            if (menu == null) return;
            render(menu, loc.getBlock());
        } catch (Throwable ignored) {
        }
    }

    /** 作废这台机器的内存缓存（映射 / 上次画出去的内容） */
    static void invalidate(Location loc) {
        if (loc == null) return;
        MAP_CACHE.remove(loc);
        LAST_RENDER.remove(loc);
    }

    static void render(BlockMenu menu, Block b) {
        if (menu == null || b == null) return;
        Location loc = b.getLocation();
        ExchangeService svc = ExchangeService.get();
        UUID owner = MachineUtil.owner(loc);
        String ownerName = MachineUtil.ownerName(owner);
        double balance = svc != null && owner != null ? svc.manager().balances().get(owner) : 0;
        String storedId = MachineUtil.info(loc, TARGET_KEY);
        Map<Integer, Map.Entry<ItemKey, Integer>> map = mappings(loc);
        long capNow = MachineUtil.capOf(menu, CAP);
        // 判「上方机器」只看方块 id：比完整 resolve 便宜，id 变了就一定会重画
        String aboveId = SynthesisMachine.idOf(b.getRelative(BlockFace.UP));
        String status = LAST_STATUS.getOrDefault(loc, "§7待机");
        String key = ownerName + "|" + balance + "|" + map.size() + "|" + storedId + "|" + capNow
                + "|" + status + "|" + aboveId;
        // 内容没变就不重建这 3 个带 lore 的物品（空转运输机以前每周期都重画）
        if (key.equals(LAST_RENDER.get(loc))) return;
        LAST_RENDER.put(loc, key);
        try {
            List<String> lore = new ArrayList<>();
            lore.add("§7机主 §f" + ownerName);
            lore.add("§7上方机器: " + (aboveId == null ? "§c没有" : "§f" + aboveId));
            lore.add("§7记录: " + (storedId == null ? "§8—" : (aboveId != null && !storedId.equals(aboveId) ? "§c" + storedId + "（不符，待机）" : "§f" + storedId)));
            lore.add("§7已设置: §f" + map.size() + " §7个槽位");
            lore.add("§8拖物品进去即时记录");
            menu.replaceExistingItem(OPEN_SETTING, new CustomItemStack(Material.CRAFTING_TABLE, "§e设置投料映射", lore.toArray(new String[0])));

            List<String> bl = new ArrayList<>();
            bl.add("§7机主 §f" + ownerName);
            bl.add("§7机主余额 §f" + SynthesisMachine.fmt(balance) + " §7EMC");
            bl.add("§7每投成功一槽: §f" + SynthesisMachine.fmt(svc == null ? 100 : svc.config().synthesisLogisticsEmcPerSlot) + " EMC");
            menu.replaceExistingItem(BALANCE, new CustomItemStack(Material.EMERALD, "§6机主余额", bl.toArray(new String[0])));

            List<String> sl = new ArrayList<>();
            sl.add("§7" + status);
            sl.add("§7周期: §f" + (svc == null ? 3 : svc.config().synthesisLogisticsInterval) + " tick");
            sl.add(capNow == MachineUtil.CAP_MISSING
                    ? "§c第 " + CAP + " 槽未放等价道具：只能用输入槽"
                    : "§7第 " + CAP + " 槽上限 §f"
                      + (capNow == TradeCap.UNLIMITED ? "无限制" : SynthesisMachine.fmt(capNow) + " EMC"));
            sl.add("§8先查输入槽，没有才买");
            menu.replaceExistingItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", sl.toArray(new String[0])));
        } catch (Throwable ignored) {
        }

        menu.addMenuClickHandler(OPEN_SETTING, (p, s, item, action) -> {
            openSetting(p, b);
            return false;
        });
        menu.addMenuClickHandler(RUN_NOW, (p, s, item, action) -> {
            UUID o = MachineUtil.ownerOrClaim(loc, p);
            if (o == null || !o.equals(p.getUniqueId())) {
                p.sendMessage("§c[运输机] 只有机主能手动跑一轮（花的是机主的 EMC）");
                return false;
            }
            String msg = runStatic(b);
            p.sendMessage("§e[运输机] " + msg);
            render(menu, b);
            return false;
        });
        menu.addMenuClickHandler(REFRESH, (p, s, item, action) -> {
            UUID o = MachineUtil.ownerOrClaim(loc, p);
            if (o == null || !o.equals(p.getUniqueId())) {
                p.sendMessage("§c[运输机] 只有机主能刷新记录");
                return false;
            }
            SynthesisMachine.Target now = SynthesisMachine.resolve(b);
            if (now == null) {
                p.sendMessage("§c[运输机] 上方没有机器");
                return false;
            }
            MachineUtil.set(loc, TARGET_KEY, now.id);
            p.sendMessage("§a[运输机] 已重新记录上方机器: " + now.id);
            render(menu, b);
            return false;
        });
    }

    // ============================================================
    // 设置映射界面（模仿上方机器）
    // ============================================================

    static void openSetting(Player p, Block b) {
        Location loc = b.getLocation();
        SynthesisMachine.Target t = SynthesisMachine.resolve(b);
        if (t == null) {
            p.sendMessage("§c[运输机] 上方没有机器");
            return;
        }
        BlockMenu above = t.menu;
        if (above == null) {
            p.sendMessage("§c[运输机] 读不到上方机器的界面");
            return;
        }
        int size = sizeOf(above);
        int invSize = Math.max(27, Math.min(54, size));
        SettingHolder holder = new SettingHolder(loc, t.id, invSize);
        Inventory inv = Bukkit.createInventory(holder, invSize, "§8设置投料映射 §7· §8拖进去即记录");

        Map<Integer, Map.Entry<ItemKey, Integer>> current = mappings(loc);
        for (int slot = 0; slot < invSize; slot++) {
            ItemStack up = itemIn(above, slot);
            boolean occupied = up != null && !up.getType().isAir();

            // ① 已经设置过：直接把记录的东西显示出来（腐竹 m01059：不要用红色玻璃板盖掉，
            //    否则上方机器一旦有物品，之前的记录就看不见、还被顺手清掉）
            Map.Entry<ItemKey, Integer> cur = current.get(slot);
            if (cur != null) {
                ItemStack tpl = cur.getKey().toTemplate();
                if (tpl != null) {
                    ItemStack show = tpl.clone();
                    show.setAmount(Math.max(1, Math.min(cur.getValue(), Math.max(1, show.getMaxStackSize()))));
                    inv.setItem(slot, show);
                    holder.prefilled.add(slot);   // 显示副本：关界面不能退给玩家（否则复制）
                    holder.want.put(slot, Math.max(1, cur.getValue()));   // 真实设定数量，别被 maxStack 截断改写
                    continue;
                }
            }
            // ② 没设置过、上方机器这一格又有东西：锁死，避免把机器里的物品顶掉
            if (occupied) {
                holder.locked.add(slot);
                inv.setItem(slot, disabledIcon(up));
            }
        }
        holder.inv = inv;
        p.openInventory(inv);
    }

    private static ItemStack disabledIcon(ItemStack above) {
        ItemStack icon = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§7上方机器这一格已经有物品：");
            ItemKey k = ItemKey.of(above);
            lore.add("§f" + (k == null ? "?" : SynthesisMachine.shortName(k)) + " ×" + above.getAmount());
            lore.add("§8清空后才能在这里设置");
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** 把界面里的内容写成映射；returnItems=true 时清空格子并把模板物品退回玩家 */
    static void saveMappings(SettingHolder sh, Inventory inv, Player p, boolean returnItems) {
        if (sh == null || inv == null) return;
        Location loc = sh.loc;
        Map<Integer, String> map = new HashMap<>();
        for (int slot = 0; slot < sh.size && slot < MAP_SLOTS; slot++) {
            if (sh.locked.contains(slot)) continue;
            ItemStack it = inv.getItem(slot);
            boolean prefilled = sh.prefilled.contains(slot);
            if (it == null || it.getType().isAir()) continue;
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            int amount = Math.max(1, sh.want.getOrDefault(slot, it.getAmount()));
            map.put(slot, k + "|" + amount);
            if (returnItems) {
                inv.setItem(slot, null);
                if (!prefilled && p != null) {
                    Map<Integer, ItemStack> rest = p.getInventory().addItem(it.clone());
                    for (ItemStack drop : rest.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), drop);
                    }
                }
            }
        }
        for (int slot = 0; slot < MAP_SLOTS; slot++) {
            MachineUtil.set(loc, MAP_PREFIX + slot, map.get(slot));
        }
        sh.sizeSaved = map.size();
        invalidate(loc);
        refresh(loc);
    }

    /** 设置界面的会话容器 */
    static final class SettingHolder implements InventoryHolder {
        final Location loc;
        final String targetId;
        final int size;
        final Set<Integer> locked = new HashSet<>();
        final Set<Integer> prefilled = new HashSet<>();
        /** 槽位 -> 玩家真实设定的投料数量（prefilled 格子的显示数量可能被 maxStack 截断） */
        final Map<Integer, Integer> want = new HashMap<>();
        int sizeSaved;
        Inventory inv;

        SettingHolder(Location loc, String targetId, int size) {
            this.loc = loc;
            this.targetId = targetId;
            this.size = size;
        }

        @Override
        public @Nonnull Inventory getInventory() {
            return inv;
        }
    }

    /** 设置界面的监听器：拖拽/点击后立即记录；关闭时退回模板物品 */
    public static final class SettingListener implements Listener {

        private static void scheduleSave(SettingHolder sh, Player p) {
            try {
                Bukkit.getScheduler().runTaskLater(XinZhiExchange.get(), () -> {
                    Inventory inv = sh.getInventory();
                    if (inv != null) saveMappings(sh, inv, p, false);
                }, 1L);
            } catch (Throwable ignored) {
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof SettingHolder sh)) return;
            Player p = e.getWhoClicked() instanceof Player pl ? pl : null;
            int raw = e.getRawSlot();

            // 双击收集会把槽里的「显示副本」吸到光标 = 复制，整个界面禁用
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                return;
            }
            if (raw < 0) return;
            if (raw >= sh.size) {
                // 玩家背包区域：Shift 点击会把物品塞进界面、可能合并到显示副本上
                if (e.isShiftClick()) {
                    e.setCancelled(true);
                    if (p != null) p.sendMessage("§7[运输机] 请把物品拖进界面、或拿在手上点格子；Shift 直塞会污染显示格");
                }
                return;
            }

            if (sh.locked.contains(raw)) {
                e.setCancelled(true);
                if (p != null) p.sendMessage("§7[运输机] 上方机器第 " + raw + " 槽已经有物品了，不能设置");
                return;
            }
            if (sh.prefilled.contains(raw)) {
                // 这一格是凭空造出来的副本：任何可能让它离开格子的操作先取消，再手动处理
                e.setCancelled(true);
                ItemStack hand = e.getCursor();
                boolean holding = hand != null && !hand.getType().isAir();
                if (!holding) {
                    if (e.isShiftClick()) {
                        // Shift+左键 = 清除这一格的投料记录
                        sh.prefilled.remove(raw);
                        sh.want.remove(raw);
                        e.getInventory().setItem(raw, null);
                        if (p != null) p.sendMessage("§7[运输机] 已清除第 " + raw + " 槽的投料记录");
                        scheduleSave(sh, p);
                    } else if (p != null) {
                        p.sendMessage("§7[运输机] 手持要投的物品点这一格即可改写；Shift+左键清除");
                    }
                    return;
                }
                // 手持物品 = 直接改写这一格的映射；物品留在手上（不消耗、不复制）
                ItemKey k = ItemKey.of(hand);
                ItemStack tpl = k == null ? null : k.toTemplate();
                if (tpl == null) {
                    if (p != null) p.sendMessage("§c[运输机] 这个物品不能作为投料");
                    return;
                }
                int amount = Math.max(1, hand.getAmount());
                int max = Math.max(1, tpl.getMaxStackSize());
                ItemStack show = tpl.clone();
                show.setAmount(Math.min(amount, max));
                e.getInventory().setItem(raw, show);
                sh.want.put(raw, amount);
                if (p != null) {
                    p.sendMessage("§a[运输机] 第 " + raw + " 槽 §7→ §f" + SynthesisMachine.shortName(k)
                            + " §7×§f" + amount
                            + (amount > max ? " §8(每次最多投 " + max + ")" : ""));
                }
                scheduleSave(sh, p);
                return;
            }
            scheduleSave(sh, p);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent e) {
            if (!(e.getInventory().getHolder() instanceof SettingHolder sh)) return;
            Player p = e.getWhoClicked() instanceof Player pl ? pl : null;
            for (int raw : e.getRawSlots()) {
                if (raw < sh.size && sh.locked.contains(raw)) {
                    e.setCancelled(true);
                    if (p != null) p.sendMessage("§7[运输机] 上方机器第 " + raw + " 槽已经有物品了");
                    return;
                }
            }
            for (int raw : e.getRawSlots()) {
                if (raw < sh.size && sh.prefilled.contains(raw)) {
                    // 已设置的格子不接拖拽：拖进去会把显示副本合并/交换掉 = 复制
                    e.setCancelled(true);
                    if (p != null) {
                        p.sendMessage("§7[运输机] 第 " + raw + " 槽已经设置过了：手持物品点它改写，Shift+左键清除");
                    }
                    return;
                }
            }
            scheduleSave(sh, p);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (!(e.getInventory().getHolder() instanceof SettingHolder sh)) return;
            Player p = e.getPlayer() instanceof Player pl ? pl : null;
            saveMappings(sh, e.getInventory(), p, true);
            if (p != null) {
                p.sendMessage(sh.sizeSaved > 0
                        ? "§a[运输机] 已记录 §f" + sh.sizeSaved + " §a个槽位的投料映射"
                        : "§7[运输机] 没有设置任何投料映射");
            }
        }
    }

    // ============================================================
    // 周期执行
    // ============================================================

    @Override
    protected void onTick(@Nonnull Block b, @Nonnull SlimefunItem item, SlimefunBlockData data) {
        try {
            Location loc = b.getLocation();
            ExchangeService svc = ExchangeService.get();
            int interval = svc == null ? 2 : Math.max(1, svc.config().synthesisLogisticsInterval);
            int cd = counters.merge(loc, 1, Integer::sum);
            if (cd < interval) return;
            counters.put(loc, 0);
            runStatic(b);
            render(MachineDrops.menu(loc), b);
        } catch (Throwable ignored) {
        }
    }

    static String runStatic(Block b) {
        Location loc = b.getLocation();
        ExchangeService svc = ExchangeService.get();
        if (svc == null) return "§c服务未就绪";
        UUID owner = MachineUtil.owner(loc);
        if (owner == null) {
            LAST_STATUS.put(loc, "§c未登记主人：由放置者打开一次");
            return "§c这台机器还没登记主人";
        }
        SynthesisMachine.Target t = SynthesisMachine.resolve(b);
        if (t == null) {
            LAST_STATUS.put(loc, "§c上方没有机器");
            return "§c上方没有机器";
        }
        String stored = MachineUtil.info(loc, TARGET_KEY);
        if (stored == null) {
            MachineUtil.set(loc, TARGET_KEY, t.id);
            stored = t.id;
        } else if (!stored.equals(t.id)) {
            LAST_STATUS.put(loc, "§c上方机器不匹配（记录 " + stored + "）");
            return "§c上方机器换了（记录的是 " + stored + "），不操作";
        }
        if (t.menu == null) {
            LAST_STATUS.put(loc, "§7上方机器菜单读不到");
            return "§7上方机器菜单读不到";
        }
        Player p = Bukkit.getPlayer(owner);
        if (p == null || !p.isOnline()) {
            LAST_STATUS.put(loc, "§7机主不在线，待机");
            return "§7机主不在线，待机";
        }

        BlockMenu menu = MachineDrops.menu(loc);
        Map<Integer, Map.Entry<ItemKey, Integer>> map = mappings(loc);
        if (map.isEmpty()) {
            LAST_STATUS.put(loc, "§7还没有设置投料映射");
            return "§7还没有设置投料映射（点「设置投料映射」）";
        }

        // 本机输入槽与上方机器各拿一次快照：以前每槽单读、回填时再逐格扫两遍
        Inputs inputs = new Inputs(menu, loc);
        ItemStack[] above = snapshot(t.menu);
        int aboveSize = above.length;
        double perSlot = svc.config().synthesisLogisticsEmcPerSlot;
        // 买东西必须先有等价道具（腐竹 m01476）
        long cap = MachineUtil.capOf(menu, CAP);
        String blockReason = null;
        int done = 0, skipped = 0;
        try {
            for (Map.Entry<Integer, Map.Entry<ItemKey, Integer>> e : map.entrySet()) {
                int slot = e.getKey();
                if (slot < 0 || slot >= aboveSize) {
                    skipped++;
                    continue;
                }
                ItemKey k = e.getValue().getKey();
                int want = Math.max(1, e.getValue().getValue());
                if (k == null) continue;
                ItemStack existing = above[slot];
                if (existing != null && !existing.getType().isAir()) {
                    skipped++;
                    continue;
                }

                // ① 先看本机 0~44 输入槽有没有
                int have = inputs.take(k, want);
                int need = want - have;
                double cost = perSlot;
                if (need > 0) {
                    double unit = MachineUtil.buyUnit(loc, k);
                    boolean buyable = unit > 0 && MachineUtil.buyAllowed(cap, unit);
                    if (!buyable) {
                        if (blockReason == null) {
                            blockReason = unit <= 0
                                    ? "§7" + SynthesisMachine.shortName(k) + " 买不到"
                                    : (cap == MachineUtil.CAP_MISSING
                                       ? "§c第 " + CAP + " 槽没放等价道具，买不了 " + SynthesisMachine.shortName(k)
                                       : "§7" + SynthesisMachine.shortName(k) + " 超出等价道具上限");
                        }
                        if (have <= 0) {
                            skipped++;
                            continue;
                        }
                        need = 0;
                        want = have;
                    } else {
                        cost += unit * need;
                    }
                }
                if (svc.manager().balances().get(owner) < cost) {
                    if (have > 0) inputs.put(k, have);
                    LAST_STATUS.put(loc, "§7余额不够（这一槽要 " + SynthesisMachine.fmt(cost) + "）");
                    if (done == 0) return "§7余额不够：这一槽要 " + SynthesisMachine.fmt(cost) + " EMC";
                    break;
                }
                svc.manager().balances().add(owner, -cost);

                ItemStack out = k.toTemplate();
                if (out == null || want <= 0) {
                    if (have > 0) inputs.put(k, have);
                    skipped++;
                    continue;
                }
                out = out.clone();
                out.setAmount(Math.min(want, Math.max(1, out.getMaxStackSize())));
                if (!putItem(t.menu, slot, out)) {
                    if (have > 0) inputs.put(k, have);
                    skipped++;
                    continue;
                }
                above[slot] = out;   // 同一轮里同一格不会再投第二次（映射每个槽最多一条）
                done++;
            }
        } finally {
            // 无论从哪条路返回，取走的料都要落回输入槽
            inputs.flush();
        }
        String tail = blockReason == null ? "" : "§7；" + blockReason;
        String msg = (done > 0
                ? "§a本轮投料 " + done + " 槽" + (skipped > 0 ? "§7，跳过 " + skipped : "")
                : "§7没有可投的槽位" + (skipped > 0 ? "（跳过 " + skipped + "）" : "")) + tail;
        LAST_STATUS.put(loc, msg);
        return msg;
    }

    // ============================================================
    // 数据 / 搬运
    // ============================================================

    /**
     * 投料映射。第一次读走方块数据，之后走内存缓存，只在保存映射 / 重新放置时失效。
     *
     * <p>腐竹 2026-10-30「首先要减少查询次数」：以前每个周期都读 54 次方块数据再解析字符串。
     */
    static Map<Integer, Map.Entry<ItemKey, Integer>> mappings(Location loc) {
        if (loc == null) return Map.of();
        Map<Integer, Map.Entry<ItemKey, Integer>> hit = MAP_CACHE.get(loc);
        if (hit != null) return hit;
        Map<Integer, Map.Entry<ItemKey, Integer>> out = new LinkedHashMap<>();
        for (int slot = 0; slot < MAP_SLOTS; slot++) {
            String raw = MachineUtil.info(loc, MAP_PREFIX + slot);
            if (raw == null || raw.isBlank()) continue;
            int i = raw.lastIndexOf('|');
            if (i <= 0) continue;
            try {
                ItemKey k = ItemKey.fromStored(raw.substring(0, i));
                int amount = Math.max(1, Integer.parseInt(raw.substring(i + 1).trim()));
                if (k != null) out.put(slot, Map.entry(k, amount));
            } catch (Throwable ignored) {
            }
        }
        Map<Integer, Map.Entry<ItemKey, Integer>> frozen = Map.copyOf(out);
        if (MAP_CACHE.size() > 4096) MAP_CACHE.clear();   // 兜底：拆掉的机器不会自己清键
        MAP_CACHE.put(loc, frozen);
        return frozen;
    }

    /** 整块菜单快照；拿不到就给 54 个空位，调用方不必判空 */
    private static ItemStack[] snapshot(BlockMenu menu) {
        try {
            if (menu != null) {
                ItemStack[] snap = menu.getContents();
                if (snap != null) return snap;
            }
        } catch (Throwable ignored) {
        }
        return new ItemStack[54];
    }

    /**
     * 本机 0~44 输入槽的一轮视图：取料/回填都在本地副本上算，结束时只写回动过的格子。
     *
     * <p>腐竹 2026-10-30「首先要减少查询次数」：以前每取一种料都从 0 扫到 44，回填还要再扫两遍。
     */
    private static final class Inputs {
        private final BlockMenu menu;
        private final Location loc;
        private final ItemStack[] items = new ItemStack[INPUT_SLOTS];
        private final boolean[] dirty = new boolean[INPUT_SLOTS];

        Inputs(BlockMenu menu, Location loc) {
            this.menu = menu;
            this.loc = loc;
            ItemStack[] snap = snapshot(menu);
            System.arraycopy(snap, 0, items, 0, Math.min(items.length, snap.length));
        }

        /** 取料；返回实际取到的数量 */
        int take(ItemKey k, int amt) {
            if (k == null || amt <= 0) return 0;
            int left = amt;
            for (int i = 0; i < items.length && left > 0; i++) {
                ItemStack it = items[i];
                if (it == null || it.getType().isAir()) continue;
                ItemKey other = ItemKey.of(it);
                if (other == null || !other.equals(k) || !ItemGuard.pristine(k, it)) continue;
                int take = Math.min(left, it.getAmount());
                int rest = it.getAmount() - take;
                if (rest <= 0) {
                    items[i] = null;
                } else {
                    ItemStack copy = it.clone();
                    copy.setAmount(rest);
                    items[i] = copy;
                }
                dirty[i] = true;
                left -= take;
            }
            return amt - left;
        }

        /** 把没花掉的东西塞回输入槽；塞不下掉在机器上 */
        void put(ItemKey k, int amount) {
            if (k == null || amount <= 0) return;
            ItemStack tpl = k.toTemplate();
            if (tpl == null) return;
            int max = Math.max(1, tpl.getMaxStackSize());
            int left = amount;
            if (menu != null) {
                for (int i = 0; i < items.length && left > 0; i++) {
                    ItemStack it = items[i];
                    if (it == null || it.getType().isAir()) continue;
                    ItemKey other = ItemKey.of(it);
                    if (other == null || !other.equals(k)) continue;
                    int add = Math.min(left, max - it.getAmount());
                    if (add <= 0) continue;
                    ItemStack copy = it.clone();
                    copy.setAmount(it.getAmount() + add);
                    items[i] = copy;
                    dirty[i] = true;
                    left -= add;
                }
                for (int i = 0; i < items.length && left > 0; i++) {
                    ItemStack it = items[i];
                    if (it != null && !it.getType().isAir()) continue;
                    ItemStack copy = tpl.clone();
                    copy.setAmount(Math.min(left, max));
                    items[i] = copy;
                    dirty[i] = true;
                    left -= copy.getAmount();
                }
            }
            while (left > 0) {
                ItemStack one = tpl.clone();
                int n = Math.min(left, max);
                one.setAmount(n);
                left -= n;
                try {
                    loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 1, 0.5), one);
                } catch (Throwable ignored) {
                }
            }
        }

        /** 只写回真正动过的格子 */
        void flush() {
            if (menu == null) return;
            for (int i = 0; i < items.length; i++) {
                if (!dirty[i]) continue;
                try {
                    menu.replaceExistingItem(i, items[i]);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean putItem(BlockMenu menu, int slot, ItemStack item) {
        try {
            ItemStack cur = menu.getItemInSlot(slot);
            if (cur != null && !cur.getType().isAir()) return false;
            menu.replaceExistingItem(slot, item);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemStack itemIn(BlockMenu menu, int slot) {
        try {
            return menu == null ? null : menu.getItemInSlot(slot);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int sizeOf(BlockMenu menu) {
        try {
            if (menu == null) return 0;
            // 以前走 toInventory()（可能按需建 Bukkit 容器），ChestMenu 自己就有 getSize()
            return menu.getSize();
        } catch (Throwable t) {
            return 54;
        }
    }

    @Override
    protected int[] storageSlotsForDrop() {
        // 0~44 输入槽 + 46 的等价道具都要掉出来
        int[] all = new int[INPUT_SLOTS + 1];
        for (int i = 0; i < INPUT_SLOTS; i++) all[i] = i;
        all[INPUT_SLOTS] = CAP;
        return all;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    /** 物流接入（腐竹 2026-10-28）：0~44 是输入槽，允许其他附属往运输机里塞投料。 */
    @Override
    public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
        return flow == ItemTransportFlow.INSERT ? SLOTS_0_44.clone() : new int[0];
    }

}
