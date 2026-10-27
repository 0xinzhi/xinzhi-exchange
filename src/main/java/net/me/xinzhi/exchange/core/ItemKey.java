package net.me.xinzhi.exchange.core;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品唯一标识：{@code SF:ID} 或 {@code VANILLA:MATERIAL_NAME}。
 *
 * <p>本附属自带一份（不依赖主附属的同名类）：相同字符串全局共享实例。
 */
public final class ItemKey implements Comparable<ItemKey> {

    private static final String SF_PREFIX = "SF:";
    private static final String VANILLA_PREFIX = "VANILLA:";

    private static final ConcurrentHashMap<String, ItemKey> POOL = new ConcurrentHashMap<>(512);

    private final String key;
    private final int hash;
    private transient volatile String cachedName;
    private transient volatile ItemStack cachedTemplate;

    private ItemKey(String key) {
        this.key = key;
        this.hash = key.hashCode();
    }

    public static ItemKey slimefun(String id) {
        return id == null ? null : parse(SF_PREFIX + id);
    }

    public static ItemKey vanilla(Material mat) {
        return mat == null ? null : parse(VANILLA_PREFIX + mat.name());
    }

    public static ItemKey parse(String s) {
        if (s == null) return null;
        ItemKey e = POOL.get(s);
        if (e != null) return e;
        return POOL.computeIfAbsent(s, ItemKey::new);
    }

    public static Collection<ItemKey> pooledKeys() {
        return Collections.unmodifiableCollection(POOL.values());
    }

    public static ItemKey of(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf != null) return slimefun(sf.getId());
        return vanilla(item.getType());
    }

    public static ItemKey of(SlimefunItem sf) {
        return sf == null ? null : slimefun(sf.getId());
    }

    /** 存档字符串还原（会校验前缀与材质名） */
    public static ItemKey fromStored(String s) {
        if (s == null) return null;
        try {
            if (s.startsWith(SF_PREFIX)) return slimefun(s.substring(SF_PREFIX.length()));
            if (s.startsWith(VANILLA_PREFIX)) {
                Material m = Material.matchMaterial(s.substring(VANILLA_PREFIX.length()));
                return m == null ? null : vanilla(m);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public boolean isSlimefun() { return key.startsWith(SF_PREFIX); }
    public boolean isVanilla() { return key.startsWith(VANILLA_PREFIX); }

    public String getSfId() {
        return isSlimefun() ? key.substring(SF_PREFIX.length()) : null;
    }

    public Material getMaterial() {
        return isVanilla() ? Material.matchMaterial(key.substring(VANILLA_PREFIX.length())) : null;
    }

    /** 模板物品（数量 1）：用于渲染图标 */
    public ItemStack toTemplate() {
        ItemStack t = templateRef();
        return t == null ? null : t.clone();
    }

    /** 模板的只读引用（调用方不得修改） */
    public ItemStack templateRef() {
        ItemStack c = cachedTemplate;
        if (c != null) return c;
        if (isSlimefun()) {
            SlimefunItem sf = SlimefunItem.getById(getSfId());
            if (sf == null) return null;
            ItemStack built = sf.getItem().clone();
            built.setAmount(1);
            cachedTemplate = built;
            return built;
        }
        Material m = getMaterial();
        if (m == null) return null;
        ItemStack built;
        try {
            built = new ItemStack(m);
        } catch (Throwable t) {
            // ⚠ 有些材质 isItem() 为真但 ItemStack 构造仍抛
            //   （1.21.8 实测：SPRUCE_WALL_HANGING_SIGN "isn't an item"）。
            //   这类材质没有可发放的物品形态，直接当作"渲染不出来"。
            return null;
        }
        built.setAmount(1);
        cachedTemplate = built;
        return built;
    }

    /**
     * 显示名：粘液物品 → 粘液名；原版 → 中文名；都拿不到时退回 id。
     *
     * <p>⚠ 解析失败的结果<b>不进缓存</b>：附属可能比我们晚注册物品，
     * 缓存了兜底值就永远是 id（这是主附属踩过的坑）。
     */
    public String getDisplayName() {
        String c = cachedName;
        if (c != null) return c;

        String resolved;
        if (isSlimefun()) {
            SlimefunItem sf = SlimefunItem.getById(getSfId());
            resolved = sf != null ? sf.getItemName() : fallbackSfName(getSfId());
        } else {
            Material m = getMaterial();
            resolved = m != null ? ItemNames.of(m) : null;
        }
        if (resolved == null || resolved.isEmpty()) {
            return isSlimefun() ? getSfId() : key;
        }
        cachedName = resolved;
        return resolved;
    }

    /** 粘液 id 查不到时的替代名字（原版同名 / 虚拟 RT_ 机器），没有就 null */
    private static String fallbackSfName(String sfId) {
        try {
            Material m = Material.matchMaterial(sfId);
            if (m != null && m.isItem() && !m.isAir()) return ItemNames.of(m);
            if (sfId.startsWith("RT_") && sfId.length() > 3) {
                SlimefunItem real = SlimefunItem.getById(sfId.substring(3));
                if (real != null) return "【配方】" + real.getItemName();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override public String toString() { return key; }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemKey k)) return false;
        return k.hash == hash && k.key.equals(key);
    }
    @Override public int compareTo(ItemKey o) { return key.compareTo(o.key); }
}
