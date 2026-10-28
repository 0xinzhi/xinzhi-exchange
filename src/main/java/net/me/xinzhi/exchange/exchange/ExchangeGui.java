package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.menu.MenuChannel;
import net.me.xinzhi.exchange.menu.MenuModel;
import net.me.xinzhi.exchange.menu.SlotRole;
import net.me.xinzhi.exchange.price.PriceEngine;
import net.me.xinzhi.exchange.recipe.Recipe;
import net.me.xinzhi.exchange.util.Dialogs;
import net.me.xinzhi.exchange.util.GuiItems;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 兑换界面（主清单 + 物品详情 / 材料链）。
 *
 * <h2>两种清单（腐竹 2026-10-18）</h2>
 * <ul>
 *   <li><b>我的</b>（默认）：只列<b>玩家自己卖过</b>的物品（腐竹 2026-10-27：
 *       原来是「卖过 / 预览过」，改成只认卖过 —— 买过、点开看过都不算记录）；</li>
 *   <li><b>搜索</b>：输入关键词后找。⚠ 2026-10-18 第五轮后：<b>普通玩家只搜自己的清单</b>，
 *       只有 {@code xzex.viewall}（默认 op）才搜全库；「一点点填充」的规则不变。</li>
 * </ul>
 *
 * <h2>版面</h2>
 * <p>没有客户端 mod：54 格 = 5 行物品（45）+ 1 行导航（9）。</p>
 * <p>有客户端 mod：63 格 = 6 行物品（54）+ 1 行导航（9），由 {@link MenuChannel} 下发；
 * 没握手 / 玩家关掉开关 / 编码失败时自动回退 54 格。</p>
 *
 * <p>⚠ 第八轮（2026-10-18）：原来占一整行的 9 段跳页滑条已经撤掉
 * （腐竹：「跳页的滑条应该在跳页界面的输入框位置」）——腾出来的 9 格给物品，
 * 跳页改成点页码格弹「滑条 + 输入框」。</p>
 */
public final class ExchangeGui {

    public static final int COLS = 9;
    public static final int VANILLA_ROWS = 5;                     // 物品行数（无 mod）
    public static final int MOD_ROWS = 6;                         // 物品行数（有 mod）
    public static final int GRID_VANILLA = COLS * VANILLA_ROWS;   // 45
    public static final int GRID_MOD = COLS * MOD_ROWS;           // 54
    public static final int NAV_SLOTS = 9;
    public static final int VANILLA_SIZE = GRID_VANILLA + NAV_SLOTS; // 54
    public static final int MOD_SIZE = GRID_MOD + NAV_SLOTS;         // 63

    /** 导航栏内序号（相对 navStart） */
    public static final int NAV_PREV = 0;
    public static final int NAV_PAGE = 1;
    public static final int NAV_SEARCH = 2;
    public static final int NAV_SORT = 3;
    public static final int NAV_BALANCE = 4;
    public static final int NAV_REFRESH = 5;
    public static final int NAV_NEXT = 6;
    public static final int NAV_MODE = 7;
    public static final int NAV_CLOSE = 8;

    /** 详情界面的固定格（仍按 54 格） */
    public static final int SLOT_DETAIL_TARGET = 4;
    public static final int SLOT_DETAIL_MACHINE = 13;
    public static final int DETAIL_INPUT_START = 18;   // 18..26
    public static final int SLOT_DETAIL_PRICE = 31;
    public static final int DETAIL_CAND_START = 36;    // 36..44
    public static final int SLOT_DETAIL_FAV = 48;       // 收藏开关（腐竹 2026-10-28）
    public static final int SLOT_DETAIL_MATERIALS = 49;  // 材料抵扣换出（腐竹 2026-10-19）
    public static final int SLOT_DETAIL_BACK = 45;
    public static final int SLOT_DETAIL_CLOSE = 53;

    public enum Mode {
        MINE("我的"), SEARCH("搜索结果");
        public final String label;
        Mode(String label) { this.label = label; }
        public Mode other() { return this == MINE ? SEARCH : MINE; }
    }

    /** 腐竹 2026-10-28：排序只要三种 —— 低→高 / 高→低 / 收藏（收藏模式只列收藏过的） */
    public enum Sort {
        PRICE_ASC("价格 低→高"), PRICE_DESC("价格 高→低"), FAVORITE("收藏");

        public final String label;
        Sort(String label) { this.label = label; }

        public Sort next() { return values()[(ordinal() + 1) % values().length]; }

        public static Sort parse(String s, Sort fallback) {
            if (s == null) return fallback;
            for (Sort v : values()) if (v.name().equalsIgnoreCase(s)) return v;
            return fallback;
        }
    }

    /** 一次界面打开时的全部状态（原版 holder 与 mod 回调共用）。 */
    public static final class MenuState {
        public final int page;
        public final int pages;
        public final Sort sort;
        public final String keyword;
        public final Mode mode;
        public final int grid;
        public final int rows;
        public final boolean mod;
        /** 显示上限（等价终端分阶）：只过滤清单与买，卖出不看它 */
        public final long cap;

