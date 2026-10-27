package net.me.xinzhi.exchange.recipe;

import net.me.xinzhi.exchange.core.ItemKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 一条配方：机器 + 输入 + 输出。
 *
 * <h2>⭐ 与主附属最大的区别：指纹<b>包含机器</b></h2>
 * <p>主附属的 {@code Recipe.getFingerprint()} 只用「输入/输出」，不含机器 ——
 * 于是「同一组输入 → 同一组输出」在不同机器上只保留<b>先注册的那一台</b>。
 * 实测代价（线上配方库 14,866 条）：<b>1,586 组</b>「RT_ 虚拟机器」与真实机器的
 * 同一配方互相顶掉，被顶掉的往往是真实机器 ——
 * 表现就是「正常机器缺配方、RT_ 开头的机器凭空多出配方」。
 *
 * <p>本附属的指纹 = {@code 机器|输入|输出}：不同机器的同一配方<b>各留各的</b>，
 * 只有同一台机器上的重复注册才会被去重。
 */
public final class Recipe {

    private final ItemKey machine;
    private final Map<ItemKey, Integer> inputs;
    private final Map<ItemKey, Integer> outputs;
    private final String source;
    /** 配方耗时（tick）：Slimefun MachineRecipe#getTicks；读不到时按 1 tick 算（腐竹 2026-10-26） */
    private final int ticks;
    /**
     * 归属附属的读取优先级（0 = 最先读，数字越小越优先；未列出的附属给一个大数）。
     * 不进指纹：同一台机器同一组配方，两个附属都注册时按它决定留谁（腐竹 2026-10-26：
     * 「先到先得确实得改」——改成按附属顺序，而不是服务器注册顺序）。
     */
    private int priority = Integer.MAX_VALUE;
    private int id = -1;
    private String fingerprint;

    public Recipe(ItemKey machine, Map<ItemKey, Integer> inputs,
                  Map<ItemKey, Integer> outputs, String source) {
        this(machine, inputs, outputs, source, 1);
    }

    public Recipe(ItemKey machine, Map<ItemKey, Integer> inputs,
                  Map<ItemKey, Integer> outputs, String source, int ticks) {
        this.machine = machine;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        this.source = source == null ? "unknown" : source;
        this.ticks = Math.max(1, ticks);
    }

    public ItemKey getMachine() { return machine; }
    public Map<ItemKey, Integer> getInputs() { return inputs; }
    public Map<ItemKey, Integer> getOutputs() { return outputs; }
    public String getSource() { return source; }
    public int getTicks() { return ticks; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getFingerprint() {
        if (fingerprint != null) return fingerprint;
        TreeSet<String> parts = new TreeSet<>();
        parts.add("M:" + machine);
        inputs.forEach((k, v) -> parts.add("I:" + k + ":" + v));
        outputs.forEach((k, v) -> parts.add("O:" + k + ":" + v));
        fingerprint = String.join("|", parts);
        return fingerprint;
    }

    /** 输入与输出有交集 = 自环 / 复制机 */
    public boolean isSelfLoop() {
        for (ItemKey k : inputs.keySet()) if (outputs.containsKey(k)) return true;
        return false;
    }

    @Override public String toString() {
        return machine + " " + inputs + " -> " + outputs;
    }
}
