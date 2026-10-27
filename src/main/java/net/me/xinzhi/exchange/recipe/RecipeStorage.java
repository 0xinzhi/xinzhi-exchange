package net.me.xinzhi.exchange.recipe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配方库落盘：{@code plugins/XinZhiExchange/recipes/}，一台机器一个 json + index.json。
 *
 * <p>格式与主附属一致（{@code machine / source / inputs / outputs}），
 * 方便用同一套工具审数据，但<b>目录、文件、索引完全独立</b>，互不影响。
 */
public final class RecipeStorage {

    private static final String INDEX_FILE = "index.json";
    private static final int FORMAT_VERSION = 1;

    private final JavaPlugin plugin;
    private final File dir;
    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public RecipeStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "recipes");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[等价交换] 无法创建配方目录: " + dir.getAbsolutePath());
        }
    }

    public File getDirectory() { return dir; }

    private static final class RecipeDto {
        String machine;
        String source;
        Map<String, Integer> inputs;
        Map<String, Integer> outputs;
        /** 耗时（tick）；老文件没有这个键 = 1 */
        Integer ticks;
    }

    private static final class IndexDto {
        int version = FORMAT_VERSION;
        long lastUpdate;
        List<String> machines = new ArrayList<>();
    }

    // ---------------- 写 ----------------

    public void saveAll(RecipeRegistry registry) {
        IndexDto index = new IndexDto();
        index.lastUpdate = System.currentTimeMillis();
        int ok = 0, fail = 0;
        for (ItemKey machine : new ArrayList<>(registry.machines())) {
            List<Recipe> list = registry.ofMachine(machine);
            if (list.isEmpty()) continue;
            try {
                saveMachine(machine, list);
                index.machines.add(machine.toString());
                ok++;
            } catch (Throwable t) {
                plugin.getLogger().warning("[等价交换] 保存配方失败 " + machine + ": " + t);
                fail++;
            }
        }
        try {
            writeAtomic(new File(dir, INDEX_FILE), gson.toJson(index));
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 写 index.json 失败: " + t);
        }
        plugin.getLogger().info("[等价交换] 配方落盘: " + ok + " 台机器"
                + (fail > 0 ? "，失败 " + fail : ""));
    }

    private void saveMachine(ItemKey machine, List<Recipe> list) throws Exception {
        List<RecipeDto> dtos = new ArrayList<>(list.size());
        for (Recipe r : list) {
            RecipeDto d = new RecipeDto();
            d.machine = r.getMachine().toString();
            d.source = r.getSource();
            d.inputs = toStringMap(r.getInputs());
            d.outputs = toStringMap(r.getOutputs());
            d.ticks = r.getTicks();
            dtos.add(d);
        }
        writeAtomic(new File(dir, fileName(machine)), gson.toJson(dtos));
    }

    private static Map<String, Integer> toStringMap(Map<ItemKey, Integer> in) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Integer> e : in.entrySet()) out.put(e.getKey().toString(), e.getValue());
        return out;
    }

    /**
     * 删掉本轮扫描没有产出的机器文件。
     *
     * <p>「每次重启都重新扫描所有配方」（腐竹 2026-10-18）意味着磁盘必须与本次扫描结果
     * 完全一致：插件被卸、机器被拉黑、机器改名，旧 json 都不能留在目录里冒充数据。
     *
     * @return 删掉的文件数
     */
    public int purgeStale(RecipeRegistry registry) {
        if (registry == null) return 0;
        Set<String> keep = new HashSet<>();
        for (ItemKey machine : registry.machines()) keep.add(fileName(machine));
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            String name = f.getName();
            if (!f.isFile() || !name.endsWith(".json") || name.equals(INDEX_FILE)) continue;
            if (keep.contains(name)) continue;
            try {
                if (f.delete()) n++;
                else plugin.getLogger().warning("[等价交换] 删不掉过期配方文件: " + name);
            } catch (Throwable t) {
                plugin.getLogger().warning("[等价交换] 删过期配方文件失败 " + name + ": " + t);
            }
        }
        return n;
    }

    // ---------------- 读 ----------------

    /** 读回配方库；返回注册条数 */
    public int loadAll(RecipeRegistry registry) {
        List<String> machines = readIndex();
        int total = 0;
        for (String m : machines) {
            File f = new File(dir, m.replace(':', '_') + ".json");
            if (!f.isFile()) continue;
            total += loadFile(f, registry);
        }
        return total;
    }

    private List<String> readIndex() {
        File f = new File(dir, INDEX_FILE);
        if (!f.isFile()) return List.of();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            IndexDto idx = gson.fromJson(r, IndexDto.class);
            if (idx == null || idx.machines == null) return List.of();
            return idx.machines;
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读 index.json 失败: " + t);
            return List.of();
        }
    }

    private int loadFile(File f, RecipeRegistry registry) {
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            List<RecipeDto> dtos = gson.fromJson(r, new TypeToken<List<RecipeDto>>() {}.getType());
            if (dtos == null) return 0;
            int n = 0;
            for (RecipeDto d : dtos) {
                Recipe rec = toRecipe(d);
                if (rec == null) continue;
                registry.register(rec);
                n++;
            }
            return n;
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读配方文件失败 " + f.getName() + ": " + t);
            return 0;
        }
    }

    private Recipe toRecipe(RecipeDto d) {
        if (d == null || d.machine == null) return null;
        ItemKey machine = ItemKey.fromStored(d.machine);
        if (machine == null) return null;
        Map<ItemKey, Integer> ins = toKeyMap(d.inputs);
        Map<ItemKey, Integer> outs = toKeyMap(d.outputs);
        if (ins.isEmpty() || outs.isEmpty()) return null;
        return new Recipe(machine, ins, outs, d.source, d.ticks == null ? 1 : d.ticks);
    }

    private static Map<ItemKey, Integer> toKeyMap(Map<String, Integer> in) {
        Map<ItemKey, Integer> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, Integer> e : in.entrySet()) {
            ItemKey k = ItemKey.fromStored(e.getKey());
            if (k != null) out.put(k, e.getValue());
        }
        return out;
    }

    // ---------------- 工具 ----------------

    public static String fileName(ItemKey machine) {
        return machine.toString().replace(':', '_') + ".json";
    }

    public List<String> listMachineFiles() {
        List<String> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) if (f.isFile() && f.getName().endsWith(".json") && !f.getName().equals(INDEX_FILE)) out.add(f.getName());
        return out;
    }

    private static void writeAtomic(File target, String json) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 供命令统计用 */
    public Collection<String> disabledMachines() { return List.of(); }
}