        MenuState(int page, int pages, Sort sort, String keyword, Mode mode,
                  int grid, int rows, boolean mod, long cap) {
            this.page = page;
            this.pages = pages;
            this.sort = sort;
            this.keyword = keyword;
            this.mode = mode;
            this.grid = grid;
            this.rows = rows;
            this.mod = mod;
            this.cap = cap;
        }
    }

    public static final class MainHolder implements InventoryHolder {
        public final MenuState state;
        public final int navStart;
        /** 当前页上真正显示的物品（槽位 0..grid-1 依次对应） */
        public List<ItemKey> visible = new ArrayList<>();
        private Inventory inv;

        MainHolder(MenuState state, int navStart) {
            this.state = state;
            this.navStart = navStart;
        }

        public void inv(Inventory inv) { this.inv = inv; }
        @Override public Inventory getInventory() { return inv; }
    }

    public static final class DetailHolder implements InventoryHolder {
        public final java.util.UUID player;
        public ItemKey target;
        public final int backPage;
        public final Sort backSort;
        public final String backKeyword;
        public final Mode backMode;
        public final long backCap;
        private Inventory inv;

        DetailHolder(java.util.UUID player, ItemKey target, int backPage, Sort backSort,
                     String backKeyword, Mode backMode, long backCap) {
            this.player = player; this.target = target; this.backPage = backPage;
            this.backSort = backSort; this.backKeyword = backKeyword; this.backMode = backMode;
            this.backCap = backCap;
        }

