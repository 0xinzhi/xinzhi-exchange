package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每个玩家自己的「卖过」清单（腐竹 2026-10-18 第四轮提出，
 * 2026-10-27 收紧成「只认卖过」）。
 *
 * <h2>为什么要单独存</h2>
 * <p>腐竹原话：「给每个玩家单独展示。只展示玩家自己卖过或预览过的物品。」
 * 2026-10-27 他又改成：「将等价道具的显示规则从预览过就记录改为只能通过卖出这个物品才记录。」
 * 所以清单不能是全局的 —— 每个 UUID 一份，而且只由 {@link #markSold} 往里加。
 *
 * <p>老版本会把「打开详情 / 买过」也记进 .preview 段；读盘时这一段会被并进卖过清单
 * （见 load() 的迁移），此后不再写入，玩家的清单不会因为这次改动凭空变空。
 *
 * <h2>为什么不用 SQL</h2>
 * <p>一个玩家这里最多几百个 key，整服也就几十 MB 里的一点点；
 * 用 YAML（{@code plugins/XinZhiExchange/players.yml}）已经够快，还省一个依赖。
 * 真要做成几十万行 / 跨服共享，再上 SQLite 或 MySQL —— 那时换的是这一个类，不动界面。
 *
 * <p>去重靠 {@link LinkedHashSet}；超过 {@link #MAX_PER_KIND} 条就从最早加的丢，
 * 免得有人挂机把文件写成几万行。
 */
public final class PlayerViewStore {

    /** 每个玩家的清单最多记多少条 */
    public static final int MAX_PER_KIND = 800;

    private static final class Entry {
        final LinkedHashSet<ItemKey> sold = new LinkedHashSet<>();
        /** 玩家主动关掉了超级菜单（默认开）。存 false 才写盘，见 save() */
        boolean modOff;
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> data = new HashMap<>();
    private int unsaved = 0;

    public PlayerViewStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        load();
    }

    public File file() { return file; }

    private Entry entry(UUID id) {
        return data.computeIfAbsent(id, k -> new Entry());
    }

    // ============================================================

    public synchronized void markSold(UUID id, ItemKey key) {
        if (id == null || key == null) return;
        if (entry(id).sold.add(key)) touch();
    }

    public synchronized boolean sold(UUID id, ItemKey key) {
        Entry e = data.get(id);
        return e != null && e.sold.contains(key);
    }

    /** 玩家自己的清单 = 卖过的那些（腐竹 2026-10-27：只认卖过） */
    public synchronized List<ItemKey> personal(UUID id) {
        Entry e = data.get(id);
        if (e == null) return List.of();
        return new ArrayList<>(e.sold);
    }

    // ============================================================
    // 每个玩家的「超级菜单」开关（腐竹 2026-10-18 第七轮：
    // 「给玩家指令或者快捷键用于设置是否启用超级菜单」）
    // ============================================================

    /** 默认开：没记录 = 用超级菜单（只要客户端握手成功） */
    public synchronized boolean modEnabled(UUID id) {
        Entry e = data.get(id);
        return e == null || !e.modOff;
    }

    public synchronized void setModEnabled(UUID id, boolean on) {
        if (id == null) return;
        Entry e = entry(id);
        if (e.modOff == !on) return;
        e.modOff = !on;
        save();                       // 这是显式设置，立刻落盘，别等 5 分钟定时器
    }

    public synchronized int size(UUID id) {
        Entry e = data.get(id);
        return e == null ? 0 : e.sold.size();
    }

    public synchronized void clear(UUID id) {
        if (data.remove(id) != null) touch();
    }

    public synchronized int totalPlayers() { return data.size(); }

    // ============================================================
    // 落盘
    // ============================================================

    private void touch() {
        if (++unsaved >= 40) save();
    }

    /** 减到上限以内（丢最早加的） */
    private static void trim(LinkedHashSet<ItemKey> set) {
        while (set.size() > MAX_PER_KIND) {
            var it = set.iterator();
            if (!it.hasNext()) return;
            it.next();
            it.remove();
        }
    }

    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "每个玩家的「卖过」清单（等价交换独立版）",
                "",
                "只有玩家在这里卖过的物品才会出现在他自己的界面上。",
                "删掉某个玩家的一行 = 清空那个玩家自己的清单。"));
        for (Map.Entry<UUID, Entry> en : data.entrySet()) {
            Entry e = en.getValue();
            trim(e.sold);
            String base = "players." + en.getKey();
            if (!e.sold.isEmpty()) {
                List<String> l = new ArrayList<>(e.sold.size());
                for (ItemKey k : e.sold) l.add(k.toString());
                yml.set(base + ".sold", l);
            }
            if (e.modOff) yml.set(base + ".super-menu", false);
        }
        try {
            yml.save(file);
            unsaved = 0;
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 保存 players.yml 失败: " + t);
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yml.getConfigurationSection("players");
            if (root == null) return;
            int migrated = 0;
            for (String key : root.getKeys(false)) {
                UUID id;
                try { id = UUID.fromString(key); } catch (Throwable t) { continue; }
                Entry e = entry(id);
                readInto(root.getStringList(key + ".sold"), e.sold);
                // 老版本的 .preview 段（打开详情 / 买过也算）在这里一次性并进卖过清单，
                // 之后再也不会写；不并的话老玩家的清单会直接变空。
                int before = e.sold.size();
                readInto(root.getStringList(key + ".preview"), e.sold);
                migrated += e.sold.size() - before;
                e.modOff = !root.getBoolean(key + ".super-menu", true);
            }
            plugin.getLogger().info("[等价交换] 玩家清单已加载: " + data.size() + " 个玩家"
                    + (migrated > 0 ? "（老 .preview 记录并入卖过清单 " + migrated + " 条）" : ""));
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读 players.yml 失败: " + t);
        }
    }

    private static void readInto(List<String> raw, Set<ItemKey> sink) {
        if (raw == null) return;
        for (String s : raw) {
            ItemKey k = ItemKey.fromStored(s);
            if (k != null) sink.add(k);
        }
    }

    /** 只读快照（诊断用） */
    public synchronized Map<UUID, List<ItemKey>> snapshot() {
        Map<UUID, List<ItemKey>> out = new HashMap<>();
        for (UUID id : data.keySet()) out.put(id, personal(id));
        return Collections.unmodifiableMap(out);
    }
}
