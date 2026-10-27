package net.me.xinzhi.exchange.menu;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 超大菜单的模型：稠密格子表 + 区间/单格赋值 + 点击回调。
 *
 * <p>本附属自带这一份（不依赖主附属）：独立版要能在只装正常版主附属、甚至不装主附属的服上
 * 单独把菜单下发给客户端 mod。</p>
 */
public final class MenuModel {

    private final String title;
    private final int cols;
    private final int rows;
    private final SlotRole[] roles;
    private final ItemStack[] items;
    private final Map<Integer, Consumer<MenuClick>> handlers = new HashMap<>();

    public MenuModel(String title, int cols, int rows) {
        if (cols < 1 || rows < 1) throw new IllegalArgumentException("菜单尺寸必须 ≥ 1×1");
        long total = (long) cols * rows;
        if (total > 16384) throw new IllegalArgumentException("菜单总格数最大 16384");
        this.title = title == null ? "" : title;
        this.cols = cols;
        this.rows = rows;
        this.roles = new SlotRole[(int) total];
        this.items = new ItemStack[(int) total];
        Arrays.fill(this.roles, SlotRole.EMPTY);
    }

    public String title() { return title; }
    public int cols() { return cols; }
    public int rows() { return rows; }
    public int size() { return roles.length; }

    public SlotRole roleAt(int slot) {
        return (slot < 0 || slot >= roles.length) ? SlotRole.EMPTY : roles[slot];
    }

    public ItemStack itemAt(int slot) {
        return (slot < 0 || slot >= items.length) ? null : items[slot];
    }

    public Consumer<MenuClick> handlerAt(int slot) { return handlers.get(slot); }

    public MenuModel set(int slot, SlotRole role, ItemStack item) {
        check(slot);
        roles[slot] = role == null ? SlotRole.EMPTY : role;
        items[slot] = item == null ? null : item.clone();
        return this;
    }

    public MenuModel onClick(int slot, Consumer<MenuClick> handler) {
        check(slot);
        if (handler == null) handlers.remove(slot);
        else handlers.put(slot, handler);
        return this;
    }

    private void check(int slot) {
        if (slot < 0 || slot >= roles.length) {
            throw new IllegalArgumentException("格序号越界 " + slot + "（共 " + roles.length + " 格）");
        }
    }
}