        public void inv(Inventory inv) { this.inv = inv; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final JavaPlugin plugin;
    private final ExchangeManager manager;
    private final PlayerViewStore store;

    public ExchangeGui(JavaPlugin plugin, ExchangeManager manager, PlayerViewStore store) {
        this.plugin = plugin;
        this.manager = manager;
        this.store = store;
    }

    public ExchangeManager manager() { return manager; }
    public PlayerViewStore store() { return store; }

    // ============================================================
    // 主界面
    // ============================================================

    public void open(Player p, int page, Sort sort, String keyword) {
        open(p, page, sort, keyword, Mode.MINE, TradeCap.UNLIMITED);
    }

    /**
     * 这次打开要不要用超级菜单：客户端握手成功，<b>并且</b>玩家没在 players.yml
     * 里关掉（{@code /xzex menu off}，腐竹 2026-10-18 第七轮）。
     */
    public boolean useMod(Player p) {
        return p != null && MenuChannel.isHandshaked(p) && store.modEnabled(p.getUniqueId());
    }

    /** 能不能直接浏览全库（腐竹 2026-10-18：只准 op） */
    public static boolean canViewAll(Player p) {
        return p != null && p.hasPermission("xzex.viewall");
    }

    public void open(Player p, int page, Sort sort, String keyword, Mode mode) {
        open(p, page, sort, keyword, mode, TradeCap.UNLIMITED);
    }

    /** 带显示上限地打开（等价终端分阶：普通命令/管理员走 {@link TradeCap#UNLIMITED}） */
    public void open(Player p, int page, Sort sort, String keyword, Mode mode, long cap) {
        Mode md = mode == null ? Mode.MINE : mode;
        String kw = normalize(keyword);
        // 普通玩家没有「全库搜索」，但可以搜自己的清单（腐竹 2026-10-18 本轮）
        if (md == Mode.SEARCH && kw == null && !canViewAll(p)) md = Mode.MINE;

        // 装了 mod、握手成功、且没在 /xzex menu 里关掉 → 先试 mod 版
        if (useMod(p)) {
            int pages = pages(p, sort, kw, md, GRID_MOD, cap);
            int pg = clamp(page, pages);
            if (openMod(p, pg, pages, sort, kw, md, cap)) return;
        }
        int pages = pages(p, sort, kw, md, GRID_VANILLA, cap);
        int pg = clamp(page, pages);
        openVanilla(p, pg, pages, sort, kw, md, cap);
    }

    private static String normalize(String keyword) {
        if (keyword == null) return null;
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        return k.isEmpty() ? null : k;
    }

    private static int clamp(int page, int pages) {
        return Math.max(0, Math.min(page, Math.max(1, pages) - 1));
    }

    private String title(Mode mode, int page, int pages, Player p) {
        String label = (mode == Mode.SEARCH && !canViewAll(p)) ? "搜索结果（我的）" : mode.label;
        return "§8等价交换 §7· " + label + " §7· 第 " + (page + 1) + "/" + pages + " 页";
    }

    // ---------------- 原版 54 格 ----------------

    private void openVanilla(Player p, int page, int pages, Sort sort, String keyword, Mode mode, long cap) {
        int grid = GRID_VANILLA;
        int navStart = grid;
        MenuState st = new MenuState(page, pages, sort, keyword, mode, grid, VANILLA_ROWS, false, cap);
        MainHolder holder = new MainHolder(st, navStart);
        Inventory inv = Bukkit.createInventory(holder, VANILLA_SIZE, title(mode, page, pages, p));
        holder.inv(inv);

        List<ItemKey> list = listFor(p, sort, keyword, mode, cap);
        int start = page * grid;
        List<ItemKey> slice = new ArrayList<>();
        for (int i = 0; i < grid && start + i < list.size(); i++) {
            ItemKey key = list.get(start + i);
            slice.add(key);
            inv.setItem(i, icon(p, key));
        }
        holder.visible = slice;

        if (list.isEmpty() && mode == Mode.MINE) {
            inv.setItem(13, GuiItems.of(Material.BOOK, "§7这里还是空的",
                    "§7在背包里 §fShift+左键 §7物品 = 卖掉",
                    "§7把物品拿在光标上 §f点格子 §7= 卖掉",
                    "§8（清单只记录你卖过的物品，买过 / 看过不算）"));
        }

        for (int i = 0; i < NAV_SLOTS; i++) {
            inv.setItem(navStart + i, navIcon(p, st, i, list.size()));
        }
        p.openInventory(inv);
    }

    // ---------------- 超大菜单（mod） ----------------

    private boolean openMod(Player p, int page, int pages, Sort sort, String keyword, Mode mode, long cap) {
        MenuModel menu;
        try {
            menu = new MenuModel(title(mode, page, pages, p), COLS, MOD_ROWS + 2);
        } catch (Throwable t) {
            return false;
        }

        MenuState st = new MenuState(page, pages, sort, keyword, mode, GRID_MOD, MOD_ROWS, true, cap);
        for (int i = 0; i < MOD_SIZE; i++) {
            menu.set(i, SlotRole.EMPTY, null);
        }

        List<ItemKey> list = listFor(p, sort, keyword, mode, cap);
        int start = page * GRID_MOD;
        for (int i = 0; i < GRID_MOD && start + i < list.size(); i++) {
            ItemKey key = list.get(start + i);
            menu.set(i, SlotRole.BUTTON, icon(p, key));
            final ItemKey clickedKey = key;
            menu.onClick(i, click ->
                    itemClicked(p, st, clickedKey, click.button(), click.shift()));
        }

        int navStart = GRID_MOD;
        for (int i = 0; i < NAV_SLOTS; i++) {
            menu.set(navStart + i, SlotRole.BUTTON, navIcon(p, st, i, list.size()));
            final int nav = i;
            menu.onClick(navStart + i, click -> navClicked(p, st, nav));
        }
        return MenuChannel.open(p, menu);
    }

    // ============================================================
    // 点击动作（原版与 mod 共用）
    // ============================================================

    /** 点物品格：左键换出 1 / Shift+左键数量 / 右键卖光标 / Shift+右键材料链。 */
    public void itemClicked(Player p, MenuState st, ItemKey key, int button, boolean shift) {
        // ★ 光标上有可识别物品 = 先卖它（原版界面在 listener 里做同一件事；mod 界面没有
        //   InventoryClickEvent，所以这里补一次，否则拿在手上的物品点商品格会变成"买"）
        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir() && ItemKey.of(cursor) != null) {
            sellCursor(p, st);
            return;
        }
        if (button == 0) {
            if (TradeCap.blocks(st.cap, manager.engine().priceOf(key))) {
                p.sendMessage("§c[等价交换] 这台终端只显示 §e" + Text.num(st.cap)
                        + " §cEMC 以下的物品（潜行右键终端可升级上限）");
                reopen(p, st);
                return;
            }
            if (shift) {
                Dialogs.amount(plugin, p,
                        "§8✦ 换出数量 ✦",
                        "§7" + Text.plain(key.getDisplayName())
                                + " §8（单价 " + Text.num(manager.buyUnit(key))
                                + " · 余额 " + Text.num(manager.balance(p.getUniqueId())) + "）",
                        1, 1, manager.config().quickAmountMax, 0,
                        () -> reopen(p, st),
                        n -> {
                            ExchangeResult r = manager.buy(p, key, n);
                            p.sendMessage(r.ok
                                    ? "§a换出 §f" + r.amount + " × " + key.getDisplayName()
                                      + " §7(-" + Text.num(r.total) + ")"
                                    : "§c" + r.message);
                            reopen(p, st);
                        });
                return;
            }
            ExchangeResult r = manager.buy(p, key, 1);
            if (!r.ok) p.sendMessage("§c[等价交换] " + r.message);
            reopen(p, st);
            return;
        }
        if (button == 1) {
            if (shift) {
                openDetail(p, key, st.page, st.sort, st.keyword, st.mode, st.cap);
                return;
            }
            // 腐竹 2026-10-28：右键 = 快捷买 64 次，钱/空间不够就尽量多买
            ExchangeResult r = manager.buyUpTo(p, key, 64);
            if (r.ok) {
                p.sendMessage("§a换出 §f" + r.amount + " × " + key.getDisplayName()
                        + " §7(-" + Text.num(r.total) + ")");
            } else {
                p.sendMessage("§c[等价交换] " + r.message);
            }
            reopen(p, st);
        }
    }

