package net.me.xinzhi.exchange.recipe;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.price.PriceBook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把<b>原版配方</b>转成价图能用的 {@link Recipe}（腐竹 2026-10-29）。
 *
 * <h2>为什么要它</h2>
 * <p>价图（{@code RecipeRegistry}）里只有粘液科技的配方，原版一条都不进 ——
 * 于是价格引擎对原版物品只会用锚点，配方推导对它们完全不起作用。
 * 结果是「石英 2 块、石英块却是 0/36」这种对不上的价。
 *
 * <p>这里用 {@link Bukkit#recipeIterator()} 把工作台 / 熔炉 / 高炉 / 烟熏炉 /
 * 切石机 / 锻造台 / 营火 / 数据包配方全扫一遍，转成机器=对应原版工作台的配方。
 * 原版工作台在 {@code isFreeMachine} 里算「随手可得」，不设机器门槛。
 *
 * <h2>多选材料怎么处理</h2>
 * <p>一条配方里某个格子可能是「任意木板」这类多选。全组合会爆炸，
 * 所以生成「基准（每格取第一个）+ 每个格子逐个换一遍其它选项」这些变体，
 * 引擎本来就取最便宜的那条，于是近似取到了「最省的做法」。
 */
public final class VanillaRecipeExtractor {

    private static volatile List<Recipe> cache;

    private VanillaRecipeExtractor() {}

    /** 扫一遍并缓存（主线程调用；结果不会因为后面注册而失效） */
    @SuppressWarnings("deprecation")
    public static synchronized List<Recipe> collected() {
        List<Recipe> c = cache;
        if (c != null) return c;
        List<Recipe> out = new ArrayList<>(2048);
        try {
            Iterator<org.bukkit.inventory.Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                org.bukkit.inventory.Recipe r;
                try {
                    r = it.next();
                } catch (Throwable t) {
                    continue;
                }
                try {
                    convert(r, out);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        c = Collections.unmodifiableList(out);
        cache = c;
        return c;
    }

    /** 把原版配方注册进配方库（幂等：同指纹只会留一条）；返回新注册条数 */
    public static int ensureRegistered(RecipeRegistry registry) {
        if (registry == null) return 0;
        int n = 0;
        for (Recipe r : collected()) {
            try {
                if (registry.register(r)) n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /**
     * 只在缓存已经建好时注册（供异步回调 / 重建路径调用，不会在主线程之外触发 recipeIterator）。
     * 启动时先在主线程调一次 {@link #ensureRegistered(RecipeRegistry)} 把缓存建起来。
     */
    public static void ensureRegisteredIfReady(RecipeRegistry registry) {
        List<Recipe> c = cache;
        if (c == null || registry == null) return;
        for (Recipe r : c) {
            try {
                registry.register(r);
            } catch (Throwable ignored) {
            }
        }
    }

    public static int size() { return collected().size(); }

    // ============================================================

    private static void convert(org.bukkit.inventory.Recipe r, List<Recipe> out) {
        if (r instanceof ShapedRecipe s) {
            convertShaped(s, out);
        } else if (r instanceof ShapelessRecipe s) {
            convertShapeless(s, out);
        } else if (r instanceof CookingRecipe<?> c) {
            Material stationType = Material.FURNACE;
            if (c instanceof org.bukkit.inventory.BlastingRecipe) stationType = Material.BLAST_FURNACE;
            else if (c instanceof org.bukkit.inventory.SmokingRecipe) stationType = Material.SMOKER;
            else if (c instanceof org.bukkit.inventory.CampfireRecipe) stationType = Material.CAMPFIRE;
            ItemKey machine = station(stationType);
            convertSimple(machine, c.getInputChoice(), c.getResult(), "vanilla-cooking", 200, out);
        } else if (r instanceof StonecuttingRecipe sc) {
            convertSimple(station(Material.STONECUTTER), sc.getInputChoice(), sc.getResult(),
                    "vanilla-stonecutting", 1, out);
        } else if (r instanceof SmithingTransformRecipe st) {
            ItemKey result = key(st.getResult());
            if (result == null) return;
            List<List<ItemKey>> opts = new ArrayList<>();
            List<Integer> amounts = new ArrayList<>();
            addChoice(opts, amounts, st.getTemplate());
            addChoice(opts, amounts, st.getBase());
            addChoice(opts, amounts, st.getAddition());
            variants(station(Material.SMITHING_TABLE), opts, amounts, result,
                    Math.max(1, st.getResult().getAmount()), "vanilla-smithing", 1, out);
        }
        // 交易 / 数据包自定义配方 / 修剪锻造：没有稳定的材料→产物关系，跳过
    }

    private static void convertShaped(ShapedRecipe s, List<Recipe> out) {
        ItemKey result = key(s.getResult());
        if (result == null) return;
        String[] shape = s.getShape();
        Map<Character, RecipeChoice> choices = s.getChoiceMap();
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (String row : shape) {
            for (int i = 0; i < row.length(); i++) {
                char ch = row.charAt(i);
                if (ch == ' ') continue;
                counts.merge(ch, 1, Integer::sum);
            }
        }
        List<List<ItemKey>> opts = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (Map.Entry<Character, Integer> e : counts.entrySet()) {
            List<ItemKey> o = options(choices.get(e.getKey()));
            if (o.isEmpty()) return;
            opts.add(o);
            amounts.add(e.getValue());
        }
        variants(station(Material.CRAFTING_TABLE), opts, amounts, result,
                Math.max(1, s.getResult().getAmount()), "vanilla-shaped", 1, out);
    }

    private static void convertShapeless(ShapelessRecipe s, List<Recipe> out) {
        ItemKey result = key(s.getResult());
        if (result == null) return;
        List<List<ItemKey>> opts = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (RecipeChoice ch : s.getChoiceList()) {
            List<ItemKey> o = options(ch);
            if (o.isEmpty()) return;
            opts.add(o);
            amounts.add(1);
        }
        variants(station(Material.CRAFTING_TABLE), opts, amounts, result,
                Math.max(1, s.getResult().getAmount()), "vanilla-shapeless", 1, out);
    }

    private static void convertSimple(ItemKey machine, RecipeChoice in, ItemStack result,
                                      String source, int ticks, List<Recipe> out) {
        ItemKey res = key(result);
        List<ItemKey> o = options(in);
        if (res == null || o.isEmpty() || machine == null) return;
        List<List<ItemKey>> opts = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        opts.add(o);
        amounts.add(1);
        variants(machine, opts, amounts, res, Math.max(1, result.getAmount()), source, ticks, out);
    }

    // ============================================================

    private static void addChoice(List<List<ItemKey>> opts, List<Integer> amounts, RecipeChoice c) {
        List<ItemKey> o = options(c);
        if (o.isEmpty()) return;
        opts.add(o);
        amounts.add(1);
    }

    private static void variants(ItemKey machine, List<List<ItemKey>> opts, List<Integer> amounts,
                                 ItemKey result, int resultCount, String source, int ticks,
                                 List<Recipe> out) {
        if (machine == null || opts.isEmpty()) return;
        out.add(build(machine, opts, amounts, result, resultCount, source, ticks, -1));
        for (int slot = 0; slot < opts.size(); slot++) {
            List<ItemKey> o = opts.get(slot);
            for (int i = 1; i < o.size(); i++) {
                out.add(build(machine, opts, amounts, result, resultCount, source, ticks, slot, i));
            }
        }
    }

    private static Recipe build(ItemKey machine, List<List<ItemKey>> opts, List<Integer> amounts,
                                ItemKey result, int resultCount, String source, int ticks,
                                int overrideSlot) {
        return build(machine, opts, amounts, result, resultCount, source, ticks, overrideSlot, 0);
    }

    private static Recipe build(ItemKey machine, List<List<ItemKey>> opts, List<Integer> amounts,
                                ItemKey result, int resultCount, String source, int ticks,
                                int overrideSlot, int overrideIndex) {
        Map<ItemKey, Integer> in = new LinkedHashMap<>();
        for (int i = 0; i < opts.size(); i++) {
            List<ItemKey> o = opts.get(i);
            ItemKey k = (i == overrideSlot) ? o.get(Math.min(overrideIndex, o.size() - 1)) : o.get(0);
            if (k == null) continue;
            in.merge(k, amounts.get(i), Integer::sum);
        }
        if (in.isEmpty()) in.put(result, 1);
        Map<ItemKey, Integer> outs = new LinkedHashMap<>();
        outs.put(result, Math.max(1, resultCount));
        return new Recipe(machine, in, outs, source, ticks);
    }

    private static ItemKey station(Material m) {
        ItemKey k = ItemKey.vanilla(m);
        return k;
    }

    private static ItemKey key(ItemStack is) {
        if (is == null) return null;
        Material m = is.getType();
        if (m == null || m.isAir() || !m.isItem()) return null;
        if (PriceBook.isTechnical(m)) return null;
        return ItemKey.vanilla(m);
    }

    private static List<ItemKey> options(RecipeChoice choice) {
        if (choice == null) return List.of();
        List<ItemKey> out = new ArrayList<>(8);
        if (choice instanceof RecipeChoice.MaterialChoice mc) {
            for (Material m : mc.getChoices()) {
                ItemKey k = key(new ItemStack(m));
                if (k != null && !out.contains(k)) out.add(k);
            }
        } else if (choice instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack s : ec.getChoices()) {
                ItemKey k = key(s);
                if (k != null && !out.contains(k)) out.add(k);
            }
        }
        return out;
    }
}
