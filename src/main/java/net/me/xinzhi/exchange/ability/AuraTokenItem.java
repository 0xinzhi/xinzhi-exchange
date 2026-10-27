package net.me.xinzhi.exchange.ability;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.util.Dialogs;
import net.me.xinzhi.exchange.util.Text;
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
 * 范围伤害护符：右键开关；潜行右键调半径（腐竹 2026-10-26）。
 *
 * <p>开启后<b>玩家的攻击带范围效果</b>：左键挥击（空挥也算）时对周围 r 格内生物结算一次伤害，
 * 持续期间每秒扣 1000 EMC（3 格起，半径每 +1 格消耗 ×10）。
 * 腐竹原话（m00483）：「范围攻击是让玩家的攻击有范围效果，不是出一个自动的攻击」。
 */
public final class AuraTokenItem extends SlimefunItem implements Listener {

    public static final String ID = "XZEX_AURA_CHARM";

    private final AbilityService abilities;

    public AuraTokenItem(ItemGroup group, SlimefunItemStack item,
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
        if (p.isSneaking()) {
            openRadius(p);
            return;
        }
        abilities.setAura(p, !abilities.isAuraOn(p));
    }

    private void openRadius(Player p) {
        ExchangeService svc = ExchangeService.get();
        if (svc == null) return;
        int base = svc.config().abilityAuraBaseRadius;
        int cur = abilities.radiusOf(p);
        Dialogs.amount(svc.plugin(), p,
                "§b§l攻击范围（格）",
                "§7当前 §e" + cur + " 格 §7· 每秒 §e" + Text.num(abilities.auraCostPerSecond(cur))
                        + " §7EMC（每 +1 格 ×" + Text.num(svc.config().abilityAuraRadiusMultiplier) + "）",
                cur, base, 10, 0, null,
                n -> abilities.setRadius(p, (int) Math.min(Integer.MAX_VALUE, n)));
    }
}