    /**
     * 换入（交物品换价值）：光标上那一叠。
     *
     * <p>数量 &gt; 1 时先弹「滑条 + 输入框」让玩家选数量 —— 腐竹 2026-10-18 第七轮
     * 「对于换入，给予上限为128的滑条供快速选择」。只有 1 个就不弹，直接卖。
     */
    public void sellCursor(Player p, MenuState st) {
        ItemStack cursor = p.getItemOnCursor();
        ItemKey cursorKey = ItemKey.of(cursor);
        if (cursorKey == null) {
            p.sendMessage("§7[等价交换] 先把要换入的物品拿到光标上");
            reopen(p, st);
            return;
        }
        if (!manager.canExchange(cursorKey)) {
            p.sendMessage("§c[等价交换] " + cursorKey.getDisplayName() + " §c没有价格，不收");
            reopen(p, st);
            return;
        }
        if (manager.overLimit(cursorKey)) {
            p.sendMessage("§c[等价交换] " + cursorKey.getDisplayName() + " §c"
                    + manager.limitMessage(cursorKey) + "§7（物品不会被扣除）");
            reopen(p, st);
            return;
        }
        int available = cursor.getAmount();
        // 超过这台终端的显示上限：卖出去之后就买不回来了 —— 腐竹 2026-10-27
        // 「超过上限的物品卖出要弹出输入框类型的大按键来进行二次确认」
        if (st != null && st.cap != TradeCap.UNLIMITED
                && TradeCap.blocks(st.cap, manager.engine().priceOf(cursorKey))) {
            openSellConfirm(p, st, cursorKey, available);
            return;
        }
        if (available <= 1) {
            sellCursorNow(p, st, 1);
            return;
        }
        Dialogs.amount(plugin, p,
                "§8✦ 换入数量 ✦",
                "§7" + Text.plain(cursorKey.getDisplayName())
                        + " §8（单价 " + Text.num(manager.sellUnit(cursorKey))
                        + " · 手里 " + available + " 个）",
                available, 1, manager.config().quickAmountMax, available,
                null,
                n -> sellCursorNow(p, st, n));
    }

    /**
     * 卖出「超出这台终端显示上限」的物品：同一个输入框里放滑条 + 精确数量，
     * 底部一个 260 宽的大按钮（{@code §c确认卖出}）。取消就原样回界面，什么都不扣。
     */
    public void openSellConfirm(Player p, MenuState st, ItemKey key, int available) {
        double price = manager.engine().priceOf(key);
        long cap = st == null ? TradeCap.UNLIMITED : st.cap;
        List<String> body = new ArrayList<>();
        body.add("§7单价 §e" + Text.num(price) + " EMC §7· 这台终端只显示 §e"
                + Text.num(cap) + " §7EMC 以下的物品");
        body.add("§c卖掉以后，你就没法从这个清单里再买回它了。");
        Dialogs.amountConfirm(plugin, p,
                "§c§l确认卖出（超出显示上限）",
                "§7" + Text.plain(key.getDisplayName()) + " §8· 手里 " + available + " 个",
                body, "§c确认卖出",
                available, 1, manager.config().quickAmountMax, available,
                () -> reopen(p, st),
                n -> sellCursorNow(p, st, n));
    }

    private void sellCursorNow(Player p, MenuState st, long amount) {
        ItemKey cursorKey = ItemKey.of(p.getItemOnCursor());
        ExchangeResult r = manager.sellCursor(p, amount);
        if (r.ok && cursorKey != null) {
            store.markSold(p.getUniqueId(), cursorKey);
            p.sendMessage("§a换入 §f" + r.amount + " × " + cursorKey.getDisplayName()
                    + " §7(+" + Text.num(r.total) + ")");
        } else if (!r.ok) {
            p.sendMessage("§c[等价交换] " + r.message);
        }
        reopen(p, st);
    }

    /** 点导航栏。 */
    public void navClicked(Player p, MenuState st, int index) {
        switch (index) {
            case NAV_PREV -> open(p, st.page - 1, st.sort, st.keyword, st.mode, st.cap);
            case NAV_NEXT -> open(p, st.page + 1, st.sort, st.keyword, st.mode, st.cap);
            case NAV_SORT -> open(p, st.page, st.sort.next(), st.keyword, st.mode, st.cap);
            case NAV_BALANCE -> { /* 信息格 */ }
            case NAV_REFRESH -> {
                // 腐竹 2026-10-28：刷新价表改成管理员功能 + 全服 2 小时冷却
                if (!p.hasPermission("xzex.admin") && !p.isOp()) {
                    p.sendMessage("§c[等价交换] 刷新价表是管理员功能");
                    open(p, st.page, st.sort, st.keyword, st.mode, st.cap);
                    return;
                }
                ExchangeService svc = ExchangeService.get();
                long left = svc == null ? 0L : svc.refreshCooldownLeft();
                if (left > 0) {
                    p.sendMessage("§c[等价交换] 价表冷却中，还需 §f" + ExchangeService.fmtCooldown(left)
                            + " §7（全服共用）");
                    open(p, st.page, st.sort, st.keyword, st.mode, st.cap);
                    return;
                }
                if (svc != null) {
                    svc.rebuildPrices();
                    svc.markManualRefresh();
                }
                p.sendMessage("§a[等价交换] 价表已重建");
                open(p, st.page, st.sort, st.keyword, st.mode, st.cap);
            }
            case NAV_CLOSE -> close(p);
            case NAV_PAGE -> {
                int pages = pages(p, st.sort, st.keyword, st.mode, st.grid, st.cap);
                if (pages <= 1) {
                    p.sendMessage("§7[等价交换] 只有 1 页，不用跳");
                    return;
                }
                Dialogs.amount(plugin, p,
                        "§8✦ 跳页 ✦", "§7跳到第几页（共 " + pages + " 页）",
                        st.page + 1, 1, pages, pages,
                        () -> reopen(p, st),
                        n -> open(p, (int) n - 1, st.sort, st.keyword, st.mode, st.cap));
            }
            case NAV_SEARCH -> openSearch(p, st);
            case NAV_MODE -> toggleMode(p, st);
            default -> { }
        }
    }

