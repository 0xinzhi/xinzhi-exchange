package net.me.xinzhi.exchange.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配方提取器 —— 沿用主附属的四条来源，但修掉了它踩过的两个坑。
 *
 * <h2>四条来源</h2>
 * <ol>
 *   <li><b>API</b>：机器的 {@code getRecipes() / getMachineRecipes() / getRecipeList()}；</li>
 *   <li><b>多方块</b>：{@link MultiBlockMachine} 的「成对 ItemStack[]」存储，
 *       走官方 {@code RecipeType.getRecipeInputList / getRecipeOutputList}；</li>
 *   <li><b>物品自身配方</b>：{@code SlimefunItem.getRecipeType() + getRecipe()}。
 *       只作<b>兜底</b>（见下）；</li>
 *   <li><b>反射</b>：{@code templates[].recipes[]} 两级结构。</li>
 * </ol>
 *
 * <h2>⭐ 修掉的两个坑（腐竹 2026-10-18 报告）</h2>
 * <h3>坑 1：RT_ 虚拟机器与真实机器互相顶掉</h3>
 * <p>主附属的配方指纹不含机器名，所以「同一组输入→同一组输出」只留先注册的一台。
 * 线上 14,866 条配方里实测有 <b>1,586 组</b> 冲突，被顶掉的常常是真实机器 ——
 * 症状就是「正常机器没记下配方、RT_ 开头的机器多出一堆配方」。
 * 修法：指纹加入机器（见 {@link Recipe}），不同机器各留各的。
 *
 * <h3>坑 2：RT_ 配方的「材料数 / 产出数」对不上</h3>
 * <p>「物品自身配方」这条腿输出数量<b>写死为 1</b>，但当物品是由机器生产的、
 * 而那台机器一次产出 N 个时，同一条配方在真实机器上是「…→ N 个」。
 * 实测例子：{@code SF:ADVANCED_CARD} 真实配方是「2 钻石 + 2 铁锭 + … → 2 个」，
 * RT_ 那条却是「… → 1 个」，价格直接翻倍；{@code LOGITECH_ABSTRACT_INGOT}
 * 真实一次产 4 个，RT_ 也写成 1 个。
 *
 * <p>修法：这条腿<b>降级成兜底</b> —— 只有在「该物品没有任何真实机器配方」时才补一条；
 * 有真实配方的物品一律以真实配方为准。这样写死的 1 不会再和一个正确的 N 竞争。
 *
 * <p>另外：配方类型能解析到<b>真实机器</b>时，机器键直接用真实机器
 * （{@code RT_ENHANCED_CRAFTING_TABLE} → {@code ENHANCED_CRAFTING_TABLE}），
 * 不再制造只有展示用途的虚拟机器键。
 */
public final class RecipeExtractor {

    /** 这些配方类型不是「合成」，不能当造价来源 */
    private static final Set<String> NON_CRAFT_TYPES = Set.of(
            "null", "mob_drop", "barter_drop", "interact", "geo_miner",
            "block_destroy", "fishing", "quest", "world_generating",
            "food_composter");

    /** 名字里带这些的配方类型是「信息展示」，同样不是合成 */
    private static final String[] NON_CRAFT_PREFIXES = {"haiman_info", "interfaced"};

    private static final String[] RECIPE_GETTERS =
            {"getRecipes", "getMachineRecipes", "getRecipeList"};

    private static volatile boolean multiblockEnabled = true;
    private static volatile boolean ownRecipesEnabled = true;

    private RecipeExtractor() {}

    public static void setMultiblockEnabled(boolean v) { multiblockEnabled = v; }
    public static void setOwnRecipesEnabled(boolean v) { ownRecipesEnabled = v; }
    public static boolean isOwnRecipesEnabled() { return ownRecipesEnabled; }

    /**
     * 主线程预热：不少附属的配方 getter 带静态初始化副作用（会调 Bukkit.addRecipe），
     * 在异步线程首次触发会被 Paper 的 AsyncCatcher 拦掉，那批配方就白读了。
     * 按 getter 的<b>声明类</b>去重，代价只有个位数次反射调用。
     */
    public static int warmUp(Collection<? extends SlimefunItem> machines) {
        if (machines == null || machines.isEmpty()) return 0;
        if (!Bukkit.isPrimaryThread()) return 0;
        Set<Class<?>> done = new HashSet<>();
        for (SlimefunItem machine : machines) {
            if (machine == null) continue;
            for (String name : RECIPE_GETTERS) {
                Method m;
                try {
                    m = machine.getClass().getMethod(name);
                } catch (NoSuchMethodException | SecurityException ignored) {
                    continue;
                }
                if (!done.add(m.getDeclaringClass())) break;
                try { m.invoke(machine); } catch (Throwable ignored) {}
                break;
            }
        }
        return done.size();
    }

    // ============================================================
    // 第 1 腿 + 2 腿 + 4 腿：真实机器配方
    // ============================================================

