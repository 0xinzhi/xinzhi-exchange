package net.me.xinzhi.exchange.ability;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 飞行护符：右键开关飞行，开启时每秒扣 EMC（腐竹 2026-10-26：500 EMC/s）。
 *
 * <p>道具必须在背包里（关掉/丢掉就自动失效，见 {@link AbilityService#tickFlight}）。
 */
public final class FlightTokenItem extends SlimefunItem implements Listener {

    public static final String ID = "XZEX_FLIGHT_CHARM";

    private final AbilityService abilities;

    public FlightTokenItem(ItemGroup group, SlimefunItemStack item,
                           RecipeType recipeType, ItemStack[] recipe, AbilityService abilities) {
        super(group, item, recipeType, recipe);
        this.abilities = abilities;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (SlimefunItem.getByItem(item) != this) return;

        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);

        Player p = e.getPlayer();
        if (!p.hasPermission("xzex.use")) {
            p.sendMessage("§c[等价交换] 你没有权限使用等价交换");
            return;
        }
        abilities.setFlight(p, !abilities.isFlightOn(p));
    }
}
