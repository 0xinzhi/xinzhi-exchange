package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 两个管理面板（从主附属的 EMC 定制 / 批量定价搬过来，id 换成 XZEX_）：
 * <ul>
 *   <li><b>价格定制</b>：直接管 {@code prices.yml} 的 {@code manual:} 段，一个物品一个价；</li>
 *   <li><b>批量定价</b>：先攒一张清单，再统一定价 / 逐个定价 / 一次写盘。</li>
 * </ul>
 *
 * <p>两者都只写 {@code manual:} 层 —— 基础价（内置表）不动，删掉手工价就回落到基础价。
 */
public final class PricingAdminGui {

    public static final String TITLE_CUSTOM = "§8§l✦ 价格定制";
    public static final String TITLE_BATCH = "§8§l✦ 批量定价";
    public static final String TITLE_FILTER = "§8§l✦ 批量定价 §7[筛选]";

    public enum Kind { CUSTOM, BATCH, FILTER }

    public static final class Holder implements InventoryHolder {
        public final Kind kind;
        public final UUID player;
        public int page;
        public boolean byPrice;
        private Inventory inv;
        Holder(Kind kind, UUID player) { this.kind = kind; this.player = player; }
        public void inv(Inventory inv) { this.inv = inv; }
        @Override public Inventory getInventory() { return inv; }
    }

    // 价格定制页
    public static final int C_PREV = 45;
    public static final int C_NEXT = 46;
    public static final int C_ADD = 48;
    public static final int C_INFO = 49;
    public static final int C_EXPORT = 51;
    public static final int C_CLOSE = 53;

    // 批量定价页
    public static final int B_FILTER = 2;
    public static final int B_INFO = 4;
    public static final int B_SORT = 8;
    public static final int B_PREV = 45;
    public static final int B_SCAN = 46;
    public static final int B_ADD = 47;
    public static final int B_UNIFORM = 48;
    public static final int B_SAVE = 49;
    public static final int B_CLEAR = 50;
    public static final int B_EXPORT = 51;
    public static final int B_NEXT = 53;

    public static final int ITEMS_START = 9;
    public static final int ITEMS_END = 44;
    public static final int CUSTOM_SIZE = 45;

    // 筛选页
    public static final int F_UNPRICED = 10;
    public static final int F_NO_ANCHOR = 12;
    public static final int F_VANILLA = 14;
    public static final int F_SLIMEFUN = 16;
    public static final int F_BACK = 22;

    private final ExchangeService svc;
    private final Map<UUID, LinkedHashMap<ItemKey, Double>> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> sortByPrice = new ConcurrentHashMap<>();

    public PricingAdminGui(ExchangeService svc) {
        this.svc = svc;
    }

    public LinkedHashMap<ItemKey, Double> session(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
    }

    public void clearSession(Player p) { sessions.remove(p.getUniqueId()); }

    /** 批量面板的排序开关（按名称 / 按待保存价） */
    public void toggleSort(Player p) {
        boolean now = !sortByPrice.getOrDefault(p.getUniqueId(), false);
        sortByPrice.put(p.getUniqueId(), now);
        openBatch(p, 0);
    }

    // ============================================================
    // 价格定制
    // ============================================================

