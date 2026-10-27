package net.me.xinzhi.exchange.ability;

import net.me.xinzhi.exchange.config.Config;
import net.me.xinzhi.exchange.exchange.ExchangeManager;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能道具的服务端结算（腐竹 2026-10-26）。
 *
 * <ul>
 *   <li><b>飞行</b>：开启后 {@code setAllowFlight(true)}，每秒扣 {@code ability.flight.emc-per-second}；</li>
 *   <li><b>范围伤害</b>：开启后按半径 r 每秒扣 {@code base} × {@code multiplier^(r-3)}；
 *       玩家每次攻击（左键挥击，空挥也算）时对周围 r 格内生物结算一次伤害
 *       —— 腐竹 2026-10-26 订正：「范围攻击是让玩家的攻击有范围效果，不是出一个自动的攻击」。</li>
 * </ul>
 *
 * <p>扣费按秒（腐竹：「这个扣费又不直接显示，无需观感上的视觉连续」）。
 * 余额不足 → 自动关闭并提示。开关状态存在 {@code abilities.yml}，重启后恢复。
 *
 * <p>范围伤害<b>避开没有实体的生物</b>（腐竹原话：「注意避开一些无实体的生物。比如反作弊用的假人，
 * 盔甲架之类的」）：非 {@link LivingEntity}、盔甲架、带 {@code NPC} metadata（Citizens 假人）、
 * 无敌实体、以及配置里点名的类型都不打。
 */
public final class AbilityService implements Listener {

    /** 每人的开关状态 */
    public static final class State {
        public boolean flight;
        public boolean aura;
        public int radius;
    }

    private final JavaPlugin plugin;
    private final Config config;
    private final ExchangeManager manager;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** 上次挥击时间（毫秒），用于攻击触发的节流 */
    private final Map<UUID, Long> lastSwing = new ConcurrentHashMap<>();
    /** 我们发出去的飞行许可（只撤销自己发的，不动创造/权限来的飞行） */
    private final Set<UUID> flightGranted = ConcurrentHashMap.newKeySet();

    private final File file;
    private int taskId = -1;

