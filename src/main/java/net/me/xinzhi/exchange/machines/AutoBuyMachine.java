package net.me.xinzhi.exchange.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import net.me.xinzhi.exchange.core.ItemGuard;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.exchange.TradeCap;
import net.me.xinzhi.exchange.util.ChatInput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 自动买入机：左侧模板（最多 24 种）右侧输出。
 *
 * <p>腐竹 2026-10-26 改版（原方案「每 tick 只买一种、用 xz-idx 轮转」机器负担太高）：
 * <ul>
 *   <li><b>一次买入所有模板槽位</b>：每个周期把 24 个模板全部过一遍，不再轮转、不再往机器里
 *       记 {@code xz-idx}（记盘只有「一直在操作的物品」的单价缓存）；</li>
 *   <li><b>中间一列（第 4 列）是功能区</b>：13 槽放等价交换道具（原来放阈值的位子），
 *       买入必须按道具的显示上限买；22 槽是输入阈值 + 机主余额（合并显示）；</li>
 *   <li>数量 = 模板槽那一叠的数量，输出装不下就跳过这一种；余额低于阈值待机；只有机主在线才买；
 *       手续费 1%。</li>
 * </ul>
 */
public final class AutoBuyMachine extends AbstractMachine {

    private static final int[] TEMPLATES = new int[24];
    private static final int[] OUTPUTS = new int[24];

    static {
        for (int r = 0; r < 6; r++) {
            int base = r * 9;
            for (int c = 0; c < 4; c++) {
                TEMPLATES[r * 4 + c] = base + c;
                OUTPUTS[r * 4 + c] = base + 5 + c;
            }
        }
    }

    /** 中间那列：4 状态 / 13 等价道具（原阈值位）/ 22 阈值+余额 / 31 节拍 / 40 手续费 / 49 用法 */
    private static final int STATUS = 4, CAP_SLOT = 13, MIN = 22, RATE = 31, FEE = 40, HINT = 49;

    /** 周期计数放内存，别写 BlockStorage（腐竹 2026-10-26：机器别频繁记录） */
    private final Map<Location, Integer> counters = new HashMap<>();

    /**
     * 模板区解析结果最多隔这么多个周期重算一次（腐竹 2026-10-30：「4 个周期内即可」）。
     *
     * <p>为什么要缓存：原来每个周期都要对 24 个模板各做一次 {@code ItemKey.of}，而真正贵的是
     * 下面的输出格扫描。模板只可能在玩家动槽位 / 物流塞东西时变，4 个周期（默认 0.6 秒）内
     * 一定能发现，人眼看不出来。
     */
    private static final int TPL_REFRESH_CYCLES = 4;
    private final Map<Location, TplCache> tplCaches = new HashMap<>();
    private final Map<Location, Integer> tplTicks = new HashMap<>();
    /** 上周期画出去的状态：内容没变就不重画（省掉每次 2 个 CustomItemStack） */
    private final Map<Location, String> lastRender = new HashMap<>();

    /** 模板区解析结果：键为 TEMPLATES 的下标，空格 / 认不出的留 null */
    private static final class TplCache {
        final ItemKey[] keys = new ItemKey[TEMPLATES.length];
        final ItemStack[] tpls = new ItemStack[TEMPLATES.length];
        final int[] want = new int[TEMPLATES.length];
        final int[] maxStack = new int[TEMPLATES.length];
    }