    /** 一台机器的真实配方（API / 多方块 / 反射）。空输入配方一律丢弃。 */
    public static List<Recipe> machineRecipes(SlimefunItem machine, ItemKey machineKey) {
        if (machine == null || machineKey == null) return List.of();
        Map<String, Recipe> dedup = new LinkedHashMap<>();
        tryCollect(apiExtract(machine, machineKey), dedup);
        tryCollect(reflectExtract(machine, machineKey), dedup);
        return new ArrayList<>(dedup.values());
    }

    private static void tryCollect(List<Recipe> in, Map<String, Recipe> out) {
        if (in == null) return;
        for (Recipe r : in) out.putIfAbsent(r.getFingerprint(), r);
    }

    private static List<Recipe> apiExtract(SlimefunItem machine, ItemKey machineKey) {
        List<Recipe> result = new ArrayList<>();
        for (String m : RECIPE_GETTERS) {
            List<Recipe> got = invokeGetter(machine, m, machineKey);
            if (!got.isEmpty()) {
                result.addAll(got);
                break;
            }
        }
        result.addAll(multiblockExtract(machine, machineKey));
        return result;
    }

    private static List<Recipe> invokeGetter(SlimefunItem machine, String m, ItemKey machineKey) {
        try {
            Method method = machine.getClass().getMethod(m);
            Object raw = method.invoke(machine);
            if (raw instanceof Collection<?> col) {
                List<Recipe> result = new ArrayList<>();
                for (Object r : col) {
                    Recipe parsed = parseRecipeObject(r, machineKey);
                    if (parsed != null) result.add(parsed);
                }
                return result;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {}
        return Collections.emptyList();
    }

    private static List<Recipe> multiblockExtract(SlimefunItem machine, ItemKey machineKey) {
        if (!multiblockEnabled) return Collections.emptyList();
        if (!(machine instanceof MultiBlockMachine mb)) return Collections.emptyList();
        List<Recipe> out = new ArrayList<>();
        List<ItemStack[]> inputs;
        try {
            inputs = RecipeType.getRecipeInputList(mb);
        } catch (Throwable t) {
            return out;
        }
        if (inputs == null) return out;

        for (ItemStack[] in : inputs) {
            if (in == null) continue;
            Map<ItemKey, Integer> ins = new LinkedHashMap<>();
            collectItems(in, ins, true);
            if (ins.isEmpty()) continue;

            ItemStack outStack;
            try {
                outStack = RecipeType.getRecipeOutputList(mb, in);
            } catch (Throwable t) {
                continue;
            }
            if (outStack == null || outStack.getType() == Material.AIR) continue;
            ItemKey ok = ItemKey.of(outStack);
            if (ok == null) continue;

            Map<ItemKey, Integer> outs = new LinkedHashMap<>();
            outs.put(ok, Math.max(1, outStack.getAmount()));
            out.add(new Recipe(machineKey, ins, outs, "[MultiBlock]"));
        }
        return out;
    }

    private static List<Recipe> reflectExtract(SlimefunItem machine, ItemKey machineKey) {
        try {
            List<?> templates = (List<?>) readField(machine, "templates");
            if (templates == null) templates = (List<?>) readField(machine, "recipes");
            if (templates == null) return Collections.emptyList();
            List<Recipe> result = new ArrayList<>();
            for (Object tpl : templates) {
                List<?> recipeList = (List<?>) readField(tpl, "recipes");
                if (recipeList == null) recipeList = (List<?>) readField(tpl, "recipeList");
                if (recipeList == null) continue;
                for (Object r : recipeList) {
                    Map<ItemKey, Integer> ins = new LinkedHashMap<>();
                    Map<ItemKey, Integer> outs = new LinkedHashMap<>();
                    collectItems(readField(r, "input"), ins, true);
                    collectItems(readField(r, "output"), outs, false);
                    if (outs.isEmpty() || ins.isEmpty()) continue;
                    Object rawTicks = readField(r, "ticks");
                    int t = rawTicks instanceof Number n ? Math.max(1, n.intValue()) : 1;
                    result.add(new Recipe(machineKey, ins, outs, "[Reflect]", t));
                }
            }
            return result;
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    private static Recipe parseRecipeObject(Object recipe, ItemKey machineKey) {
        if (recipe == null) return null;
        Map<ItemKey, Integer> inputs = new LinkedHashMap<>();
        Map<ItemKey, Integer> outputs = new LinkedHashMap<>();
        collectItems(invokeAny(recipe, new String[]{"getInput", "getInputs", "getIngredients"}), inputs, true);
        // ★ 古代祭坛：新版 Slimefun 的 AltarRecipe 把「祭坛中心催化剂」单独存字段，
        //   getInput() 只返回 8 个基座料 —— 少读 getCatalyst() 就会漏掉 3×3 配方里的第 5 格
        //   （腐竹 2026-10-18 第七轮：「只获取了8种材料，合成配方中的第5种材料没有获得」）。
        //   催化剂在合成时同样被消耗，所以它也是一条材料成本。
        if (isAltarRecipe(recipe)) {
            collectItems(invokeAny(recipe, new String[]{"getCatalyst", "getCatalystItem"}), inputs, true);
        }
        collectItems(invokeAny(recipe, new String[]{"getOutput", "getOutputs", "getResult"}), outputs, false);
        if (outputs.isEmpty() || inputs.isEmpty()) return null;
        return new Recipe(machineKey, inputs, outputs, "[API]", readTicks(recipe));
    }

    /**
     * 配方耗时（tick）。
     *
     * <p>腐竹 2026-10-26：「应该有合成时间吧，我看读取配方功能也能看到有 tick 标注，
     * 读不到 tick 就按 1 tick 算。」Slimefun 的 {@code MachineRecipe#getTicks()} 就是那个数，
     * 主附属的配方界面也是这么显示的（「§7耗时: §f" + recipe.getTicks() + " tick」）。
     */
    private static int readTicks(Object recipe) {
        Object v = invokeAny(recipe, new String[]{"getTicks", "getTick", "getTime", "getCraftingTime"});
        if (v instanceof Number n) return Math.max(1, n.intValue());
        if (v != null) {
            try { return Math.max(1, Integer.parseInt(String.valueOf(v).trim())); } catch (Throwable ignored) {}
        }
        return 1;
    }

    // ============================================================
    // 第 3 腿：物品自身配方（兜底）
    // ============================================================

    /**
     * 「这个物品是怎么合成出来的」—— 只在物品没有真实机器配方时调用。
     *
     * <p>输出数量固定 1：这一腿只认「工作台式配方」（9 格网格 → 1 个产物）。
     * 机器型配方（一次产 N 个）由真实机器那条腿负责，所以这里不会与之竞争。
     *
     * @return 配方；配方类型不是合成 / 为空 / 输入为空时返回 null
     */
    public static Recipe ownRecipe(SlimefunItem item, ItemKey itemKey) {
        if (!ownRecipesEnabled || item == null || itemKey == null) return null;
        try {
            RecipeType type = item.getRecipeType();
            if (type == null) return null;

            String typeId = null;
            try {
                if (type.getKey() != null) typeId = type.getKey().getKey();
            } catch (Throwable ignored) {}
            if (typeId == null || typeId.isEmpty()) return null;

            String lower = typeId.toLowerCase(Locale.ROOT);
            if (NON_CRAFT_TYPES.contains(lower)) return null;
            for (String p : NON_CRAFT_PREFIXES) if (lower.startsWith(p)) return null;

            ItemStack[] arr = item.getRecipe();
            if (arr == null || arr.length == 0) return null;

            Map<ItemKey, Integer> ins = new LinkedHashMap<>();
            collectItems(arr, ins, true);
            if (ins.isEmpty()) return null;
            if (ins.containsKey(itemKey)) return null;   // 自环，别当配方

            Map<ItemKey, Integer> outs = new LinkedHashMap<>();
            outs.put(itemKey, 1);

            // 能解析到真实机器就用真实机器，否则保留 RT_ 键（只作展示 / 兜底）
            String upper = typeId.toUpperCase(Locale.ROOT);
            ItemKey machine = null;
            try {
                SlimefunItem real = SlimefunItem.getById(upper);
                if (real != null) machine = ItemKey.of(real);
            } catch (Throwable ignored) {}
            if (machine == null) machine = ItemKey.slimefun("RT_" + upper);

            return new Recipe(machine, ins, outs, "[OwnRecipe:" + typeId + "]");
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================

    /** 祭坛配方（AltarRecipe / 附属自写的祭坛配方）—— 只有这类需要单独补催化剂 */
    private static boolean isAltarRecipe(Object recipe) {
        return recipe != null && recipe.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("altar");
    }

    private static Object invokeAny(Object obj, String[] methods) {
        for (String m : methods) {
            try {
                Method method = obj.getClass().getMethod(m);
                Object v = method.invoke(obj);
                if (v != null) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 把 ItemStack / 集合 / 数组里的物品累加进 sink（相同 key 数量相加） */
    private static void collectItems(Object raw, Map<ItemKey, Integer> sink, boolean strict) {
        if (raw == null) return;
        List<ItemStack> stacks = new ArrayList<>();
        if (raw instanceof ItemStack is) stacks.add(is);
        else if (raw instanceof Collection<?> col) {
            for (Object o : col) if (o instanceof ItemStack is) stacks.add(is);
        } else if (raw.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < len; i++) {
                Object o = java.lang.reflect.Array.get(raw, i);
                if (o instanceof ItemStack is) stacks.add(is);
            }
        }
        for (ItemStack is : stacks) {
            if (is == null || is.getType() == Material.AIR) continue;
            if (strict && !isValidInput(is)) continue;
            ItemKey k = ItemKey.of(is);
            if (k == null) continue;
            sink.merge(k, Math.max(1, is.getAmount()), Integer::sum);
        }
    }

    /** 排除玩家自定义名称 / Lore 的伪造物品 */
    private static boolean isValidInput(ItemStack is) {
        if (SlimefunItem.getByItem(is) != null) return true;
        var meta = is.getItemMeta();
        if (meta == null) return true;
        return !meta.hasDisplayName() && !meta.hasLore();
    }

    private static Object readField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