    private void openSearch(Player p, MenuState st) {
        boolean all = canViewAll(p);
        Dialogs.text(plugin, p,
                all ? "§8✦ 搜索全部物品 ✦" : "§8✦ 搜索我的物品 ✦",
                all ? "§7输入关键词（匹配名称或 id；留空 = 清除）"
                    : "§7只在你卖过的清单里找（留空 = 清除）",
                st.keyword == null ? "" : st.keyword, 48,
                () -> reopen(p, st),
                kw -> {
                    String k = normalize(kw);
                    open(p, 0, st.sort, k, k == null ? Mode.MINE : Mode.SEARCH, st.cap);
                });
    }

    private void toggleMode(Player p, MenuState st) {
        if (!canViewAll(p)) {
            if (st.keyword != null) {
                open(p, 0, st.sort, null, Mode.MINE, st.cap);
            } else {
                p.sendMessage("§7[等价交换] 搜索只在自己的清单里；点 §f搜索 §7输入关键词");
            }
            return;
        }
        Mode next = st.mode.other();
        open(p, 0, st.sort, next == Mode.MINE ? null : st.keyword, next, st.cap);
    }

    private void close(Player p) {
        MenuChannel.close(p);
        p.closeInventory();
    }

    public void reopen(Player p, MenuState st) {
        open(p, st.page, st.sort, st.keyword, st.mode, st.cap);
    }

    // ============================================================
    // 列表 / 页数
    // ============================================================

    public int pages(Sort sort, String keyword, Mode mode) {
        return pages(null, sort, keyword, mode, GRID_VANILLA, TradeCap.UNLIMITED);
    }

    public int pages(Player p, Sort sort, String keyword, Mode mode) {
        int grid = useMod(p) ? GRID_MOD : GRID_VANILLA;
        return pages(p, sort, keyword, mode, grid, TradeCap.UNLIMITED);
    }

    private int pages(Player p, Sort sort, String keyword, Mode mode, int grid, long cap) {
        int n = listOf(manager, store, p, sort, keyword, mode, cap).size();
        return Math.max(1, (n + grid - 1) / grid);
    }

    /** 当前视图 + 关键词下的物品（已排序） */
    public List<ItemKey> listFor(Player p, Sort sort, String keyword, Mode mode) {
        return listOf(manager, store, p, sort, keyword, mode, TradeCap.UNLIMITED);
    }

    public List<ItemKey> listFor(Player p, Sort sort, String keyword, Mode mode, long cap) {
        return listOf(manager, store, p, sort, keyword, mode, cap);
    }

    private static List<ItemKey> listOf(ExchangeManager manager, PlayerViewStore store, Player p,
                                        Sort sort, String keyword, Mode mode, long cap) {
        // 只有 op 的 SEARCH 才是全库；其余（MINE、普通玩家的 SEARCH）都是个人清单
        boolean global = mode == Mode.SEARCH && canViewAll(p);
        List<ItemKey> base = (p != null && !global)
                ? store.personal(p.getUniqueId())
                : manager.catalog();
        List<ItemKey> list = new ArrayList<>(base);
        if (keyword != null && !keyword.isEmpty()) {
            String kw = keyword.toLowerCase(Locale.ROOT);
            list.removeIf(k -> !(k.getDisplayName().toLowerCase(Locale.ROOT).contains(kw)
                    || k.toString().toLowerCase(Locale.ROOT).contains(kw)));
        }
        // 显示上限：只挡买（清单），卖出路径不看这里
        if (cap != TradeCap.UNLIMITED) {
            list.removeIf(k -> TradeCap.blocks(cap, manager.engine().priceOf(k)));
        }
        if (sort == Sort.FAVORITE) {
            java.util.Set<ItemKey> favs = (p == null)
                    ? java.util.Set.of()
                    : new java.util.HashSet<>(store.favorites(p.getUniqueId()));
            list.removeIf(k -> !favs.contains(k));
        }
        switch (sort) {
            case PRICE_ASC, FAVORITE -> list.sort(Comparator.comparingDouble(manager::buyUnit)
                    .thenComparing(ItemKey::getDisplayName));
            default -> list.sort(Comparator.comparingDouble((ItemKey k) -> -manager.buyUnit(k))
                    .thenComparing(ItemKey::getDisplayName));
        }
        return list;
    }

