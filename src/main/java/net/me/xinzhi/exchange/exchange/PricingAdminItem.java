package net.me.xinzhi.exchange.exchange;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 两个管理道具（从主附属搬来，id 换成 XZEX_ 以便共存）：
 * {@code XZEX_PRICE_CUSTOM}（价格定制）/ {@code XZEX_PRICE_BATCH}（批量定价）。
 *
 * <p>无配方、只有 {@code xzex.admin}（或 OP）能开。
 */
public final class PricingAdminItem extends SlimefunItem implements Listener {

    public enum Kind { CUSTOM, BATCH }

    private final Kind kind;
    private final PricingAdminGui gui;

    public PricingAdminItem(ItemGroup group, SlimefunItemStack item, RecipeType type,
                            ItemStack[] recipe, Kind kind, PricingAdminGui gui) {
        super(group, item, type, recipe);
        this.kind = kind;
        this.gui = gui;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR
                && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isItem(e.getItem())) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("xzex.admin") && !p.isOp()) {
            p.sendMessage("§c[等价交换] 需要 §fxzex.admin §c权限");
            return;
        }
        if (kind == Kind.CUSTOM) gui.openCustom(p, 0);
        else gui.openBatch(p, 0);
    }
}
