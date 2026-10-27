package net.me.xinzhi.exchange.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemHandler;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;

/**
 * 方块机器统一基类（从主附属搬来的最小版）。
 *
 * <p>收编：BlockMenuPreset 骨架、BlockTicker、锁格、拆除掉落、权限判断。
 * 子类只填钩子，{@code preRegister()} 是 final —— 忘了调 super 的那类 bug 结构上不存在。
 */
public abstract class AbstractMachine extends SlimefunItem {

    protected static final ItemStack LOCKED_PANE =
            new CustomItemStack(Material.BLACK_STAINED_GLASS_PANE, " ");

    private boolean menuInstalled = false;
    private BlockMenuPreset menu;

    protected AbstractMachine(ItemGroup group, SlimefunItemStack item,
                              RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
    }

    // ---------------- 子类钩子 ----------------

    protected String menuTitle() { return null; }

    protected void initMenu(@Nonnull BlockMenuPreset preset) { }

    protected void onMenuOpen(@Nonnull BlockMenu menu, @Nonnull Block b) {
    }

    protected void onTick(@Nonnull Block b, @Nonnull SlimefunItem item, SlimefunBlockData data) { }

    protected boolean syncTick() { return true; }

    protected boolean hasTicker() { return true; }

    /** 拆机时要掉出来的格子（只报真正的存储格，展示格/锁格不许列） */
    protected int[] storageSlotsForDrop() { return new int[0]; }

    // ---------------- 基类默认实现 ----------------

    public boolean canOpen(@Nonnull Block b, @Nonnull Player p) {
        return canOpenBlock(b, p);
    }

    public static boolean canOpenBlock(@Nonnull Block b, @Nonnull Player p) {
        return p.hasPermission("slimefun.inventory.bypass")
                || Slimefun.getProtectionManager().hasPermission(
                        p, b.getLocation(), Interaction.INTERACT_BLOCK);
    }

    public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
        return new int[0];
    }

    protected static void lockAllSlots(@Nonnull BlockMenuPreset preset) {
        for (int i = 0; i < 54; i++) {
            preset.addItem(i, LOCKED_PANE, (p, s, item, action) -> false);
        }
    }

    @Override
    public final void preRegister() {
        installMenu();
        if (hasTicker()) {
            addItemHandler(new BlockTicker() {
                @Override
                public void tick(@Nonnull Block b, @Nonnull SlimefunItem sf, SlimefunBlockData data) {
                    onTick(b, sf, data);
                }

                @Override
                public boolean isSynchronized() {
                    return syncTick();
                }
            });
        }
        installDropHandler();
    }

    private void installDropHandler() {
        int[] slots;
        try {
            slots = storageSlotsForDrop();
        } catch (Throwable t) {
            return;
        }
        if (slots == null || slots.length == 0) return;
        if (hasOwnBreakHandler()) return;
        addItemHandler(MachineDrops.dropHandler(this::storageSlotsForDrop));
    }

    private boolean hasOwnBreakHandler() {
        try {
            for (ItemHandler h : getHandlers()) {
                if (h instanceof BlockBreakHandler) return true;
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    protected final void installMenu() {
        if (menuInstalled) return;
        menuInstalled = true;

        final String title = menuTitle();
        if (title == null) return;

        menu = new BlockMenuPreset(getId(), title) {
            @Override
            public void init() {
                initMenu(this);
            }

            @Override
            public void newInstance(@Nonnull BlockMenu m, @Nonnull Block b) {
                onMenuOpen(m, b);
            }

            @Override
            public boolean canOpen(@Nonnull Block b, @Nonnull Player p) {
                return AbstractMachine.this.canOpen(b, p);
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return AbstractMachine.this.getSlotsAccessedByItemTransport(flow);
            }
        };
    }

    protected final BlockMenuPreset menu() { return menu; }
}