    /** 保留旧名字（别的类还在用） */
    public List<ItemKey> filtered(Sort sort, String keyword) {
        return listOf(manager, store, null, sort, keyword, Mode.SEARCH, TradeCap.UNLIMITED);
    }

    // ============================================================
    // 图标
    // ============================================================

    public ItemStack balance(Player p) {
        return GuiItems.of(Material.SUNFLOWER,
                "§6价值: §e" + Text.num(manager.balance(p.getUniqueId())),
                "§7左键物品 = 换出 1 个（花价值）",
                "§7Shift+左键 = 自定义数量",
                "§7右键物品 = 把光标上的同类物品换入",
                "§7光标上有物品时 §f点格子 §7= 卖掉它",
                "§7背包里 §fShift+左键 §7= 卖掉那一叠",
                "§7Shift+右键 = 看材料链",
                "§8/xzex menu = 开关超级菜单（客户端装 mod 时）");
    }

    public ItemStack icon(Player p, ItemKey key) {
        ItemStack base = key.toTemplate();
        if (base == null) base = new ItemStack(Material.PAPER);
        ItemStack out = base.clone();
        out.setAmount(1);
        ItemMeta meta = out.getItemMeta();
        if (meta == null) return out;
        String name = Text.plain(key.getDisplayName());
        meta.setDisplayName("§f" + name);

        List<String> lore = new ArrayList<>();
        lore.add("§8" + key);
        lore.add("");
        if (manager.overLimit(key)) {
            lore.add("§7单价: §c" + Text.num(manager.engine().priceOf(key))
                    + " §c超过兑换上限 " + Text.num(manager.tradeMax()) + "，不能交易");
        } else {
            double buy = manager.buyUnit(key);
            lore.add("§7换出（花价值）: §e" + Text.num(buy) + " §7/ 个");
            lore.add("§7换入（得价值）: §a" + Text.num(manager.sellUnit(key)) + " §7/ 个");
        }
        PriceEngine.Derivation dv = manager.engine().derive(key);
        if (dv != null && dv.recipe != null) {
            lore.add("§8来源: " + dv.machine.getDisplayName());
        } else {
            lore.add("§8来源: 基础价（定价表）");
        }
        if (p != null && store.sold(p.getUniqueId(), key)) lore.add("§8你卖过这个物品");
        lore.add("");
        lore.add("§e左键 §7换出 1 个 · §eShift+左键 §7数量");
        lore.add("§a右键 §7换入光标上的同类物品");
        lore.add("§bShift+右键 §7材料链（详情页可用材料抵扣换出）");
        meta.setLore(lore);
        out.setItemMeta(meta);
        return out;
    }

    /** 「刷新价表」全服冷却长度的可读文本（0 = 不限制） */
    private String cooldownLabel() {
        int sec = plugin.getConfig().getInt("terminal.refresh-cooldown-seconds", 7200);
        return sec <= 0 ? "不限制" : ExchangeService.fmtCooldown(sec * 1000L);
    }

