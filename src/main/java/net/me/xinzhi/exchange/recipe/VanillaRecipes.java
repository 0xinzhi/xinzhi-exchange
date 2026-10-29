package net.me.xinzhi.exchange.recipe;

import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.price.PriceBook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 原版配方索引（腐竹 2026-10-29：「原版物品中没有合成配方的物品，让我自行定价」）。
 *
 * <h2>它是干什么的</h2>
 * <p>本附属的配方库只收 <b>粘液科技</b> 的配方（{@code RecipeExtractor}），原版配方一条都不进
 * —— 所以原版物品的价永远来自锚点（定价表 / prices.yml / 手工价），配方推导对它们不起作用。
 * 于是「哪些原版物品必须手工定价」就等于「哪些原版材质拿不到任何原版配方」。
 *
 * <p>这里用服务端的 {@link Bukkit#recipeIterator()} 把<b>所有</b>已注册配方扫一遍
 * （工作台 / 熔炉 / 高炉 / 烟熏炉 / 切石机 / 锻造台 / 营火 / 数据包配方），
 * 记下「每个材质能被几条配方产出」。{@link #withoutRecipe()} 就是反过来：一条都没有的那些。
 *
 * <h2>为什么不是「只能靠 Player#crafting」</h2>
 * <p>熔炉能出的东西（铁锭、玻璃、木炭…）也算「有配方」—— 对定价来说它们不是根，
 * 只是本附属暂时没把这条边接进价图。
 */
public final class VanillaRecipes {

    /** 材质 → 能产出它的原版配方条数 */
    private static volatile Map<Material, Integer> sources = Collections.emptyMap();
    private static volatile List<Material> cache;
    private static volatile Map<Material, Integer> cacheFor;
    private static volatile boolean failed;
    private static volatile int totalRecipes;

    private VanillaRecipes() {}

    /**
     * 重扫原版配方索引（要在主线程调）。
     *
     * <p>拿不到结果时（旧服务端没有 recipeIterator 之类）只置 {@link #failed()}，
     * 调用方会看到「所有原版物品都无配方」这种最坏情况 —— 所以宁可把异常吞掉也要留下索引。
     */
    @SuppressWarnings("deprecation")
    public static synchronized void rescan() {
        Map<Material, Integer> idx = new EnumMap<>(Material.class);
        int total = 0;
        boolean bad = false;
        try {
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe r;
                try {
                    r = it.next();
                } catch (Throwable t) {
                    continue;
                }
                if (r == null) continue;
                ItemStack out;
                try {
                    out = r.getResult();
                } catch (Throwable t) {
                    continue;
                }
                if (out == null) continue;
                Material m = out.getType();
                if (m == null || m.isAir()) continue;
                idx.merge(m, 1, Integer::sum);
                total++;
            }
        } catch (Throwable t) {
            bad = true;
        }
        sources = idx;
        totalRecipes = total;
        failed = bad;
        cache = null;
        cacheFor = null;
    }

    private static Map<Material, Integer> index() {
        Map<Material, Integer> s = sources;
        if (s == null || (s.isEmpty() && totalRecipes == 0)) {
            rescan();
            s = sources;
        }
        return s == null ? Collections.emptyMap() : s;
    }

    /** 扫过了没有；false = 还没扫或扫失败（此时 {@link #withoutRecipe()} 不可信） */
    public static boolean scanned() { return !failed && totalRecipes > 0; }

    /** 扫失败过（服务端不给配方迭代器） */
    public static boolean failed() { return failed; }

    /** 索引里一共有多少条原版配方 */
    public static int totalRecipes() { return totalRecipes; }

    /** 有多少个材质能被至少一条原版配方产出 */
    public static int indexedMaterials() { return sources.size(); }

    /** 这个材质能被几条原版配方产出（0 = 无配方） */
    public static int recipeCount(Material m) {
        if (m == null) return 0;
        Integer v = index().get(m);
        return v == null ? 0 : v;
    }

    /** 有没有任何原版配方能产出它 */
    public static boolean hasRecipe(Material m) { return recipeCount(m) > 0; }

    /**
     * 原版物品里<b>没有任何配方</b>能产出的那些 —— 价只能手工给。
     *
     * <p>过滤口径和价表一致：{@code isItem()} / 非空气 / 非技术方块 / 渲染得出物品形态
     * （见 {@link PriceBook}）。结果按枚举顺序返回，调用方自己排序。
     */
    public static synchronized List<Material> withoutRecipe() {
        Map<Material, Integer> idx = index();
        if (cache != null && cacheFor == idx) return cache;
        List<Material> out = new ArrayList<>(256);
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir()) continue;
            if (PriceBook.isTechnical(m)) continue;
            Integer n = idx.get(m);
            if (n != null && n > 0) continue;
            ItemKey k = ItemKey.vanilla(m);
            if (k == null || k.templateRef() == null) continue;   // 渲染不出来的（挂在墙上的告示牌之类）
            out.add(m);
        }
        cache = Collections.unmodifiableList(out);
        cacheFor = idx;
        return cache;
    }
}