    public AbilityService(JavaPlugin plugin, Config config, ExchangeManager manager) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.file = new File(plugin.getDataFolder(), "abilities.yml");
    }

    // ============================================================
    // 生命周期
    // ============================================================

    public void enable() {
        load();
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L).getTaskId();
    }

    public void disable() {
        if (taskId != -1) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
            taskId = -1;
        }
        for (UUID id : flightGranted) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) revokeFlight(p);
        }
        flightGranted.clear();
        save();
    }

    // ============================================================
    // 状态
    // ============================================================

    public State state(UUID id) {
        return states.computeIfAbsent(id, k -> new State());
    }

    public boolean isFlightOn(Player p) { return state(p.getUniqueId()).flight; }
    public boolean isAuraOn(Player p) { return state(p.getUniqueId()).aura; }

    public int radiusOf(Player p) {
        State st = state(p.getUniqueId());
        return st.radius <= 0 ? config.abilityAuraBaseRadius : st.radius;
    }

    public double flightCostPerSecond() { return config.abilityFlightEmcPerSecond; }

    /** 半径 r 的每秒消耗：base × multiplier^(r-3) */
    public double auraCostPerSecond(int radius) {
        int base = config.abilityAuraBaseRadius;
        int steps = Math.max(0, radius - base);
        return config.abilityAuraEmcPerSecond * Math.pow(config.abilityAuraRadiusMultiplier, steps);
    }

    /** 手里 / 背包里有没有这件功能道具（没有就自动关掉） */
    public static boolean hasItem(Player p, String slimefunId) {
        ItemStack[] contents = p.getInventory().getContents();
        for (ItemStack s : contents) {
            if (s == null || s.getType().isAir()) continue;
            var sf = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(s);
            if (sf != null && sf.getId().equals(slimefunId)) return true;
        }
        return false;
    }

    // ============================================================
    // 飞行
    // ============================================================

    public void setFlight(Player p, boolean on) {
        State st = state(p.getUniqueId());
        UUID id = p.getUniqueId();
        if (on) {
            if (!hasItem(p, FlightTokenItem.ID)) {
                p.sendMessage("§c[等价交换] 背包里没有飞行护符");
                return;
            }
            st.flight = true;
            p.setAllowFlight(true);
            flightGranted.add(id);
            p.sendMessage("§a[等价交换] 飞行已开启 §7（每秒 §e"
                    + Text.num(config.abilityFlightEmcPerSecond) + " §7EMC）");
        } else {
            st.flight = false;
            revokeFlight(p);
            p.sendMessage("§7[等价交换] 飞行已关闭");
        }
        save();
    }

    private void revokeFlight(Player p) {
        GameMode gm = p.getGameMode();
        boolean otherSource = gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || p.hasPermission("xzex.ability.fly");
        if (!otherSource) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        flightGranted.remove(p.getUniqueId());
    }

    // ============================================================
    // 范围伤害
    // ============================================================

    public void setAura(Player p, boolean on) {
        State st = state(p.getUniqueId());
        if (on) {
            if (!hasItem(p, AuraTokenItem.ID)) {
                p.sendMessage("§c[等价交换] 背包里没有范围伤害护符");
                return;
            }
            st.aura = true;
            if (st.radius <= 0) st.radius = config.abilityAuraBaseRadius;
            p.sendMessage("§a[等价交换] 攻击附带 §e" + st.radius + " 格 §a范围伤害已开启 §7（每秒 §e"
                    + Text.num(auraCostPerSecond(st.radius)) + " §7EMC，挥击时结算）");
        } else {
            st.aura = false;
            p.sendMessage("§7[等价交换] 范围伤害已关闭");
        }
        save();
    }

    public void setRadius(Player p, int radius) {
        State st = state(p.getUniqueId());
        st.radius = Math.max(1, radius);
        p.sendMessage("§a[等价交换] 攻击范围 = §e" + st.radius + " 格 §7（每秒 §e"
                + Text.num(auraCostPerSecond(st.radius)) + " §7EMC）");
        save();
    }

    // ============================================================
    // 每秒结算
    // ============================================================

    private void tick() {
        if (!plugin.isEnabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = states.get(p.getUniqueId());
            if (st == null) continue;
            if (st.flight) tickFlight(p, st);
            if (st.aura) tickAura(p, st);
        }
    }

    private void tickFlight(Player p, State st) {
        if (!hasItem(p, FlightTokenItem.ID)) {
            st.flight = false;
            revokeFlight(p);
            p.sendMessage("§c[等价交换] 背包里没有飞行护符，飞行已关闭");
            save();
            return;
        }
        double cost = config.abilityFlightEmcPerSecond;
        if (!charge(p, cost)) {
            st.flight = false;
            revokeFlight(p);
            p.sendMessage("§c[等价交换] EMC 不足（每秒 §e" + Text.num(cost) + "§c），飞行已关闭");
            save();
            return;
        }
        if (!p.getAllowFlight()) p.setAllowFlight(true);
        flightGranted.add(p.getUniqueId());
    }

    private void tickAura(Player p, State st) {
        if (!hasItem(p, AuraTokenItem.ID)) {
            st.aura = false;
            p.sendMessage("§c[等价交换] 背包里没有范围伤害护符，模式已关闭");
            save();
            return;
        }
        // 只扣费；伤害在玩家攻击时结算（见 onSwing）
        double cost = auraCostPerSecond(radiusOf(p));
        if (!charge(p, cost)) {
            st.aura = false;
            p.sendMessage("§c[等价交换] EMC 不足（每秒 §e" + Text.num(cost)
                    + "§c），范围伤害已关闭");
            save();
            return;
        }
    }

    private boolean charge(Player p, double cost) {
        if (cost <= 0) return true;
        double bal = manager.balance(p.getUniqueId());
        if (bal < cost) return false;
        manager.addBalance(p.getUniqueId(), -cost);
        return true;
    }

    /** 打范围内所有「有实体」的生物，避开盔甲架 / 假人 / 无敌实体 */
    private void hitAround(Player p, int radius) {
        double dmg = config.abilityAuraDamage;
        if (dmg <= 0) return;
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (e.getUniqueId().equals(p.getUniqueId())) continue;
            if (!(e instanceof LivingEntity le)) continue;
            if (e instanceof ArmorStand) continue;
            if (e instanceof Player) {
                if (!config.abilityAuraHitPlayers) continue;
                if (((Player) e).getGameMode() == GameMode.CREATIVE
                        || ((Player) e).getGameMode() == GameMode.SPECTATOR) continue;
            }
            if (le.isInvulnerable()) continue;
            // Citizens / 其它反作弊假人：带 NPC metadata
            if (e.hasMetadata("NPC") || e.hasMetadata("xzex_fake_player")) continue;
            if (config.abilityAuraIgnoredEntities.contains(e.getType().name())) continue;
            try {
                le.damage(dmg, p);
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================================================
    // 事件
    // ============================================================

    /**
     * 攻击触发：挥击（打实体 / 打空气都算）时，对周围 r 格内的生物造成一次范围伤害。
     *
     * <p>必须挂 {@link PlayerAnimationEvent#ARM_SWING}：{@code PlayerInteractEvent} 的 LEFT_CLICK_AIR
     * 在「左键打中实体」时不会触发，只有动画事件每次挥击都发。连点节流用 {@code getAttackCooldown()}
     * 加 {@code ability.aura.cooldown-ms}。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (p.getAttackCooldown() < 0.9F) return;
        State st = states.get(p.getUniqueId());
        if (st == null || !st.aura) return;
        if (!hasItem(p, AuraTokenItem.ID)) return;
        long now = System.currentTimeMillis();
        Long last = lastSwing.get(p.getUniqueId());
        if (last != null && now - last < config.abilityAuraCooldownMs) return;
        lastSwing.put(p.getUniqueId(), now);
        hitAround(p, radiusOf(p));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        State st = states.get(e.getPlayer().getUniqueId());
        if (st == null || !st.flight) return;
        // 重启后恢复：有道具、有余额就先给回飞行许可，下一拍再结算
        if (!hasItem(e.getPlayer(), FlightTokenItem.ID)) return;
        e.getPlayer().setAllowFlight(true);
        flightGranted.add(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // 退出时撤销我们发的飞行，免得离线状态残留 allowFlight
        revokeFlight(e.getPlayer());
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        e.getEntity().setAllowFlight(false);
        e.getEntity().setFlying(false);
    }

    // ============================================================
    // 持久化
    // ============================================================

    private void load() {
        states.clear();
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                State st = new State();
                st.flight = sec.getBoolean(key + ".flight", false);
                st.aura = sec.getBoolean(key + ".aura", false);
                st.radius = sec.getInt(key + ".radius", config.abilityAuraBaseRadius);
                states.put(id, st);
            } catch (Throwable ignored) {
            }
        }
    }

    public void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (var en : states.entrySet()) {
                State st = en.getValue();
                if (!st.flight && !st.aura && st.radius <= 0) continue;
                String path = "players." + en.getKey();
                y.set(path + ".flight", st.flight);
                y.set(path + ".aura", st.aura);
                y.set(path + ".radius", st.radius);
            }
            if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs()) return;
            y.save(file);
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