    private ItemStack navIcon(Player p, MenuState st, int index, int total) {
        return switch (index) {
            case NAV_PREV -> GuiItems.of(Material.ARROW, "§e上一页",
                    "§7第 " + (st.page + 1) + " / " + st.pages + " 页");
            case NAV_NEXT -> GuiItems.of(Material.SPECTRAL_ARROW, "§e下一页",
                    "§7第 " + (st.page + 1) + " / " + st.pages + " 页");
            case NAV_PAGE -> GuiItems.of(Material.PAPER,
                    "§7第 §f" + (st.page + 1) + " §7/ §f" + st.pages + " §7页 · 共 §f" + total + " §7件",
                    "§8点一下可直接跳页（滑条 / 输入框）",
                    "§8视图: " + (st.mode == Mode.SEARCH && !canViewAll(p) ? "搜索结果（我的）" : st.mode.label),
                    "§8搜索: " + (st.keyword == null ? "无" : st.keyword),
                    "§8排序: " + st.sort.label,
                    "§8显示上限: " + (st.cap == TradeCap.UNLIMITED ? "不限" : Text.num(st.cap) + " EMC"));
            case NAV_SEARCH -> canViewAll(p)
                    ? GuiItems.of(Material.COMPASS, "§b搜索全部物品",
                        "§7输入关键词匹配名称或 id",
                        "§7搜到的物品点一下 = 看详情",
                        "§7输入 §fcancel §7清除搜索")
                    : GuiItems.of(Material.COMPASS, "§b搜索我的物品",
                        "§7只在你卖过的清单里找",
                        "§7搜到的物品点一下 = 看详情",
                        "§7输入 §fcancel §7清除搜索");
            case NAV_SORT -> GuiItems.of(Material.HOPPER, "§b排序: §f" + st.sort.label,
                    "§7点一下换一种排序");
            case NAV_BALANCE -> balance(p);
            case NAV_REFRESH -> {
                if (!p.hasPermission("xzex.admin") && !p.isOp()) {
                    yield GuiItems.of(Material.EMERALD, "§a刷新价表",
                            "§c需要管理员权限 §f xzex.admin");
                }
                ExchangeService svc = ExchangeService.get();
                long left = svc == null ? 0L : svc.refreshCooldownLeft();
                yield left > 0
                        ? GuiItems.of(Material.EMERALD, "§7刷新价表",
                            "§c冷却中 · 还需 §f" + ExchangeService.fmtCooldown(left),
                            "§8全服共用，重启也不会清零")
                        : GuiItems.of(Material.EMERALD, "§a刷新价表",
                            "§7重建价格后重新打开（管理员）",
                            "§8全服冷却 " + cooldownLabel());
            }
            case NAV_MODE -> canViewAll(p)
                    ? GuiItems.of(Material.CHEST,
                        "§b视图: §f" + st.mode.label,
                        "§7点一下切到「" + st.mode.other().label + "」",
                        "§8我的 = 只显示自己卖过的物品",
                        "§8搜索结果 = 全库关键词匹配")
                    : GuiItems.of(Material.CHEST,
                        "§7视图: §f" + (st.mode == Mode.SEARCH ? "搜索结果（我的）" : "我的"),
                        "§8搜索只在自己的清单里",
                        "§8清单只靠卖东西一点点填起来");
            case NAV_CLOSE -> GuiItems.of(Material.BARRIER, "§c关闭");
            default -> GuiItems.of(Material.GRAY_STAINED_GLASS_PANE, "§8—");
        };
    }

    // ============================================================
    // 详情 / 材料链
    // ============================================================

    public void openDetail(Player p, ItemKey target, int backPage, Sort backSort, String backKeyword) {
        openDetail(p, target, backPage, backSort, backKeyword, Mode.MINE, TradeCap.UNLIMITED);
    }

    public void openDetail(Player p, ItemKey target, int backPage, Sort backSort,
                           String backKeyword, Mode backMode) {
        openDetail(p, target, backPage, backSort, backKeyword, backMode, TradeCap.UNLIMITED);
    }

    public void openDetail(Player p, ItemKey target, int backPage, Sort backSort,
                           String backKeyword, Mode backMode, long backCap) {
        DetailHolder holder = new DetailHolder(p.getUniqueId(), target, backPage, backSort,
                backKeyword, backMode, backCap);
        Inventory inv = Bukkit.createInventory(holder, VANILLA_SIZE,
                "§8材料链 §7· " + Text.plain(target.getDisplayName()));
        holder.inv(inv);

        inv.setItem(SLOT_DETAIL_TARGET, icon(p, target));
        if (TradeCap.blocks(backCap, manager.engine().priceOf(target))) {
            inv.setItem(SLOT_DETAIL_CLOSE - 1, GuiItems.of(Material.BARRIER,
                    "§c超出这台终端的显示上限",
                    "§7当前上限: §e" + Text.num(backCap) + " §7EMC",
                    "§7潜行 + 右键终端可升级上限（每 +1 点 §e"
                            + Text.num(ExchangeService.get() == null ? 1000 : ExchangeService.get().config().terminalUpgradeEmcPerCap)
                            + " §7EMC）"));
        }

        PriceEngine engine = manager.engine();
        PriceEngine.Derivation dv = engine.derive(target);
        double price = engine.priceOf(target);

        if (dv != null && dv.recipe != null) {
            Recipe r = dv.recipe;
            inv.setItem(SLOT_DETAIL_MACHINE, machineIcon(r.getMachine()));

            int i = 0;
            for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
                if (i >= 9) break;
                inv.setItem(DETAIL_INPUT_START + i, inputIcon(e.getKey(), e.getValue(), engine));
                i++;
            }
            inv.setItem(SLOT_DETAIL_PRICE, GuiItems.of(Material.GOLD_INGOT,
                    "§6单价: §e" + Text.num(price),
                    "§7配方总成本 ÷ 产出数量",
                    "§7材料 " + r.getInputs().size() + " 种 → 产出 "
                            + r.getOutputs().getOrDefault(target, 1) + " 个",
                    "§8机器: " + r.getMachine().getDisplayName()));
        } else {
            inv.setItem(SLOT_DETAIL_MACHINE, GuiItems.of(Material.NAME_TAG,
                    "§7基础价物品",
                    "§8价格来自 pricing 表（基础定价），不由配方推导"));
            inv.setItem(SLOT_DETAIL_PRICE, GuiItems.of(Material.GOLD_INGOT,
                    "§6单价: §e" + Text.num(price),
                    "§7来源: 基础定价表 / 手工价"));
        }

        if (dv != null && dv.recipe != null) {
            inv.setItem(SLOT_DETAIL_MATERIALS, materialsIcon(p, target));
        }

