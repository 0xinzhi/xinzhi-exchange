package net.me.xinzhi.exchange.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import net.me.xinzhi.exchange.core.ItemGuard;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动卖出机：每 5 秒把输入槽里能卖的东西按卖价卖掉，钱进机主余额，收 1% 手续费。
 *
 * <p>间隔做成 5 秒（100 tick）是腐竹定的：「有充足的时间进行 emc 值计算」。
 * 单价第一次用到时写进机器（{@link MachineUtil}），之后不再查价表。
 */
public final class AutoSellMachine extends AbstractMachine {

    /** 腐竹 2026-10-19：卖出机做成 45 格输入槽（54 格界面，最后一行 9 格放状态） */
    private static final int[] INPUT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };
    private static final int STATUS = 47, BALANCE = 48, HINT = 49;

    /** 周期计数放内存：腐竹 2026-10-26 要求机器别频繁记录，只有一直在操作的物品单价才写盘 */
    private final Map<Location, Integer> counters = new HashMap<>();
    /** 默认 1 秒一次；腐竹 2026-10-19 反馈 5 秒太慢，改成配置项 machine.sell.interval-ticks */

    public AutoSellMachine(ItemGroup group, SlimefunItemStack item,
                           RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                MachineUtil.setOwner(e.getBlock().getLocation(), e.getPlayer());
            }
        });
    }

    @Override
    protected String menuTitle() {
        return "§6自动卖出机 §8· §7按周期结算";
    }

    @Override
    protected void initMenu(@Nonnull BlockMenuPreset preset) {
        for (int i = 45; i < 54; i++) {
            preset.addItem(i, LOCKED_PANE, (p, s, item, action) -> false);
        }
        preset.addItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7待机"), (p, s, item, action) -> false);
        preset.addItem(BALANCE, new CustomItemStack(Material.EMERALD, "§6机主余额", "§7—"), (p, s, item, action) -> false);
        preset.addItem(HINT, new CustomItemStack(Material.HOPPER, "§e用法",
                "§7把要卖的东西放进上面 45 格",
                "§7自动按 §f卖价 §7卖出",
                "§7手续费 §f1%", "§8无价 / 改过的物品不动"), (p, s, item, action) -> false);
    }

    @Override
    public void onMenuOpen(@Nonnull BlockMenu menu, @Nonnull Block b) {
        render(menu, b.getLocation(), "§7待机", 0);
    }

    @Override
    public void onTick(@Nonnull Block b, @Nonnull SlimefunItem item,
                       com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData data) {
        Location loc = b.getLocation();
        try {
            ExchangeService svc0 = ExchangeService.get();
            int interval = svc0 == null ? 20 : Math.max(1, svc0.config().machineSellInterval);
            int cd = counters.merge(loc, 1, Integer::sum);
            if (cd < interval) return;
            counters.put(loc, 0);
            sell(b);
        } catch (Throwable ignored) {
        }
    }

    private void sell(Block b) {
        Location loc = b.getLocation();
        BlockMenu menu = MachineDrops.menu(loc);
        if (menu == null) return;
        UUID owner = MachineUtil.owner(loc);
        if (owner == null) {
            render(menu, loc, "§c未登记主人：由放置者打开一次", 0);
            return;
        }

        double gross = 0;
        long count = 0;
        for (int slot : INPUT) {
            // 老机器可能还是 27 格界面（尺寸在第一次建菜单时就定了）：越界直接跳过
            ItemStack it;
            try {
                it = menu.getItemInSlot(slot);
            } catch (Throwable t) {
                continue;
            }
            if (it == null || it.getType().isAir()) continue;
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            if (!ItemGuard.pristine(k, it)) continue;
            double unit = MachineUtil.sellUnit(loc, k);
            if (!(unit > 0)) continue;
            gross += unit * it.getAmount();
            count += it.getAmount();
            menu.replaceExistingItem(slot, null);
        }
        if (count == 0) {
            render(menu, loc, "§7输入区没有可卖的东西", 0);
            return;
        }
        double net = gross * (1 - MachineUtil.FEE);
        ExchangeService svc = ExchangeService.get();
        if (svc != null) svc.manager().balances().add(owner, net);
        render(menu, loc, "§a已卖出 §f" + count + " §a件", net);
    }

    private void render(BlockMenu menu, Location loc, String status, double lastGain) {
        if (menu == null) return;
        UUID owner = MachineUtil.owner(loc);
        double balance = 0;
        ExchangeService svc = ExchangeService.get();
        if (svc != null && owner != null) balance = svc.manager().balances().get(owner);
        try {
            menu.replaceExistingItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7" + status));
            menu.replaceExistingItem(BALANCE, new CustomItemStack(Material.EMERALD, "§6机主余额 §f" + fmt(balance),
                    lastGain > 0 ? "§7本次入账 §f" + fmt(lastGain) : "§7—"));
        } catch (Throwable ignored) {
        }
    }

    private static String fmt(double v) {
        return String.format("%,.2f", v);
    }

    @Override
    protected int[] storageSlotsForDrop() {
        return INPUT;
    }

    /** 物流接入（腐竹 2026-10-28）：0~44 是输入槽，其他附属的运输/物流设备可以往里塞物品。 */
    @Override
    public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
        return flow == ItemTransportFlow.INSERT ? INPUT.clone() : new int[0];
    }

}
