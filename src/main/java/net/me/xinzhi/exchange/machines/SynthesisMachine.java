package net.me.xinzhi.exchange.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import net.me.xinzhi.exchange.core.ItemGuard;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.util.Dialogs;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.exchange.TradeCap;
import net.me.xinzhi.exchange.recipe.Recipe;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 等价合成台（腐竹 2026-10-26 / m00824 定稿）：**只负责合成**，运输拆到
 * {@link TransporterMachine}。
 *
 * <p>放在目标机器<b>下方</b>，读<b>正上方</b>那台的配方。点击配方 = 按「配方耗时 × 每 tick
 * 单价」扣 EMC，产物直接给点击的玩家，<b>不消耗材料</b>，钱由点击的人付。
 *
 * <p>版面（腐竹 m00824：「配方放 0-44 槽，功能槽都移到最后一排」）：
 * 0~44 = 配方图标（每页 45 条），45 上一页 / 47 上方机器 / 49 页码 / 51 状态 / 53 下一页，
 * 46、48、50、52 锁死。
 *
 * <p>上方机器身份记在 BlockStorage 的 {@code xz-syn-target}；换了一台就整机待机。
 */
public final class SynthesisMachine extends AbstractMachine {

    public static final String ID = "XZEX_SYNTHESIZER";

    /** 配方区：0~44（腐竹 m00824 点名） */
    private static final int[] WORK = new int[45];

    static {
        for (int i = 0; i < 45; i++) WORK[i] = i;
    }

    /**
     * 最后一排功能槽。48 = 等价道具槽（腐竹 m01476：合成台也要放等价道具，否则玩家能借
     * 「自动购买材料」绕过显示上限）。
     */
    private static final int PREV = 45, REFRESH = 46, TARGET = 47, CAP = 48, PAGE_INFO = 49, STATUS = 51, SEARCH = 52, NEXT = 53;

