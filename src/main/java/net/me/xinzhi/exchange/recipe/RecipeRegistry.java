package net.me.xinzhi.exchange.recipe;

import net.me.xinzhi.exchange.core.ItemKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存配方库：按机器 / 输入 / 输出建索引。 */
public final class RecipeRegistry {

    public interface ChangeListener { void onChanged(); }

    private final Map<Integer, Recipe> byId = new ConcurrentHashMap<>();
    private final Map<String, Integer> byFp = new ConcurrentHashMap<>();
    private final Map<ItemKey, List<Integer>> byMachine = new ConcurrentHashMap<>();
    private final Map<ItemKey, List<Integer>> producedBy = new ConcurrentHashMap<>();
    private final Map<ItemKey, List<Integer>> consumedBy = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private int nextId = 0;

    public void addChangeListener(ChangeListener l) { if (l != null) listeners.add(l); }
    public void removeChangeListener(ChangeListener l) { listeners.remove(l); }

    private void notifyChanged() {
        for (ChangeListener l : listeners) {
            try { l.onChanged(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 注册（同机器 + 同输入输出 视为重复）；返回是否真的新增。
     *
     * <p>重复时不一定是「丢掉后来的」：腐竹 2026-10-26「先到先得确实得改」——
     * 如果后来的配方来自<b>读取顺序更靠前</b>的附属（{@link Recipe#getPriority() 优先级更小}），
     * 就顶掉先前那条，保证留给配方库的是高优先级附属的版本。
     */
    public boolean register(Recipe recipe) {
        if (recipe == null) return false;
        String fp = recipe.getFingerprint();
        Integer existingId = byFp.get(fp);
        if (existingId != null) return replaceIfBetter(existingId, recipe);
        synchronized (lock) {
            existingId = byFp.get(fp);
            if (existingId != null) return replaceIfBetter(existingId, recipe);
            int id = nextId++;
            recipe.setId(id);
            byId.put(id, recipe);
            byFp.put(fp, id);
            byMachine.computeIfAbsent(recipe.getMachine(), k -> new ArrayList<>()).add(id);
            for (ItemKey k : recipe.getInputs().keySet()) {
                consumedBy.computeIfAbsent(k, x -> new ArrayList<>()).add(id);
            }
            for (ItemKey k : recipe.getOutputs().keySet()) {
                producedBy.computeIfAbsent(k, x -> new ArrayList<>()).add(id);
            }
        }
        return true;
    }

    /** 同一指纹重复出现：只有「优先级更靠前」的那条能顶掉旧记录 */
    private boolean replaceIfBetter(int existingId, Recipe incoming) {
        synchronized (lock) {
            Recipe old = byId.get(existingId);
            if (old == null) return false;
            if (incoming.getPriority() >= old.getPriority()) return false;
            incoming.setId(existingId);
            byId.put(existingId, incoming);
            for (ItemKey k : old.getInputs().keySet()) removeFrom(consumedBy, k, existingId);
            for (ItemKey k : old.getOutputs().keySet()) removeFrom(producedBy, k, existingId);
            removeFrom(byMachine, old.getMachine(), existingId);
            byMachine.computeIfAbsent(incoming.getMachine(), k -> new ArrayList<>()).add(existingId);
            for (ItemKey k : incoming.getInputs().keySet()) consumedBy.computeIfAbsent(k, x -> new ArrayList<>()).add(existingId);
            for (ItemKey k : incoming.getOutputs().keySet()) producedBy.computeIfAbsent(k, x -> new ArrayList<>()).add(existingId);
            return false;
        }
    }

    /** 批量注册（扫描用，结束时统一通知一次） */
    public int registerAll(Collection<Recipe> recipes) {
        int n = 0;
        for (Recipe r : recipes) if (register(r)) n++;
        if (n > 0) notifyChanged();
        return n;
    }

    /**
     * 按条件删配方（黑名单用）：连同 机器/输入/输出 三个反向索引一起摘干净。
     *
     * @return 删掉的条数
     */
    public int removeWhere(java.util.function.Predicate<Recipe> cond) {
        if (cond == null) return 0;
        int n = 0;
        synchronized (lock) {
            for (Recipe r : new ArrayList<>(byId.values())) {
                if (!cond.test(r)) continue;
                int id = r.getId();
                byId.remove(id);
                byFp.remove(r.getFingerprint());
                removeFrom(byMachine, r.getMachine(), id);
                for (ItemKey k : r.getInputs().keySet()) removeFrom(consumedBy, k, id);
                for (ItemKey k : r.getOutputs().keySet()) removeFrom(producedBy, k, id);
                n++;
            }
        }
        if (n > 0) notifyChanged();
        return n;
    }

    private static void removeFrom(Map<ItemKey, List<Integer>> index, ItemKey key, int id) {
        List<Integer> list = index.get(key);
        if (list == null) return;
        list.remove(Integer.valueOf(id));
        if (list.isEmpty()) index.remove(key);
    }

    public void clear() {
        synchronized (lock) {
            byId.clear(); byFp.clear(); byMachine.clear();
            producedBy.clear(); consumedBy.clear(); nextId = 0;
        }
        notifyChanged();
    }

    public int size() { return byId.size(); }

    /**
     * 配方库内容摘要（与顺序无关）—— 用来判断「两次扫描结果是否真的不一样」。
     *
     * <p>只比条数是不够的：本轮实测过条数相同、内容却差一条（附属的配方表还在长）的情况。
     */
    public String digest() {
        long h = 0;
        for (String fp : byFp.keySet()) h += fp.hashCode() * 31L;
        return byFp.size() + ":" + Long.toHexString(h);
    }
    public Collection<Recipe> all() { return byId.values(); }
    public Recipe getById(int id) { return byId.get(id); }
    public Map<ItemKey, List<Integer>> machineIndex() { return byMachine; }
    public Collection<ItemKey> machines() { return byMachine.keySet(); }

    public List<Recipe> producersOf(ItemKey key) { return collect(producedBy.get(key)); }
    public List<Recipe> consumersOf(ItemKey key) { return collect(consumedBy.get(key)); }
    public List<Recipe> ofMachine(ItemKey machine) { return collect(byMachine.get(machine)); }

    private List<Recipe> collect(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Recipe> out = new ArrayList<>(ids.size());
        for (int id : ids) {
            Recipe r = byId.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }
}
