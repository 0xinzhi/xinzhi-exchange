package net.me.xinzhi.exchange.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetProvider;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import net.me.xinzhi.exchange.core.ItemGuard;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.util.ChatInput;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.UUID;

/**
 * EMC 发电机：消耗 EMC 换电量，1 EMC = 500 J（腐竹 2026-10-19 定的比例）。
 *
 * <p>EMC 两个来源：
 * <ol>
 *   <li>输入槽放物品 —— 按卖价折算成 EMC；</li>
 *   <li>点「直接充 EMC」—— 从机主余额扣指定数量。</li>
 * </ol>
 *
 * <p>电量直接写进 Slimefun 的 {@code energy-charge}：<b>不接能源调节器时可以超过容量上限</b>；
 * 一旦接进能源网络，网络的「多余电量回存」会按 {@code getCapacity()} 截断 ——
 * 正好是腐竹实测到的现象（超出部分被抹去）。
 */
public final class EmcGenerator extends AbstractMachine implements EnergyNetProvider {

    private static final int[] INPUT = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int STATUS = 18, POOL = 19, CHARGE = 20, RECHARGE = 21, HINT = 22,
            OVERLOAD = 23;
    /** 过载持续时间：1 分钟（腐竹 2026-10-19） */
    private static final long OVERLOAD_MS = 60_000L;

    public EmcGenerator(ItemGroup group, SlimefunItemStack item,
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
        return "§6EMC 发电机 §8· §71 EMC = 500 J";
    }

    @Override
    protected void initMenu(@Nonnull BlockMenuPreset preset) {
        for (int i = 18; i < 27; i++) {
            preset.addItem(i, LOCKED_PANE, (p, s, item, action) -> false);
        }
        preset.addItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7待机"), (p, s, item, action) -> false);
        preset.addItem(POOL, new CustomItemStack(Material.EMERALD, "§6EMC 池", "§7—"), (p, s, item, action) -> false);
        preset.addItem(CHARGE, new CustomItemStack(Material.REDSTONE, "§6电量", "§7—"), (p, s, item, action) -> false);
        preset.addItem(RECHARGE, new CustomItemStack(Material.GOLD_INGOT, "§e直接充 EMC", "§7点击输入数量",
                "§7从机主余额扣"), (p, s, item, action) -> false);
        preset.addItem(OVERLOAD, new CustomItemStack(Material.TNT, "§c过载输出",
                "§7点击后 1 分钟内持续满输出",
                "§71 分钟后强制把电量归零",
                "§7中途电量耗尽则自动恢复",
                "§8慎用：归零不可撤销"), (p, s, item, action) -> false);
        preset.addItem(HINT, new CustomItemStack(Material.HOPPER, "§e用法",
                "§7输入槽放东西 → 按卖价折 EMC",
                "§7EMC 每 tick 换成电量 §f1:500",
                "§8不接调节器：电量可超上限",
                "§8接调节器：超出部分会被抹掉"), (p, s, item, action) -> false);
    }