    public SynthesisMachine(ItemGroup group, SlimefunItemStack item,
                            RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                MachineUtil.setOwner(e.getBlock().getLocation(), e.getPlayer());
                String above = idOf(e.getBlock().getRelative(BlockFace.UP));
                if (above != null) MachineUtil.set(e.getBlock().getLocation(), TARGET_KEY, above);
            }
        });
    }

    static final String TARGET_KEY = "xz-syn-target";

    @Override
    protected String menuTitle() {
        return "§d等价合成台 §8· §7读正上方机器的配方";
    }

    @Override
    protected void initMenu(@Nonnull BlockMenuPreset preset) {
        // 配方格先占位：不占位的话玩家能往配方格塞东西
        for (int i = 0; i < 45; i++) {
            preset.addItem(i, new CustomItemStack(Material.GRAY_STAINED_GLASS_PANE, "§8—"),
                    (p, s, item, action) -> false);
        }
        preset.addItem(PREV, new CustomItemStack(Material.ARROW, "§7上一页"), (p, s, i, a) -> false);
        preset.addItem(TARGET, new CustomItemStack(Material.CRAFTING_TABLE, "§6上方机器", "§7—"), (p, s, i, a) -> false);
        preset.addItem(PAGE_INFO, new CustomItemStack(Material.PAPER, "§7配方页", "§7—"), (p, s, i, a) -> false);
        preset.addItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7待机"), (p, s, i, a) -> false);
        preset.addItem(NEXT, new CustomItemStack(Material.ARROW, "§7下一页"), (p, s, i, a) -> false);
        preset.addItem(REFRESH, new CustomItemStack(Material.SPYGLASS, "§b刷新记录", "§7重新记录正上方的机器"), (p, s, i, a) -> false);
        // 48 是真·空槽（放等价道具），连玻璃板都不放；只锁 50
        preset.addItem(50, LOCKED_PANE, (p, s, item, action) -> false);
        // 52 = 搜索（腐竹 2026-10-28）
        preset.addItem(SEARCH, new CustomItemStack(Material.COMPASS, "§b搜索配方",
                "§7按产物名 / 材料名筛选"), (p, s, item, action) -> false);
    }

    @Override
    public void onMenuOpen(@Nonnull BlockMenu menu, @Nonnull Block b) {
        render(menu, b);
    }

    private void render(BlockMenu menu, Block b) {
        Location loc = b.getLocation();
        List<Recipe> all = recipesFor(b);
        String kwRaw = MachineUtil.info(loc, "xz-syn-kw");
        final String kwText = kwRaw == null ? "" : kwRaw;
        List<Recipe> list = filterByKeyword(all, kwText);
        int pages = Math.max(1, (list.size() + WORK.length - 1) / WORK.length);
        int page = Math.min(pages - 1, Math.max(0, MachineUtil.intInfo(loc, "xz-syn-page", 0)));
        MachineUtil.set(loc, "xz-syn-page", String.valueOf(page));

        for (int i = 0; i < WORK.length; i++) {
            int slot = WORK[i];
            final int idx = page * WORK.length + i;
            if (idx < list.size()) {
                menu.replaceExistingItem(slot, recipeIcon(list.get(idx)));
                menu.addMenuClickHandler(slot, (p, s, item, action) -> {
                    boolean shift = action != null && action.isShiftClicked();
                    boolean right = action != null && action.isRightClicked();
                    // 腐竹 2026-10-28：搜索后点合成必须用「筛选后的那份列表」取配方
                    craft(p, b, idx, kwText, shift, right);
                    return false;
                });
            } else {
                menu.replaceExistingItem(slot, new CustomItemStack(Material.GRAY_STAINED_GLASS_PANE, "§8—"));
                menu.addMenuClickHandler(slot, (p, s, item, action) -> false);
            }
        }

        Target t = resolve(b);
        String storedId = MachineUtil.info(loc, TARGET_KEY);
        try {
            menu.replaceExistingItem(TARGET, new CustomItemStack(Material.CRAFTING_TABLE,
                    "§6上方机器",
                    t == null ? "§c上方没有机器" : "§f" + t.id,
                    "§7记录 " + (storedId == null ? "§8—" : storedId),
                    t != null && storedId != null && !storedId.equals(t.id) ? "§c与记录不符：不操作" : "§8放在目标机器正下方"));
            long capNow = MachineUtil.capOf(menu, CAP);
            String capText = capNow == MachineUtil.CAP_MISSING
                    ? "§c第 " + CAP + " 槽未放等价道具：只能用手上材料"
                    : "§7第 " + CAP + " 槽上限 §f"
                      + (capNow == TradeCap.UNLIMITED ? "无限制" : fmt(capNow) + " EMC");
            menu.replaceExistingItem(PAGE_INFO, new CustomItemStack(Material.PAPER,
                    "§7配方页 §f" + (page + 1) + " §8/ §f" + pages,
                    "§7共 §f" + list.size() + " §7条配方",
                    list.isEmpty() ? "§c上方机器没有可用配方" : "§8左键1次 / Shift左键64次 / Shift右键填满背包",
                    "§8材料优先扣背包，不够按等价道具上限购买",
                    capText));
            menu.replaceExistingItem(PREV, new CustomItemStack(Material.ARROW,
                    page > 0 ? "§7上一页" : "§8已经是第一页"));
            menu.replaceExistingItem(NEXT, new CustomItemStack(Material.ARROW,
                    page + 1 < pages ? "§7下一页" : "§8已经是最后一页"));
            menu.replaceExistingItem(REFRESH, new CustomItemStack(Material.SPYGLASS,
                    "§b刷新记录",
                    "§7重新读取正上方的机器",
                    "§8换机器 / 加配方后点一下"));
            menu.replaceExistingItem(SEARCH, new CustomItemStack(Material.COMPASS,
                    kwText.isEmpty() ? "§b搜索配方" : "§b搜索: §f" + kwText,
                    "§7产物名或材料名包含这个关键词才显示",
                    kwText.isEmpty() ? "§8点一下输入关键词" : "§8点一下可改写 / 清空",
                    "§7全部 §f" + all.size() + " §7条 → 命中 §f" + list.size() + " §7条"));
            setStatus(menu, list.isEmpty() ? "§c上方机器没有可用配方"
                    : (capNow == MachineUtil.CAP_MISSING ? "§7待机（没放等价道具，不能买材料）" : "§7待机"));
        } catch (Throwable ignored) {
        }

        menu.addMenuClickHandler(PREV, (p, s, item, action) -> {
            if (page <= 0) return false;
            MachineUtil.set(loc, "xz-syn-page", String.valueOf(page - 1));
            render(menu, b);
            return false;
        });
        menu.addMenuClickHandler(NEXT, (p, s, item, action) -> {
            if (page + 1 >= pages) return false;
            MachineUtil.set(loc, "xz-syn-page", String.valueOf(page + 1));
            render(menu, b);
            return false;
        });
        menu.addMenuClickHandler(REFRESH, (p, s, item, action) -> {
            Target now = resolve(b);
            if (now == null) {
                p.sendMessage("§c[合成台] 上方没有机器");
                return false;
            }
            MachineUtil.set(loc, TARGET_KEY, now.id);
            MachineUtil.set(loc, "xz-syn-page", "0");
            p.sendMessage("§a[合成台] 已重新记录上方机器: " + now.id);
            render(menu, b);
            return false;
        });
        menu.addMenuClickHandler(SEARCH, (p, s, item, action) -> {
            Dialogs.text((org.bukkit.plugin.java.JavaPlugin) org.bukkit.Bukkit.getPluginManager()
                            .getPlugin("XinZhiExchange"),
                    p, "§8✦ 搜索配方 ✦", "§7产物名 / 材料名里的关键词（留空 = 清空）",
                    kwText, 32,
                    () -> render(menu, b),
                    v -> {
                        String nv = v == null ? "" : v.trim();
                        MachineUtil.set(loc, "xz-syn-kw", nv);
                        MachineUtil.set(loc, "xz-syn-page", "0");
                        render(menu, b);
                    });
            return false;
        });
    }

    /** 搜索（腐竹 2026-10-28）：产物名或任一材料名包含关键词就命中 */
    private static List<Recipe> filterByKeyword(List<Recipe> all, String kw) {
        if (kw == null || kw.isEmpty()) return all;
        String k = kw.toLowerCase(java.util.Locale.ROOT);
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : all) {
            boolean hit = false;
            for (ItemKey o : r.getOutputs().keySet()) {
                String n = o.getDisplayName();
                if (n != null && n.toLowerCase(java.util.Locale.ROOT).contains(k)) { hit = true; break; }
            }
            if (!hit) {
                for (ItemKey in : r.getInputs().keySet()) {
                    String n = in.getDisplayName();
                    if (n != null && n.toLowerCase(java.util.Locale.ROOT).contains(k)) { hit = true; break; }
                }
            }
            if (hit) out.add(r);
        }
        return out;
    }

    private void setStatus(BlockMenu menu, String text) {
        try {
            menu.replaceExistingItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7" + text));
        } catch (Throwable ignored) {
        }
    }

    private ItemStack recipeIcon(Recipe r) {
        ExchangeService svc = ExchangeService.get();
        double perTick = svc == null ? 100 : svc.config().synthesisCraftEmcPerTick;
        ItemKey out = r.getOutputs().keySet().iterator().next();
        int amount = r.getOutputs().getOrDefault(out, 1);
        double cost = r.getTicks() * perTick;
        ItemStack icon = out.toTemplate();
        if (icon == null) icon = new ItemStack(Material.STONE);
        icon = icon.clone();
        icon.setAmount(Math.max(1, Math.min(amount, 64)));
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§7产出: §f" + shortName(out) + " ×" + amount);
            int shown = 0;
            for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
                if (shown++ >= 5) {
                    lore.add("§8… 共 " + r.getInputs().size() + " 种材料");
                    break;
                }
                lore.add("§8· §7" + shortName(e.getKey()) + " ×" + e.getValue());
            }
            lore.add("§7耗时: §f" + r.getTicks() + " tick");
            lore.add("§7费用: §f" + fmt(cost) + " EMC");
            lore.add("§8" + r.getSource());
            lore.add("");
            lore.add("§e左键合成一次，Shift+左键合成 64 次");
            lore.add("§eShift+右键尽可能多的合成");
            lore.add("§7材料优先从背包中扣除，如果不够会自动购买");
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /**
     * 合成：材料优先用背包里的，缺的按买价买；费用 = 时间成本（ticks × 单价）+ 材料成本。
     *
     * <p>腐竹 m00888：左键 1 次、Shift+左键 64 次、Shift+右键尽量填满背包；中间不够就
     * 能做几次做几次，并且宁可少做也不让物品掉出来（m00896）。
     */
    private void craft(Player p, Block b, int idx, String kw, boolean shift, boolean right) {
        ExchangeService svc = ExchangeService.get();
        if (svc == null) return;
        List<Recipe> list = filterByKeyword(recipesFor(b), kw == null ? "" : kw);
        if (idx < 0 || idx >= list.size()) {
            p.sendMessage("§c[合成台] 这条配方已经不在了，重新打开界面");
            return;
        }
        Recipe r = list.get(idx);
        long target = (shift && right) ? Long.MAX_VALUE : (shift ? 64L : 1L);
        double perTick = svc.config().synthesisCraftEmcPerTick;
        double timeCost = r.getTicks() * perTick;
        // 只要这次要「买材料」，就必须先有等价道具（腐竹 m01476：防止用自动购买绕过上限）
        long cap = MachineUtil.capOf(MachineDrops.menu(b.getLocation()), CAP);

        int done = 0;
        String why = null;
        double totalSpent = 0;
        for (long i = 0; i < target; i++) {
            if (freeCrafts(p, r) <= 0) {
                why = "背包放不下产物了";
                break;
            }
            // 材料成本：背包里缺的部分按买价补
            double matCost = 0;
            ItemKey unbuyable = null;
            String buyWhy = null;
            for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
                int have = countIn(p, e.getKey());
                int shortfall = Math.max(0, e.getValue() - have);
                if (shortfall <= 0) continue;
                // 腐竹 2026-10-28：能不能买，还要看它是否出现在玩家自己的等价界面里
                // （清单=已卖过的物品）；没卖过的材料不能靠购买补上
                if (!buyableByPlayer(p, e.getKey(), svc)) {
                    unbuyable = e.getKey();
                    buyWhy = "背包里的 " + shortName(e.getKey())
                            + " 不够，而且你的等价界面里没有它（先卖过一次才能买）";
                    break;
                }
                double unit = MachineUtil.buyUnit(b.getLocation(), e.getKey());
                if (!(unit > 0)) {
                    unbuyable = e.getKey();
                    buyWhy = "背包里的 " + shortName(e.getKey()) + " 不够，而且它买不到";
                    break;
                }
                if (!MachineUtil.buyAllowed(cap, unit)) {
                    unbuyable = e.getKey();
                    buyWhy = cap == MachineUtil.CAP_MISSING
                            ? "要买 " + shortName(e.getKey()) + " 补材料，但第 " + CAP + " 槽没放等价道具"
                            : "要买 " + shortName(e.getKey()) + "(" + fmt(unit) + " EMC)，超出等价道具上限";
                    break;
                }
                matCost += unit * shortfall;
            }
            if (unbuyable != null) {
                why = buyWhy;
                break;
            }
            double cost = timeCost + matCost;
            double balance = svc.manager().balances().get(p.getUniqueId());
            if (balance < cost) {
                why = "EMC 不够（这次要 " + fmt(cost) + "）";
                break;
            }
            // 真扣：背包先扣，缺口等于买到了
            for (Map.Entry<ItemKey, Integer> e : r.getInputs().entrySet()) {
                int take = Math.min(e.getValue(), countIn(p, e.getKey()));
                if (take > 0) takeIn(p, e.getKey(), take);
            }
            svc.manager().balances().add(p.getUniqueId(), -cost);
            totalSpent += cost;
            giveOutputs(p, r);
            done++;
            if (done >= 2304) {
                why = "一次最多做 2304 次";
                break;
            }
        }

        if (done <= 0) {
            p.sendMessage("§c[合成台] 一次都没合成：" + (why == null ? "未知原因" : why));
            return;
        }
        p.sendMessage("§a[合成台] 合成 §f" + done + " §a次（" + shortName(r.getOutputs().keySet().iterator().next())
                + "），花费 §f" + fmt(totalSpent) + "§a EMC"
                + (why == null ? "" : "§7，停在：" + why));
    }

    /** 玩家自己的等价界面里能不能买到这个物品：清单只由卖出记录生成（PlayerViewStore.sold） */
    private static boolean buyableByPlayer(Player p, ItemKey k, ExchangeService svc) {
        if (p == null || k == null || svc == null) return false;
        try {
            return svc.viewStore().sold(p.getUniqueId(), k);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 还能做几次（受产物在背包里放得下的限制） */
    private static long freeCrafts(Player p, Recipe r) {
        long best = Long.MAX_VALUE / 4;
        for (Map.Entry<ItemKey, Integer> e : r.getOutputs().entrySet()) {
            ItemStack tpl = e.getKey().toTemplate();
            if (tpl == null) continue;
            int need = Math.max(1, e.getValue());
            long cap = capacityFor(p, tpl);
            best = Math.min(best, cap / need);
        }
        return best;
    }

    /** 背包还能装下多少个这种物品（只算物品栏 0~35） */
    private static long capacityFor(Player p, ItemStack tpl) {
        ItemKey k = ItemKey.of(tpl);
        int max = Math.max(1, tpl.getMaxStackSize());
        long cap = 0;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && i < 36; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) {
                cap += max;
                continue;
            }
            ItemKey other = ItemKey.of(it);
            if (k != null && k.equals(other) && ItemGuard.pristine(k, it)) {
                cap += Math.max(0, max - it.getAmount());
            }
        }
        return cap;
    }

    private static int countIn(Player p, ItemKey k) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            ItemKey other = ItemKey.of(it);
            if (other != null && other.equals(k) && ItemGuard.pristine(k, it)) n += it.getAmount();
        }
        return n;
    }

    private static void takeIn(Player p, ItemKey k, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            ItemKey other = ItemKey.of(it);
            if (other == null || !other.equals(k) || !ItemGuard.pristine(k, it)) continue;
            int take = Math.min(left, it.getAmount());
            int rest = it.getAmount() - take;
            if (rest <= 0) p.getInventory().setItem(i, null);
            else {
                ItemStack copy = it.clone();
                copy.setAmount(rest);
                p.getInventory().setItem(i, copy);
            }
            left -= take;
        }
    }

    private static void giveOutputs(Player p, Recipe r) {
        for (Map.Entry<ItemKey, Integer> e : r.getOutputs().entrySet()) {
            ItemStack out = e.getKey().toTemplate();
            if (out == null) continue;
            int left = Math.max(1, e.getValue());
            int max = Math.max(1, out.getMaxStackSize());
            while (left > 0) {
                ItemStack one = out.clone();
                int n = Math.min(left, max);
                one.setAmount(n);
                left -= n;
                Map<Integer, ItemStack> rest = p.getInventory().addItem(one);
                for (ItemStack drop : rest.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), drop);
                }
            }
        }
    }

    @Override
    protected void onTick(@Nonnull Block b, @Nonnull SlimefunItem item, SlimefunBlockData data) {
        // 纯合成机没有后台周期，什么都不做
    }

    // ============================================================
    // 共用小工具（运输机里有一份同样的，保持两边独立）
    // ============================================================

    static String idOf(Block block) {
        try {
            return BlockStorage.checkID(block);
        } catch (Throwable t) {
            return null;
        }
    }

    static final class Target {
        final String id;
        final BlockMenu menu;

        Target(String id, BlockMenu menu) {
            this.id = id;
            this.menu = menu;
        }
    }

    static Target resolve(Block b) {
        Block above = b.getRelative(BlockFace.UP);
        String id = idOf(above);
        if (id == null) return null;
        return new Target(id, MachineDrops.menu(above.getLocation()));
    }

    private static List<Recipe> recipesFor(Block b) {
        ExchangeService svc = ExchangeService.get();
        if (svc == null) return List.of();
        Block above = b.getRelative(BlockFace.UP);
        String id = idOf(above);
        if (id == null) return List.of();
        try {
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf == null) return List.of();
            ItemKey key = ItemKey.of(sf);
            if (key == null) return List.of();
            return svc.registry().ofMachine(key);
        } catch (Throwable t) {
            return List.of();
        }
    }

    static String shortName(ItemKey k) {
        try {
            String n = k.getDisplayName();
            return n == null ? k.toString() : n;
        } catch (Throwable t) {
            return k.toString();
        }
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    @Override
    protected int[] storageSlotsForDrop() {
        // 0~44 全是配方展示格，拆机不许把它们当存储物掉出来（否则会复制配方图标）；
        // 48 槽放的是玩家自己的等价道具，拆机必须掉出来（m01476）
        return new int[]{CAP};
    }
}
