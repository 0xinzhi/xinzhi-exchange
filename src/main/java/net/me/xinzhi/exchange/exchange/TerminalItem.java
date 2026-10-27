package net.me.xinzhi.exchange.exchange;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 「等价交换」终端道具：右键打开兑换界面，潜行右键升级显示上限。
 *
 * <p>分阶（腐竹 2026-10-26）：
 * <ul>
 *   <li>{@link #ID_BASIC} 初级 —— 开局可做，只显示 100 EMC 以下，道具内最多升到 2000；</li>
 *   <li>{@link #ID} 中级 —— 原配方（要下界之星），初始 2000，道具内可无限升。</li>
 * </ul>
 * 上限只限制「显示 / 买」，卖出路径不看它。
 */
public final class TerminalItem extends SlimefunItem implements Listener {

    /** 中级终端：初始 2000，无封顶 */
    public static final String ID = "XZEX_TERMINAL";
    /** 初级终端：初始 100，封顶 2000 */
    public static final String ID_BASIC = "XZEX_TERMINAL_BASIC";

    private final long defaultCap;
    /** 升级封顶；{@link TradeCap#UNLIMITED} = 无封顶 */
    private final long capCeiling;

    public TerminalItem(ItemGroup group, SlimefunItemStack item,
                        RecipeType recipeType, ItemStack[] recipe,
                        long defaultCap, long capCeiling) {
        super(group, item, recipeType, recipe);
        this.defaultCap = defaultCap;
        this.capCeiling = capCeiling;
    }

    public long defaultCap() { return defaultCap; }
    public long capCeiling() { return capCeiling; }

    /** 物品 Lore：上限随时可变，所以由代码生成 */
    public static List<String> loreFor(long cap, long ceiling) {
        List<String> l = new ArrayList<>();
        l.add("§7右键打开等价交换界面");
        l.add("§7只显示 §f" + (cap == TradeCap.UNLIMITED ? "不限" : Text.num(cap)) + " §7EMC 以下的物品");
        if (ceiling == TradeCap.UNLIMITED || cap < ceiling) {
            l.add("§8潜行 + 右键：增加显示上限");
            if (ceiling != TradeCap.UNLIMITED) l.add("§8本级最高 " + Text.num(ceiling));
        } else {
            l.add("§8本级已到顶（" + Text.num(ceiling) + "）");
        }
        l.add("§8卖出不受上限限制");
        return l;
    }

    private void stamp(ItemStack item, long cap) {
        TradeCap.setCap(item, cap);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setLore(loreFor(cap, capCeiling));
        item.setItemMeta(meta);
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
        ExchangeService svc = ExchangeService.get();
        if (svc == null || !svc.isReady()) {
            p.sendMessage("§c[等价交换] 服务未就绪");
            return;
        }
        long cap = TradeCap.capOf(item, defaultCap);
        // 老物品（升级前发的）补一次 Lore；Lore 变了才写回手持槽
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !loreFor(cap, capCeiling).equals(meta.getLore())) {
            stamp(item, cap);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) {
                p.getInventory().setItemInOffHand(item);
            } else {
                p.getInventory().setItemInMainHand(item);
            }
        }

        if (p.isSneaking()) {
            openUpgrade(svc, p, item, cap);
            return;
        }
        svc.gui().open(p, 0, ExchangeGui.Sort.parse(svc.config().defaultSort, ExchangeGui.Sort.PRICE_DESC),
                null, ExchangeGui.Mode.MINE, cap);
    }

    /** 增量升级框：玩家填「加多少」，不是「升到多少」（腐竹 2026-10-26 订正） */
    private void openUpgrade(ExchangeService svc, Player p, ItemStack item, long cap) {
        if (capCeiling != TradeCap.UNLIMITED && cap >= capCeiling) {
            p.sendMessage("§c[等价交换] 这台终端已到顶（" + Text.num(capCeiling)
                    + " EMC）—— 想更高得用中级终端（下界之星）");
            return;
        }
        double balance = svc.manager().balance(p.getUniqueId());
        long maxAdd = (capCeiling == TradeCap.UNLIMITED) ? Long.MAX_VALUE : capCeiling - cap;
        long initial = Math.min(1_000L, maxAdd);
        long sliderMax = Math.min(10_000L, maxAdd);
        // 中级（无封顶）：输入框不限；初级：输入框也截到 maxAdd
        long hardMax = (capCeiling == TradeCap.UNLIMITED) ? 0L : maxAdd;
        Dialogs.amount(svc.plugin(), p,
                "§b§l增加显示上限",
                "§7这一次加多少上限？（每 +1 点要 §e" + Text.num(svc.config().terminalUpgradeEmcPerCap)
                        + " EMC§7，你有 §e" + Text.num(balance) + "§7；当前上限 §e" + Text.num(cap) + "§7）",
                initial, 1L, sliderMax, hardMax, null,
                add -> addTo(svc, p, item, cap, add));
    }

    private void addTo(ExchangeService svc, Player p, ItemStack item, long cap, long add) {
        if (add <= 0) {
            p.sendMessage("§c[等价交换] 要增加的上限得是正数");
            return;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        boolean offHand = false;
        if (SlimefunItem.getByItem(held) != this) {
            held = p.getInventory().getItemInOffHand();
            offHand = true;
        }
        if (SlimefunItem.getByItem(held) != this) {
            p.sendMessage("§c[等价交换] 请把终端拿在手上再升级");
            return;
        }
        if (capCeiling != TradeCap.UNLIMITED && cap + add > capCeiling) {
            add = capCeiling - cap;
            p.sendMessage("§c[等价交换] 这台终端最高只能到 " + Text.num(capCeiling) + "，本次按 +"
                    + Text.num(add) + " 算");
        }
        if (add <= 0) return;
        long t = cap + add;
        double cost = (double) add * svc.config().terminalUpgradeEmcPerCap;
        double balance = svc.manager().balance(p.getUniqueId());
        if (balance < cost) {
            p.sendMessage("§c[等价交换] EMC 不够：要 §e" + Text.num(cost) + "§c，你只有 §e" + Text.num(balance));
            return;
        }
        svc.manager().addBalance(p.getUniqueId(), -cost);
        stamp(held, t);
        if (offHand) p.getInventory().setItemInOffHand(held);
        else p.getInventory().setItemInMainHand(held);
        p.sendMessage("§a[等价交换] 显示上限 §e" + Text.num(cap) + " §a+§e" + Text.num(add)
                + " §a→ §e" + Text.num(t) + "§a，花费 §e" + Text.num(cost) + " §aEMC（剩余 §e"
                + Text.num(svc.manager().balance(p.getUniqueId())) + "§a）");
    }
}