    @Override
    public void onMenuOpen(@Nonnull BlockMenu menu, @Nonnull Block b) {
        Location loc = b.getLocation();
        menu.addMenuClickHandler(OVERLOAD, (p, slot, item, action) -> {
            UUID owner = MachineUtil.ownerOrClaim(loc, p);
            if (owner == null || !owner.equals(p.getUniqueId())) {
                p.sendMessage("§c[发电机] 只有机主能过载");
                return false;
            }
            long until = MachineUtil.longInfo(loc, "xz-ovl-until", 0);
            if (until > System.currentTimeMillis()) {
                p.sendMessage("§e[发电机] 过载已在进行中，剩余 "
                        + ((until - System.currentTimeMillis()) / 1000) + " 秒");
                return false;
            }
            MachineUtil.set(loc, "xz-ovl-until", String.valueOf(System.currentTimeMillis() + OVERLOAD_MS));
            // 一次性把总电量全部写进 energy-charge（允许超过 int 上限；
            // 1 分钟后强制归零就是为了防止这个"无限电量"长期存在）
            long total = MachineUtil.longInfo(loc, "xz-j", 0);
            StorageCacheUtils.setData(loc, "energy-charge", String.valueOf(total));
            MachineUtil.set(loc, "xz-mirror", String.valueOf(total));
            p.sendMessage("§c[发电机] 过载开始：全部电量已输出（" + fmt(total)
                    + " J），60 秒后强制归零");
            render(menu, loc, "§c过载中");
            return false;
        });
        menu.addMenuClickHandler(RECHARGE, (p, slot, item, action) -> {
            UUID owner = MachineUtil.ownerOrClaim(loc, p);
            if (owner == null || !owner.equals(p.getUniqueId())) {
                p.sendMessage("§c[发电机] 只有机主能充 EMC");
                return false;
            }
            p.closeInventory();
            ChatInput.request(ExchangeService.get().plugin(), p,
                    "§e[发电机] 输入要充入的 EMC 数量（从余额扣），输入 cancel 取消：",
                    msg -> {
                        ExchangeService svc = ExchangeService.get();
                        if (svc == null) return;
                        try {
                            double v = Double.parseDouble(msg.trim());
                            if (!(v > 0)) {
                                p.sendMessage("§c[发电机] 数量必须大于 0");
                                return;
                            }
                            double balance = svc.manager().balances().get(owner);
                            if (balance < v) {
                                p.sendMessage("§c[发电机] 余额不足：现有 " + fmt(balance) + " EMC");
                                return;
                            }
                            svc.manager().balances().add(owner, -v);
                            addPool(loc, (long) Math.floor(v));
                            p.sendMessage("§a[发电机] 已充入 " + fmt(Math.floor(v)) + " EMC");
                        } catch (Throwable t) {
                            p.sendMessage("§c[发电机] 这不是一个数字");
                        }
                    });
            return false;
        });
        render(menu, loc, "§7待机");
    }

    @Override
    public void onTick(@Nonnull Block b, @Nonnull SlimefunItem item, SlimefunBlockData data) {
        Location loc = b.getLocation();
        try {
            BlockMenu menu = MachineDrops.menu(loc);
            if (menu == null) return;
            ExchangeService svc = ExchangeService.get();
            if (svc == null) return;

            // 过载：到点强制把电量（总池 + 对外镜像）全部归零，然后恢复普通发电
            long until = MachineUtil.longInfo(loc, "xz-ovl-until", 0);
            if (until > 0 && System.currentTimeMillis() >= until) {
                MachineUtil.set(loc, "xz-j", "0");
                MachineUtil.set(loc, "xz-mirror", "0");
                StorageCacheUtils.setData(loc, "energy-charge", "0");
                MachineUtil.set(loc, "xz-ovl-until", "0");
                render(menu, loc, "§c过载结束，电量已归零");
                return;
            }

            long pool = MachineUtil.longInfo(loc, "xz-emc", 0);
            pool += convertInputs(menu, loc);

            long ratio = Math.max(1, svc.config().machineGenJoulesPerEmc);
            long made = 0;
            long joules = MachineUtil.longInfo(loc, "xz-j", 0);
            if (pool > 0) {
                made = (pool > Long.MAX_VALUE / ratio) ? Long.MAX_VALUE / 2 : pool * ratio;
                joules = (joules > Long.MAX_VALUE - made) ? Long.MAX_VALUE : joules + made;
                pool = 0;
            }

            // energy-charge 只放 int 安全值（镜像）。⚠ 超过 int 上限会让别的机器用
            // getCharge()（int）时读到饱和值，扣电永远扣不掉 —— 腐竹 2026-10-19 报的
            // 「电量变无限、扣除失败」就是这个原因。真正的总电量放在 xz-j（long）。
            long cap = getCapacityLong();
            long mirrorNow = getChargeLong(loc);
            long lastMirror = MachineUtil.longInfo(loc, "xz-mirror", mirrorNow);
            if (mirrorNow < lastMirror) {
                // 能源网/别的机器把镜像电量消耗（或因超出被抹掉）了，从总池里扣掉
                joules = Math.max(0, joules - (lastMirror - mirrorNow));
            }
            // 过载中：不夹 int 安全值，直接把总电量全灌进 energy-charge；
            // 普通状态：只放 min(总电量, 单次上限)，避免别的机器 int 读饱和后扣不掉电
            boolean over = until > System.currentTimeMillis();
            long mirrorTarget = over ? joules : Math.min(joules, cap);
            if (mirrorTarget != mirrorNow) {
                StorageCacheUtils.setData(loc, "energy-charge", String.valueOf(mirrorTarget));
            }
            MachineUtil.set(loc, "xz-mirror", String.valueOf(mirrorTarget));
            MachineUtil.set(loc, "xz-j", String.valueOf(joules));
            MachineUtil.set(loc, "xz-emc", String.valueOf(pool));

            // 过载期间：电量池被抽干就提前结束（回到普通运作）
            if (over && joules <= 0 && mirrorTarget <= 0) {
                MachineUtil.set(loc, "xz-ovl-until", "0");
            }

            int cd = MachineUtil.intInfo(loc, "xz-cd", 0) + 1;
            if (cd >= 2) {
                cd = 0;
                render(menu, loc, over ? "§c过载输出中" : (made > 0 ? "§a发电中" : "§7待机"));
            }
            MachineUtil.set(loc, "xz-cd", String.valueOf(cd));
        } catch (Throwable ignored) {
        }
    }