    public void openCustom(Player p, int page) {
        List<Map.Entry<ItemKey, Double>> list = new ArrayList<>(svc.book().manualEntries().entrySet());
        list.sort(Comparator.comparing(e -> e.getKey().toString()));

        int pages = Math.max(1, (list.size() + CUSTOM_SIZE - 1) / CUSTOM_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(Kind.CUSTOM, p.getUniqueId());
        h.page = pg;
        Inventory inv = Bukkit.createInventory(h, 54,
                TITLE_CUSTOM + " §7[" + (pg + 1) + "/" + pages + "§7]");
        h.inv(inv);

        int start = pg * CUSTOM_SIZE;
        for (int i = 0; i < CUSTOM_SIZE && start + i < list.size(); i++) {
            inv.setItem(i, customIcon(list.get(start + i)));
        }
        if (list.isEmpty()) {
            inv.setItem(22, GuiItems.of(Material.BOOK, "§7还没有手工价",
                    "§7拿一个物品在手上，点下面的 §f添加手持物品",
                    "§7或者用 §f/xzex set <物品> <价格>"));
        }
        if (pg > 0) inv.setItem(C_PREV, GuiItems.of(Material.ARROW, "§e上一页"));
        if (pg < pages - 1) inv.setItem(C_NEXT, GuiItems.of(Material.ARROW, "§e下一页"));
        inv.setItem(C_ADD, GuiItems.of(Material.ANVIL, "§a➕ 添加手持物品",
                "§7把主手物品写进 manual: 段",
                "§7点一下弹输入框填价格"));
        inv.setItem(C_INFO, GuiItems.of(Material.PAPER, "§6§l价格定制",
                "§7手工价条目: §e" + list.size(),
                "§7定价表: §f" + svc.book().file().getName(),
                "",
                "§7左键条目: §f改价",
                "§7右键条目: §c删掉这条手工价"));
        inv.setItem(C_EXPORT, GuiItems.of(Material.WRITABLE_BOOK, "§b📤 导出手工价",
                "§7写到 §fdiag/manual-prices.tsv",
                "§7格式: §8key<TAB>价格"));
        inv.setItem(C_CLOSE, GuiItems.of(Material.BARRIER, "§c关闭"));
        p.openInventory(inv);
    }

    private ItemStack customIcon(Map.Entry<ItemKey, Double> e) {
        ItemKey key = e.getKey();
        ItemStack icon = key.toTemplate();
        if (icon == null) icon = new ItemStack(Material.PAPER);
        icon = icon.clone();
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.setDisplayName("§e" + key.getDisplayName());
        List<String> lore = new ArrayList<>();
        lore.add("§7Key: §8" + key);
        lore.add("§7手工价: §a" + Text.money2(e.getValue()));
        lore.add("§7当前生效价: §f" + Text.money2(svc.engine().priceOf(key)));
        lore.add("");
        lore.add("§a[左键] §7改价");
        lore.add("§c[右键] §7删除手工价");
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    // ============================================================
    // 批量定价
    // ============================================================

    /** 批量清单按当前排序规则排好（界面 / 点击定位共用一份） */
    public List<ItemKey> batchKeys(Player p) {
        LinkedHashMap<ItemKey, Double> s = session(p);
        List<ItemKey> keys = new ArrayList<>(s.keySet());
        boolean byPrice = sortByPrice.getOrDefault(p.getUniqueId(), false);
        if (keys.size() > 1) {
            if (byPrice) {
                keys.sort(Comparator.comparingDouble(k -> {
                    Double v = s.get(k);
                    return v == null ? Double.MAX_VALUE : v;
                }));
            } else {
                keys.sort(Comparator.comparing(ItemKey::getDisplayName, String.CASE_INSENSITIVE_ORDER));
            }
        }
        return keys;
    }

    public ItemKey customKeyAt(int page, int slot) {
        if (slot < 0 || slot >= CUSTOM_SIZE) return null;
        List<Map.Entry<ItemKey, Double>> list = new ArrayList<>(svc.book().manualEntries().entrySet());
        list.sort(Comparator.comparing(e -> e.getKey().toString()));
        int idx = page * CUSTOM_SIZE + slot;
        return idx < list.size() ? list.get(idx).getKey() : null;
    }

    public ItemKey batchKeyAt(Player p, int page, int slot) {
        if (slot < ITEMS_START || slot > ITEMS_END) return null;
        List<ItemKey> keys = batchKeys(p);
        int idx = page * (ITEMS_END - ITEMS_START + 1) + (slot - ITEMS_START);
        return idx < keys.size() ? keys.get(idx) : null;
    }

    public void openBatch(Player p, int page) {
        LinkedHashMap<ItemKey, Double> s = session(p);
        List<ItemKey> keys = batchKeys(p);
        boolean byPrice = sortByPrice.getOrDefault(p.getUniqueId(), false);
        int perPage = ITEMS_END - ITEMS_START + 1;
        int pages = Math.max(1, (keys.size() + perPage - 1) / perPage);
        int pg = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(Kind.BATCH, p.getUniqueId());
        h.page = pg;
        h.byPrice = byPrice;
        Inventory inv = Bukkit.createInventory(h, 54,
                TITLE_BATCH + " §7[" + (pg + 1) + "/" + pages + "§7]");
        h.inv(inv);

        int start = pg * perPage;
        for (int i = 0; i < perPage && start + i < keys.size(); i++) {
            inv.setItem(ITEMS_START + i, batchIcon(keys.get(start + i), s));
        }

        int set = 0;
        for (Double v : s.values()) if (v != null) set++;
        inv.setItem(B_INFO, GuiItems.of(Material.PAPER, "§6§l批量定价",
                "§7清单物品: §e" + s.size(),
                "§7已填价: §a" + set,
                "§7未填价: §c" + (s.size() - set),
                "",
                "§7Shift+左键背包物品: §f快速加入清单",
                "§7左键清单物品: §f单独定价",
                "§7右键清单物品: §c从清单移除"));
        inv.setItem(B_FILTER, GuiItems.of(Material.SPYGLASS, "§d🔍 快捷筛选",
                "§7从全服物品里挑一批进清单",
                "§7未定价 / 无锚点 / 原版 / 粘液"));
        inv.setItem(B_SORT, GuiItems.of(Material.HOPPER, "§e排序: §f" + (byPrice ? "按价格" : "按名称"),
                "§7点一下切换"));
        if (pg > 0) inv.setItem(B_PREV, GuiItems.of(Material.ARROW, "§e上一页"));
        if (pg < pages - 1) inv.setItem(B_NEXT, GuiItems.of(Material.ARROW, "§e下一页"));
        inv.setItem(B_SCAN, GuiItems.of(Material.CHEST, "§b🔍 扫描背包",
                "§7把背包所有非空格子加进清单"));
        inv.setItem(B_ADD, GuiItems.of(Material.ANVIL, "§a➕ 添加手持物品"));
        inv.setItem(B_UNIFORM, GuiItems.of(Material.GOLD_INGOT, "§6💰 统一定价",
                "§7给清单里所有物品设同一个价",
                "§7点一下弹输入框"));
        inv.setItem(B_SAVE, GuiItems.of(Material.EMERALD_BLOCK, "§a§l✔ 保存并应用",
                "§7写进 prices.yml 的 manual: 段",
                "§7并重建价表"));
        inv.setItem(B_CLEAR, GuiItems.of(Material.LAVA_BUCKET, "§c🗑 清空清单"));
        inv.setItem(B_EXPORT, GuiItems.of(Material.WRITABLE_BOOK, "§b📤 导出清单",
                "§7写到 §fdiag/batch-prices.tsv"));
        p.openInventory(inv);
    }

    private ItemStack batchIcon(ItemKey key, Map<ItemKey, Double> s) {
        ItemStack icon = key.toTemplate();
        if (icon == null) icon = new ItemStack(Material.PAPER);
        icon = icon.clone();
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.setDisplayName("§e" + key.getDisplayName());
        List<String> lore = new ArrayList<>();
        lore.add("§7Key: §8" + key);
        Double pending = s.get(key);
        lore.add(pending == null ? "§7待保存: §c未填价" : "§7待保存: §a" + Text.money2(pending));
        lore.add("§7当前生效价: §f" + Text.money2(svc.engine().priceOf(key)));
        lore.add("");
        lore.add("§a[左键] §7单独定价");
        lore.add("§c[右键] §7从清单移除");
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    public void openFilter(Player p) {
        Holder h = new Holder(Kind.FILTER, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 27, TITLE_FILTER);
        h.inv(inv);
        inv.setItem(F_UNPRICED, GuiItems.of(Material.REDSTONE_TORCH, "§c🔍 未定价物品",
                "§7算不出价、也没有手工价",
                "§7点一下全部进清单"));
        inv.setItem(F_NO_ANCHOR, GuiItems.of(Material.LEVER, "§e🔍 无锚点物品",
                "§7有价，但价格是配方推出来的",
                "§7点一下全部进清单"));
        inv.setItem(F_VANILLA, GuiItems.of(Material.GRASS_BLOCK, "§a🔍 原版物品",
                "§7所有可发放的原版材质"));
        inv.setItem(F_SLIMEFUN, GuiItems.of(Material.CARROT, "§b🔍 粘液物品",
                "§7所有已注册的粘液科技物品"));
        inv.setItem(F_BACK, GuiItems.of(Material.BARRIER, "§c返回"));
        p.openInventory(inv);
    }

    // ============================================================
    // 操作
    // ============================================================

    public void addHeld(Player p, boolean batch, int page) {
        ItemKey key = ItemKey.of(p.getInventory().getItemInMainHand());
        if (key == null) {
            p.sendMessage("§c[等价交换] 手上没有物品");
            return;
        }
        if (batch) {
            session(p).putIfAbsent(key, null);
            openBatch(p, page);
            p.sendMessage("§7已加入清单: §f" + key.getDisplayName());
        } else {
            double current = svc.engine().priceOf(key);
            Dialogs.price(svc.plugin(), p, "§8✦ 手工价 ✦",
                    "§7" + Text.plain(key.getDisplayName()) + " §8（当前价 "
                            + Text.money2(current) + "）",
                    current, 0,
                    () -> openCustom(p, page),
                    v -> {
                        svc.book().setManual(key, v);
                        svc.reloadPrices();
                        p.sendMessage("§a[等价交换] " + key.getDisplayName() + " 手工价 = " + Text.money2(v));
                        openCustom(p, page);
                    });
        }
    }

    public void setOne(Player p, ItemKey key, boolean batch, int page) {
        double current = batch ? (session(p).get(key) == null ? svc.engine().priceOf(key) : session(p).get(key))
                : svc.engine().priceOf(key);
        Dialogs.price(svc.plugin(), p, "§8✦ 定价 ✦",
                "§7" + Text.plain(key.getDisplayName()) + " §8（当前 " + Text.money2(current) + "）",
                current, 0,
                () -> { if (batch) openBatch(p, page); else openCustom(p, page); },
                v -> {
                    if (batch) {
                        session(p).put(key, v);
                        openBatch(p, page);
                    } else {
                        svc.book().setManual(key, v);
                        svc.reloadPrices();
                        openCustom(p, page);
                    }
                });
    }

    public void removeOne(Player p, ItemKey key, boolean batch, int page) {
        if (batch) {
            session(p).remove(key);
            openBatch(p, page);
        } else {
            svc.book().unsetManual(key);
            svc.reloadPrices();
            openCustom(p, page);
            p.sendMessage("§a[等价交换] 已删除 " + key.getDisplayName() + " 的手工价");
        }
    }

    public void uniform(Player p, int page) {
        LinkedHashMap<ItemKey, Double> s = session(p);
        if (s.isEmpty()) {
            p.sendMessage("§c[等价交换] 清单是空的");
            return;
        }
        Dialogs.price(svc.plugin(), p, "§8✦ 统一定价 ✦",
                "§7给清单里 " + s.size() + " 个物品设同一个价",
                1, 0,
                () -> openBatch(p, page),
                v -> {
                    for (ItemKey k : new ArrayList<>(s.keySet())) s.put(k, v);
                    openBatch(p, page);
                    p.sendMessage("§7已把 " + s.size() + " 个物品的待保存价设为 " + Text.money2(v));
                });
    }

    public void saveBatch(Player p, int page) {
        LinkedHashMap<ItemKey, Double> s = session(p);
        if (s.isEmpty()) {
            p.sendMessage("§c[等价交换] 清单是空的");
            return;
        }
        Map<ItemKey, Double> out = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Double> e : s.entrySet()) {
            if (e.getValue() != null) out.put(e.getKey(), e.getValue());
        }
        if (out.isEmpty()) {
            p.sendMessage("§c[等价交换] 还没有填过价");
            return;
        }
        int n = svc.book().setManualBatch(out);
        svc.reloadPrices();
        p.sendMessage("§a[等价交换] 已写入 " + n + " 条手工价（prices.yml 的 manual: 段）");
        openBatch(p, page);
    }

    public void scanInventory(Player p, int page) {
        LinkedHashMap<ItemKey, Double> s = session(p);
        int added = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            if (s.putIfAbsent(k, null) == null) added++;
        }
        openBatch(p, page);
        p.sendMessage("§7从背包加入 " + added + " 个物品（清单共 " + s.size() + " 个）");
    }

    public void filter(Player p, int kind) {
        LinkedHashMap<ItemKey, Double> s = session(p);
        int added = 0;
        if (kind == 0) {              // 未定价
            for (ItemKey k : allKnownKeys()) {
                if (svc.engine().hasPrice(k)) continue;
                if (s.putIfAbsent(k, null) == null) added++;
            }
        } else if (kind == 1) {       // 无锚点
            for (ItemKey k : svc.manager().catalog()) {
                PriceEngine.Derivation dv = svc.engine().derive(k);
                if (dv == null || dv.origin != PriceEngine.Origin.RECIPE) continue;
                if (s.putIfAbsent(k, null) == null) added++;
            }
        } else if (kind == 2) {       // 原版
            for (Material m : Material.values()) {
                if (!m.isItem() || m.isAir()) continue;
                ItemKey k = ItemKey.vanilla(m);
                if (k == null) continue;
                if (s.putIfAbsent(k, null) == null) added++;
            }
        } else {                      // 粘液
            try {
                for (var item : io.github.thebusybiscuit.slimefun4.implementation.Slimefun
                        .getRegistry().getAllSlimefunItems()) {
                    ItemKey k = ItemKey.of(item);
                    if (k == null) continue;
                    if (s.putIfAbsent(k, null) == null) added++;
                }
            } catch (Throwable ignored) {}
        }
        openBatch(p, 0);
        p.sendMessage("§7筛选加入 " + added + " 个物品（清单共 " + s.size() + " 个）");
    }

    private LinkedHashSet<ItemKey> allKnownKeys() {
        LinkedHashSet<ItemKey> all = new LinkedHashSet<>();
        for (Recipe r : svc.registry().all()) {
            all.addAll(r.getInputs().keySet());
            all.addAll(r.getOutputs().keySet());
        }
        return all;
    }

    // ============================================================
    // 导出
    // ============================================================

    public void exportCustom(Player p) {
        StringBuilder sb = new StringBuilder("# 等价交换（独立版）手工价导出\n# 格式: key<TAB>价格\n");
        List<Map.Entry<ItemKey, Double>> list = new ArrayList<>(svc.book().manualEntries().entrySet());
        list.sort(Comparator.comparing(e -> e.getKey().toString()));
        for (Map.Entry<ItemKey, Double> e : list) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        write(p, "manual-prices.tsv", sb.toString(), list.size());
    }

    public void exportBatch(Player p) {
        StringBuilder sb = new StringBuilder("# 等价交换（独立版）批量定价导出\n# 格式: key<TAB>价格\n");
        LinkedHashMap<ItemKey, Double> s = session(p);
        List<Map.Entry<ItemKey, Double>> list = new ArrayList<>(s.entrySet());
        list.sort(Comparator.comparing(e -> e.getKey().toString()));
        int n = 0;
        for (Map.Entry<ItemKey, Double> e : list) {
            if (e.getValue() == null) continue;
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            n++;
        }
        write(p, "batch-prices.tsv", sb.toString(), n);
    }

    private void write(Player p, String name, String content, int count) {
        File dir = new File(svc.plugin().getDataFolder(), "diag");
        if (!dir.exists() && !dir.mkdirs()) {
            p.sendMessage("§c[等价交换] 无法创建 diag 目录");
            return;
        }
        File out = new File(dir, name);
        try {
            Files.write(out.toPath(), content.getBytes(StandardCharsets.UTF_8));
            p.sendMessage("§a[等价交换] 已导出 " + count + " 条 → §f" + out.getPath());
        } catch (Throwable t) {
            p.sendMessage("§c[等价交换] 导出失败: " + t);
        }
    }
}