        boolean fav = store.fav(p.getUniqueId(), target);
        inv.setItem(SLOT_DETAIL_FAV, GuiItems.of(
                fav ? Material.NETHER_STAR : Material.GOLD_NUGGET,
                fav ? "§e已收藏" : "§7收藏",
                "§7收藏的物品会出现在 §f排序 → 收藏 §7里",
                "§8点一下" + (fav ? "取消收藏" : "加入收藏")));

        inv.setItem(SLOT_DETAIL_BACK, GuiItems.of(Material.ARROW, "§e返回",
                "§7回到物品清单"));
        inv.setItem(SLOT_DETAIL_CLOSE, GuiItems.of(Material.BARRIER, "§c关闭"));
        p.openInventory(inv);
    }

    /** 详情页的「材料抵扣」按钮：显示这份配方能用哪些材料抵多少价 */
    private ItemStack materialsIcon(Player p, ItemKey key) {
        List<String> lore = new ArrayList<>();
        lore.add("§7交出预览里这条配方的材料，只付缺的那部分价");
        lore.add("§7材料交齐 → §a余额一分不扣§7");
        lore.add("");
        ExchangeManager.MaterialQuote q = manager.quoteMaterials(p, key, 1);
        if (q == null) {
            lore.add("§8这个物品没有配方（纯基础价），不能用材料抵扣");
            return GuiItems.of(Material.HOPPER, "§8材料抵扣（不可用）", lore);
        }
        int i = 0;
        for (Map.Entry<ItemKey, Integer> e : q.need.entrySet()) {
            if (i++ >= 6) {
                lore.add("§8… 还有 " + (q.need.size() - 6) + " 种");
                break;
            }
            int have = q.consume.getOrDefault(e.getKey(), 0);
            double v = e.getValue() * manager.engine().priceOf(e.getKey());
            lore.add("§7· §f" + e.getValue() + " × " + e.getKey().getDisplayName()
                    + " §8(抵 " + Text.num(v) + ") §7你有 §f" + have);
        }
        lore.add("");
        lore.add("§7换出 1 个: §7可抵 §e" + Text.num(q.offset) + " §7/ 还需余额 §e" + Text.num(q.remainder));
        lore.add("§b点一下选数量（材料交齐就不扣余额）");
        return GuiItems.of(Material.HOPPER, "§b材料抵扣换出", lore);
    }

    /** 材料抵扣换出：先报一次价，再让玩家选数量。 */
    public void openMaterialBuy(Player p, ItemKey key, int backPage, Sort backSort,
                                String backKeyword, Mode backMode, long backCap) {
        ExchangeManager.MaterialQuote q = manager.quoteMaterials(p, key, 1);
        if (q == null) {
            p.sendMessage("§c[等价交换] 这个物品没有配方（纯基础价），不能用材料抵扣");
            return;
        }
        String label = "§7" + Text.plain(key.getDisplayName())
                + " §8（单价 " + Text.num(q.unit) + " · 材料可抵 " + Text.num(q.offset)
                + " · 余额 " + Text.num(manager.balance(p.getUniqueId())) + "）";
        Dialogs.amount(plugin, p,
                "§8✦ 材料抵扣换出 ✦", label,
                1, 1, manager.config().quickAmountMax, 0,
                () -> openDetail(p, key, backPage, backSort, backKeyword, backMode, backCap),
                n -> {
                    ExchangeResult r = manager.buyWithMaterials(p, key, n);
                    if (r.ok) {
                        p.sendMessage("§a换出 §f" + r.amount + " × " + key.getDisplayName()
                                + " §7(余额 -" + Text.num(r.total) + ")");
                    } else {
                        p.sendMessage("§c[等价交换] " + r.message);
                    }
                    openDetail(p, key, backPage, backSort, backKeyword, backMode, backCap);
                });
    }

    private ItemStack machineIcon(ItemKey machine) {
        ItemStack base = machine.toTemplate();
        if (base == null) {
            return GuiItems.of(Material.CRAFTING_TABLE, "§7机器: §f" + machine.getDisplayName(),
                    "§8" + machine);
        }
        ItemStack out = base.clone();
        out.setAmount(1);
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7机器: §f" + Text.plain(machine.getDisplayName()));
            meta.setLore(List.of("§8" + machine));
            out.setItemMeta(meta);
        }
        return out;
    }

    private ItemStack inputIcon(ItemKey key, int qty, PriceEngine engine) {
        ItemStack base = key.toTemplate();
        if (base == null) base = new ItemStack(Material.PAPER);
        ItemStack out = base.clone();
        out.setAmount(Math.min(64, Math.max(1, qty)));
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            double unit = engine.priceOf(key);
            meta.setDisplayName("§f" + qty + " × " + Text.plain(key.getDisplayName()));
            meta.setLore(List.of(
                    "§7单价: §e" + Text.num(unit),
                    "§7小计: §e" + Text.num(unit * qty),
                    unit > 0 ? "§8点开看材料链？" : "§c没有价（这条链算不通）"));
            out.setItemMeta(meta);
        }
        return out;
    }
}