    public AutoBuyMachine(ItemGroup group, SlimefunItemStack item,
                          RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                MachineUtil.setOwner(e.getBlock().getLocation(), e.getPlayer());
            }
        });
    }

    @Override
    protected String menuTitle() {
        return "§6自动买入机 §8· §7左模板 / 右输出";
    }

    @Override
    protected void initMenu(@Nonnull BlockMenuPreset preset) {
        for (int col = 0; col < 6; col++) {
            int slot = col * 9 + 4;
            // 13 槽是真的留空（连玻璃板都不放），玩家要把等价道具放进去
            if (slot == CAP_SLOT) continue;
            preset.addItem(slot, LOCKED_PANE, (p, s, item, action) -> false);
        }
        preset.addItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7待机"), (p, s, item, action) -> false);
        // 13 槽刻意不放任何菜单物品，直接空着给玩家放等价道具（腐竹 m00988 第 3 条）
        preset.addItem(MIN, new CustomItemStack(Material.GOLD_INGOT, "§6输入阈值 §8/ §6机主余额",
                "§7点击输入数字",
                "§7余额高于它才买，低于它待机"), (p, s, item, action) -> false);
        preset.addItem(RATE, new CustomItemStack(Material.PAPER, "§7节拍", "§7周期处理全部模板"), (p, s, item, action) -> false);
        preset.addItem(FEE, new CustomItemStack(Material.EXPERIENCE_BOTTLE, "§6手续费", "§71%"), (p, s, item, action) -> false);
        preset.addItem(HINT, new CustomItemStack(Material.CHEST, "§e用法",
                "§7左边 24 格放模板（目标物品）",
                "§7右边 24 格是输出",
                "§7一个周期买完所有模板（不轮转）",
                "§7只买 §f不超等价道具上限 §7的物品",
                "§8超上限的目标请用合成台"), (p, s, item, action) -> false);
    }

    @Override
    public void onMenuOpen(@Nonnull BlockMenu menu, @Nonnull Block b) {
        Location loc = b.getLocation();
        // 玩家打开界面 = 正准备动模板：下个周期强制重解析模板区
        tplCaches.remove(loc);
        lastRender.remove(loc);
        menu.addMenuClickHandler(MIN, (p, slot, item, action) -> {
            UUID owner = MachineUtil.ownerOrClaim(loc, p);
            if (owner == null || !owner.equals(p.getUniqueId())) {
                p.sendMessage("§c[买入机] 只有机主能改阈值");
                return false;
            }
            p.closeInventory();
            ChatInput.request(ExchangeService.get().plugin(), p,
                    "§e[买入机] 请输入余额阈值（数字，0 = 不设下限），输入 cancel 取消：",
                    msg -> {
                        try {
                            double v = Double.parseDouble(msg.trim());
                            MachineUtil.set(loc, "xz-min", String.valueOf(Math.max(0, v)));
                            p.sendMessage("§a[买入机] 阈值已设为 " + fmt(Math.max(0, v)));
                        } catch (Throwable t) {
                            p.sendMessage("§c[买入机] 这不是一个数字");
                        }
                    });
            return false;
        });
        render(menu, loc, "§7待机", 0);
    }

    @Override
    public void onTick(@Nonnull Block b, @Nonnull SlimefunItem item,
                       com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData data) {
        try {
            Location loc = b.getLocation();
            ExchangeService svc = ExchangeService.get();
            int interval = svc == null ? 20 : Math.max(1, svc.config().machineBuyInterval);
            int cd = counters.merge(loc, 1, Integer::sum);
            if (cd < interval) return;
            counters.put(loc, 0);
            buy(b);
        } catch (Throwable ignored) {
        }
    }

    private void buy(Block b) {
        Location loc = b.getLocation();
        BlockMenu menu = MachineDrops.menu(loc);
        if (menu == null) return;
        ExchangeService svc = ExchangeService.get();
        if (svc == null) return;

        UUID owner = MachineUtil.owner(loc);
        if (owner == null) {
            render(menu, loc, "§c未登记主人：由放置者打开一次", 0);
            return;
        }
        Player p = Bukkit.getPlayer(owner);
        if (p == null || !p.isOnline()) {
            render(menu, loc, "§7机主不在线，待机", 0);
            return;
        }

        double balance = svc.manager().balances().get(owner);
        double min = MachineUtil.doubleInfo(loc, "xz-min", svc.config().machineBuyMinBalance);
        if (balance < min) {
            render(menu, loc, "§7余额低于阈值 " + fmt(min) + "，待机", 0);
            return;
        }

        long cap = capOf(menu);
        if (cap == CAP_MISSING) {
            render(menu, loc, "§c未装等价道具，无法使用", 0);
            return;
        }
        // 一次拿整块界面快照：原来 room()/give() 对每个模板各扫一遍输出格，24 模板 × 48 格 = 上千次单格读
        ItemStack[] snap;
        try {
            snap = menu.getContents();
        } catch (Throwable t) {
            return;
        }
        if (snap == null) return;

        // 模板区：最多每 TPL_REFRESH_CYCLES 个周期重解析一次（腐竹 2026-10-30「4 个周期内即可」）
        TplCache cache = tplCaches.get(loc);
        int tick = tplTicks.merge(loc, 1, Integer::sum);
        if (cache == null || tick >= TPL_REFRESH_CYCLES) {
            cache = parseTemplates(snap);
            tplCaches.put(loc, cache);
            tplTicks.put(loc, 0);
        }

        Out out = new Out(snap);
        int bought = 0, skipped = 0;
        for (int i = 0; i < TEMPLATES.length; i++) {
            ItemKey k = cache.keys[i];
            if (k == null) continue;                        // 空格 / 认不出的模板
            ItemStack tpl = cache.tpls[i];
            int want = cache.want[i];
            int maxStack = cache.maxStack[i];
            // 模板刚被拿走：缓存最多还活 4 个周期，这里拦一下，别在人家撤掉目标后还继续买
            int tplSlot = TEMPLATES[i];
            if (tplSlot >= snap.length) continue;
            ItemStack live = snap[tplSlot];
            if (live == null || live.getType().isAir()) continue;

            double raw = MachineUtil.buyUnit(loc, k);
            if (!(raw > 0)) continue;                       // 无价：跳过
            if (TradeCap.blocks(cap, raw)) continue;        // 超道具上限：不买
            double unit = raw * (1 + MachineUtil.FEE);

            int fit = out.room(k, maxStack, want);
            if (fit <= 0) {
                skipped++;
                continue;                                   // 输出装不下：跳过这一种
            }

            int affordable = (int) Math.floor((balance - min) / unit);
            if (affordable <= 0) {
                out.flush(menu);
                render(menu, loc, "§7余额不够买一个，待机", bought);
                return;
            }
            int qty = Math.min(want, Math.min(fit, affordable));
            if (qty <= 0) continue;

            double cost = unit * qty;
            svc.manager().balances().add(owner, -cost);
            balance -= cost;
            out.give(k, tpl, maxStack, qty);
            bought += qty;
        }
        out.flush(menu);
        if (bought > 0) render(menu, loc, "§a买入 " + bought + " 件", bought);
        else render(menu, loc, skipped > 0 ? "§7输出满了 / 都超上限，待机" : "§7没有可买的目标，待机", 0);
    }

    /** 把 24 个模板槽解析成缓存（只在 template 区真的可能变了的时候调） */
    private static TplCache parseTemplates(ItemStack[] snap) {
        TplCache c = new TplCache();
        for (int i = 0; i < TEMPLATES.length; i++) {
            int slot = TEMPLATES[i];
            ItemStack tpl = slot < snap.length ? snap[slot] : null;
            if (tpl == null || tpl.getType().isAir()) continue;
            ItemKey k = ItemKey.of(tpl);
            if (k == null) continue;
            c.keys[i] = k;
            c.tpls[i] = tpl;
            c.want[i] = Math.max(1, tpl.getAmount());
            c.maxStack[i] = Math.max(1, tpl.getMaxStackSize());
        }
        return c;
    }

    /** 13 槽没放等价道具 = 整台机器不能用（腐竹 m00819）；口径统一在 {@link MachineUtil#capOf} */
    private static final long CAP_MISSING = MachineUtil.CAP_MISSING;

    private static long capOf(BlockMenu menu) {
        return MachineUtil.capOf(menu, CAP_SLOT);
    }

    /**
     * 输出区的本周期快照：{@code room()} 与 {@code give()} 都在这份本地副本上算，
     * 最后只把真的动过的格子写回界面。
     *
     * <p>2026-10-30 提速：原来每处理一个模板就要重新 {@code getItemInSlot} 扫两遍 24 格
     * （24 模板 × 48 格 ≈ 1150 次单格读），现在整块界面只读一次。
     */
    private static final class Out {

        private final ItemStack[] items = new ItemStack[OUTPUTS.length];
        private final boolean[] dirty = new boolean[OUTPUTS.length];
        /** 界面长度不够（老机器 27 格）时，超出范围的槽当作不存在 */
        private final int len;

        Out(ItemStack[] snap) {
            this.len = snap == null ? 0 : snap.length;
            for (int i = 0; i < OUTPUTS.length; i++) {
                items[i] = OUTPUTS[i] < len ? snap[OUTPUTS[i]] : null;
            }
        }

        /** 该格是不是「同款且原样」 */
        private static boolean same(ItemKey k, ItemStack it) {
            if (it == null || it.getType().isAir()) return false;
            ItemKey other = ItemKey.of(it);
            // 没有 ItemMeta 的原版物品必然是原样，省一次模板深比较
            return other != null && other.equals(k) && (!it.hasItemMeta() || ItemGuard.pristine(k, it));
        }

        /** 输出槽还能放下多少个这种物品（同款先叠，再找空格） */
        int room(ItemKey k, int maxStack, int max) {
            int room = 0;
            for (int i = 0; i < items.length; i++) {
                if (OUTPUTS[i] >= len) continue;
                ItemStack it = items[i];
                if (it == null || it.getType().isAir()) {
                    room += maxStack;
                } else if (same(k, it)) {
                    room += Math.max(0, maxStack - it.getAmount());
                }
                if (room >= max) return max;
            }
            return room;
        }

        void give(ItemKey k, ItemStack tpl, int maxStack, int qty) {
            int left = qty;
            // 先叠到同款上
            for (int i = 0; i < items.length && left > 0; i++) {
                if (OUTPUTS[i] >= len) continue;
                ItemStack it = items[i];
                if (it == null || it.getType().isAir() || !same(k, it)) continue;
                int add = Math.min(left, maxStack - it.getAmount());
                if (add <= 0) continue;
                ItemStack copy = it.clone();
                copy.setAmount(it.getAmount() + add);
                items[i] = copy;
                dirty[i] = true;
                left -= add;
            }
            // 再放空格
            for (int i = 0; i < items.length && left > 0; i++) {
                if (OUTPUTS[i] >= len) continue;
                ItemStack it = items[i];
                if (it != null && !it.getType().isAir()) continue;
                int add = Math.min(left, maxStack);
                ItemStack copy = tpl.clone();
                copy.setAmount(add);
                items[i] = copy;
                dirty[i] = true;
                left -= add;
            }
        }

        /** 只写回动过的格子 */
        void flush(BlockMenu menu) {
            for (int i = 0; i < dirty.length; i++) {
                if (!dirty[i]) continue;
                menu.replaceExistingItem(OUTPUTS[i], items[i]);
                dirty[i] = false;
            }
        }
    }

    private void render(BlockMenu menu, Location loc, String status, int bought) {
        if (menu == null) return;
        ExchangeService svc = ExchangeService.get();
        UUID owner = MachineUtil.owner(loc);
        double balance = svc != null && owner != null ? svc.manager().balances().get(owner) : 0;
        double min = MachineUtil.doubleInfo(loc, "xz-min",
                svc == null ? 0 : svc.config().machineBuyMinBalance);
        long cap = capOf(menu);
        // 内容没变就不重画（腐竹 2026-10-30 降载）：省掉每周期 2 个带 lore 的 CustomItemStack
        String ownerName = MachineUtil.ownerName(owner);
        String key = cap + "|" + status + "|" + bought + "|" + balance + "|" + min + "|" + ownerName;
        if (key.equals(lastRender.get(loc))) return;
        lastRender.put(loc, key);
        try {
            if (cap == CAP_MISSING) {
                ItemStack s13 = menu.getItemInSlot(CAP_SLOT);
                boolean empty = s13 == null || s13.getType().isAir();
                menu.replaceExistingItem(STATUS, new CustomItemStack(Material.BARRIER,
                        empty ? "§c未检测到等价交换道具请在下方放置" : "§c第 13 槽里的物品不是等价交换道具",
                        empty ? "§7把等价交换道具放进第 13 槽" : "§7换成初级 / 中级等价交换道具",
                        "§8放之前整台机器待机"));
            } else {
                menu.replaceExistingItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态",
                        "§7" + status,
                        bought > 0 ? "§7本次买入 §f" + bought + " §7件" : "§7—"));
            }
            menu.replaceExistingItem(MIN, new CustomItemStack(Material.GOLD_INGOT,
                    "§6输入阈值 §f" + fmt(min),
                    "§6机主余额 §f" + fmt(balance),
                    "§7机主 §f" + ownerName,
                    "§7点击输入阈值（只有机主能改）"));
        } catch (Throwable ignored) {
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    @Override
    protected int[] storageSlotsForDrop() {
        int[] all = new int[TEMPLATES.length + OUTPUTS.length + 1];
        System.arraycopy(TEMPLATES, 0, all, 0, TEMPLATES.length);
        System.arraycopy(OUTPUTS, 0, all, TEMPLATES.length, OUTPUTS.length);
        all[all.length - 1] = CAP_SLOT;   // 等价道具掉出来
        return all;
    }

    /** 物流接入（腐竹 2026-10-28）：右边 4 列是输出槽，其他附属的运输/物流设备可以从这里取货。 */
    @Override
    public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
        return flow == ItemTransportFlow.WITHDRAW ? OUTPUTS.clone() : new int[0];
    }

}