    private long convertInputs(BlockMenu menu, Location loc) {
        long emc = 0;
        for (int slot : INPUT) {
            ItemStack it = menu.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) continue;
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            if (!ItemGuard.pristine(k, it)) continue;
            double unit = MachineUtil.sellUnit(loc, k);
            if (!(unit > 0)) continue;
            emc += (long) Math.floor(unit * it.getAmount());
            menu.replaceExistingItem(slot, null);
        }
        return emc;
    }

    private static void addPool(Location loc, long add) {
        long pool = MachineUtil.longInfo(loc, "xz-emc", 0);
        MachineUtil.set(loc, "xz-emc", String.valueOf(pool + add));
    }

    private void render(BlockMenu menu, Location loc, String status) {
        if (menu == null) return;
        long pool = MachineUtil.longInfo(loc, "xz-emc", 0);
        long joules = MachineUtil.longInfo(loc, "xz-j", 0);
        long mirror = getChargeLong(loc);
        long cap = getCapacityLong();
        try {
            menu.replaceExistingItem(STATUS, new CustomItemStack(Material.CLOCK, "§7状态", "§7" + status));
            menu.replaceExistingItem(POOL, new CustomItemStack(Material.EMERALD,
                    "§6EMC 池 §f" + fmt(pool), "§7输入物品或直接充 EMC"));
            menu.replaceExistingItem(CHARGE, new CustomItemStack(Material.REDSTONE,
                    "§6总电量 §f" + fmt(joules) + " J",
                    "§7对外供电 §f" + fmt(mirror) + " J",
                    "§7单次上限 " + fmt(cap) + " J",
                    joules > cap ? "§e超出部分留在机器里，按需补上" : "§7在容量内"));
            long until = MachineUtil.longInfo(loc, "xz-ovl-until", 0);
            if (until > System.currentTimeMillis()) {
                menu.replaceExistingItem(OVERLOAD, new CustomItemStack(Material.TNT, "§c过载中",
                        "§7剩余 §f" + ((until - System.currentTimeMillis()) / 1000) + " §7秒",
                        "§8结束后电量强制归零"));
            } else {
                menu.replaceExistingItem(OVERLOAD, new CustomItemStack(Material.TNT, "§c过载输出",
                        "§7点击后 1 分钟内持续满输出",
                        "§71 分钟后强制把电量归零",
                        "§7中途电量耗尽则自动恢复",
                        "§8慎用：归零不可撤销"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.0f", v);
    }

    // ---------------- EnergyNetProvider ----------------

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.GENERATOR;
    }

    /**
     * 对外供电的单次上限（energy-charge 镜像的容量）。
     *
     * <p>⚠ 故意夹在 {@link Integer#MAX_VALUE} 以内：超过 int 上限后，别的机器用
     * {@code getCharge()}（int）会读到饱和值，扣电永远扣不掉（腐竹 2026-10-19 报的 bug）。
     * 机器自己的总电量走 {@code xz-j}（long），不受这个上限限制。
     */
    @Override
    public long getCapacityLong() {
        ExchangeService svc = ExchangeService.get();
        long v = svc == null ? 2_000_000_000L : svc.config().machineGenCapacity;
        return Math.max(1L, Math.min(v, Integer.MAX_VALUE - 1L));
    }

    @Override
    public int getCapacity() {
        return (int) getCapacityLong();
    }

    @Override
    public boolean isChargeable() {
        return true;
    }

    @Override
    public long getGeneratedOutputLong(Location loc, SlimefunBlockData data) {
        return 0L;   // 电量由本机自己写进 energy-charge，不额外凭空产出
    }

    @Override
    protected int[] storageSlotsForDrop() {
        return INPUT;
    }
}
