package net.me.xinzhi.exchange.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 机器被拆 / 被炸时，把白名单格子里的东西还给玩家。
 *
 * <p>⚠ 只掉「真存储格」：Slimefun 的 BlockMenuPreset 会把锁格外观和状态卡写进真实格子，
 * 无脑掉 54 格 = 掉玻璃板，更严重的是把状态卡也掉出来等于刷物品。
 */
public final class MachineDrops {

    private MachineDrops() { }

    public static BlockBreakHandler dropHandler(Supplier<int[]> slots) {
        return new BlockBreakHandler(true, true) {
            @Override
            public void onPlayerBreak(BlockBreakEvent e, ItemStack tool, List<ItemStack> drops) {
                if (e.isCancelled()) return;
                Location loc = e.getBlock().getLocation();
                for (ItemStack it : take(loc, slots)) {
                    e.getBlock().getWorld().dropItemNaturally(loc, it);
                }
            }

            @Override
            public void onExplode(Block b, List<ItemStack> drops) {
                drops.addAll(take(b.getLocation(), slots));
            }
        };
    }

    private static List<ItemStack> take(Location loc, Supplier<int[]> supplier) {
        List<ItemStack> items = new ArrayList<>();
        if (loc == null || loc.getWorld() == null || supplier == null) return items;
        int[] slots;
        try {
            slots = supplier.get();
        } catch (Throwable t) {
            return items;
        }
        if (slots == null || slots.length == 0) return items;

        BlockMenu menu = menu(loc);
        if (menu == null) return items;

        boolean[] done = new boolean[54];
        for (int slot : slots) {
            if (slot < 0 || slot >= 54 || done[slot]) continue;
            done[slot] = true;
            ItemStack it;
            try {
                it = menu.getItemInSlot(slot);
            } catch (Throwable t) {
                continue;
            }
            if (it == null || it.getType() == Material.AIR) continue;
            items.add(it.clone());
            try {
                menu.replaceExistingItem(slot, null);
            } catch (Throwable ignored) {
            }
        }
        return items;
    }

    static BlockMenu menu(Location loc) {
        try {
            BlockMenu m = StorageCacheUtils.getMenu(loc);
            if (m != null) return m;
        } catch (Throwable ignored) {
        }
        try {
            return BlockStorage.getInventory(loc);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
