package net.me.xinzhi.exchange.exchange;

import io.github.thebusybiscuit.slimefun4.api.geo.GEOResource;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import net.me.xinzhi.exchange.XinZhiExchange;
import net.me.xinzhi.exchange.ability.AbilityService;
import net.me.xinzhi.exchange.ability.AuraTokenItem;
import net.me.xinzhi.exchange.ability.FlightTokenItem;
import net.me.xinzhi.exchange.api.EmcRegistry;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.config.Config;
import net.me.xinzhi.exchange.price.BlockDropPrices;
import net.me.xinzhi.exchange.price.MachineTier;
import net.me.xinzhi.exchange.price.MobDropPrices;
import net.me.xinzhi.exchange.price.PriceBook;
import net.me.xinzhi.exchange.price.PriceEngine;
import net.me.xinzhi.exchange.recipe.RecipeRegistry;
import net.me.xinzhi.exchange.recipe.RecipeService;
import net.me.xinzhi.exchange.recipe.VanillaRecipes;
import net.me.xinzhi.exchange.util.ChatInput;
import net.me.xinzhi.exchange.util.GuiItems;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生命周期与接线：价表 / 配方库 / 余额 / 界面 / 命令 / 终端道具。
 *
 * <p>启动流程：
 * <ol>
 *   <li>读 prices.yml（没有就导出内置表）；</li>
 *   <li>读 balance.yml；</li>
 *   <li>读磁盘配方库 → 立刻建一次价表（先可用）；</li>
 *   <li>延迟 {@code scan.delay-ticks} 后异步全量扫描（等其它附属注册完物品），
 *       扫完再建一次价表。</li>
 * </ol>
 */
public final class ExchangeService {

    private static ExchangeService INSTANCE;

    private final JavaPlugin plugin;
    private final Config config;
    private final PriceBook book;
    private final PriceEngine engine;
    private MachineTier tier;
    private final RecipeService recipes;
    private final BalanceStore balances;
    private final ExchangeManager manager;
    private final ExchangeGui gui;
    private final CraftGui craftGui;
    private final CraftPickerGui craftPicker;
    private final CraftPlanner planner;
    private final PlayerViewStore viewStore;
    private final PricingAdminGui pricingGui;
    private ExchangeGuiListener listener;
    private AbilityService abilities;
    private ItemGroup group;

    private volatile boolean ready = false;
    /** 价表版本：每次重建 +1；机器用它判断缓存的单价是否过期 */
    private volatile int priceVersion = 0;
    private int saveTaskId = -1;
    /** 第三方附属注册 EMC 的监听器（重建防抖） */
    private EmcRegistry.ChangeListener emcListener;
    private final java.util.concurrent.atomic.AtomicBoolean emcRebuildPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ExchangeService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = new Config(plugin);
        this.book = new PriceBook(plugin);
        this.engine = new PriceEngine();
        this.recipes = new RecipeService(plugin, config);
        this.balances = new BalanceStore(plugin);
        this.manager = new ExchangeManager(plugin, config, engine, balances, recipes.registry());
        this.viewStore = new PlayerViewStore(plugin);
        this.gui = new ExchangeGui(plugin, manager, viewStore);
        this.craftGui = new CraftGui(this);
        this.craftPicker = new CraftPickerGui(this, craftGui);
        this.planner = new CraftPlanner(engine, recipes.registry(), () -> manager.tradeMax());
        this.pricingGui = new PricingAdminGui(this);
        loadManualRefreshStamp();
        INSTANCE = this;
    }

    public static ExchangeService get() { return INSTANCE; }

    public boolean isReady() { return ready; }

    /** 机器缓存用的价格版本（价表重建 / 上限变化都会变） */
    public String priceVersionTag() {
        return priceVersion + ":" + (long) manager.tradeMax();
    }

    public org.bukkit.plugin.java.JavaPlugin plugin() { return plugin; }
    public ExchangeGui gui() { return gui; }
    public CraftGui craftGui() { return craftGui; }
    public CraftPickerGui craftPicker() { return craftPicker; }
    public CraftPlanner planner() { return planner; }
    public PlayerViewStore viewStore() { return viewStore; }
    public PricingAdminGui pricingGui() { return pricingGui; }
    public ExchangeManager manager() { return manager; }
    public PriceEngine engine() { return engine; }
    public PriceBook book() { return book; }
    public RecipeService recipes() { return recipes; }
    public RecipeRegistry registry() { return recipes.registry(); }
    public Config config() { return config; }

    /** 插件版本（plugin.yml 里由 Maven 过滤进来的 project.version）—— 给 /xzex info 对账用 */
    public String pluginVersion() {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (Throwable t) {
            return "?";
        }
    }
    public AbilityService abilities() { return abilities; }

    // ============================================================

    public void enable() {
        book.reload();

        // ★ 第三方附属的 EMC 注册（与主附属 api.EmcRegistry 同形）。
        // 它们多在独立版之后 enable，所以这里挂监听：注册后 1 秒合并重建一次价表。
        emcListener = added -> {
            if (!emcRebuildPending.compareAndSet(false, true)) return;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                emcRebuildPending.set(false);
                if (plugin.isEnabled()) rebuildPrices("附属注册 EMC（" + added.size() + " 条）");
            }, 20L);
        };
        EmcRegistry.addListener(emcListener);

        balances.load();
        try {
            tier = new MachineTier(plugin);
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 阶段判定器装配失败，本轮按单阶段定价: " + t);
        }

        // ★ 每次重启都全量重扫（腐竹 2026-10-18）：
        // 默认不读上一次的配方文件 —— 否则重启后的那几秒会用旧配方给出错价。
        // scan.reuse-disk: true 时才先读旧文件当临时价表（扫完照样覆盖）。
        int loaded = 0;
        if (!config.scanOnStart || config.reuseDisk) {
            loaded = recipes.load();
            if (loaded == 0) {
                plugin.getLogger().info("[等价交换] 磁盘上还没有配方库，等首次扫描");
            }
            rebuildPrices(config.reuseDisk ? "启动（旧配方库·临时，扫描后覆盖）" : "启动（磁盘配方库）");
        } else {
            plugin.getLogger().info("[等价交换] 启动策略：每次重启全量重扫，不沿用 recipes/ 里的旧文件"
                    + "（扫描完成前只显示基础定价表里的物品）");
            rebuildPrices("启动（仅基础定价表，等扫描）");
        }

        // 原版配方索引（腐竹 2026-10-29：用来挑出「原版·无配方」的物品，让它们能手工定价）
        VanillaRecipes.rescan();

        listener = new ExchangeGuiListener(plugin, manager, gui, viewStore);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        Bukkit.getPluginManager().registerEvents(craftGui, plugin);
        Bukkit.getPluginManager().registerEvents(craftPicker, plugin);
        ChatInput.init(plugin);

        registerItem();
        registerAbility();
        registerMachines();
        registerPricingItems();
        registerCommand();

        // 全量扫描（延迟，等其它附属把物品注册完）
        if (config.scanOnStart) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!plugin.isEnabled()) return;
                recipes.scanAsync(n -> {
                    rebuildPrices("启动扫描");
                    ready = true;
                    scheduleSettleScan();
                }, () -> {
                    // 扫描炸了：退回磁盘配方库，至少别让整个兑换功能没价
                    int n = recipes.load();
                    rebuildPrices("启动扫描失败，改用磁盘配方库");
                    plugin.getLogger().warning("[等价交换] 启动扫描失败，已回退到磁盘配方库: " + n + " 条");
                    ready = true;
                });
            }, config.scanDelayTicks);
        } else {
            ready = true;
        }

        // 余额定时落盘
        saveTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { balances.save(); } catch (Throwable ignored) {}
            try { viewStore.save(); } catch (Throwable ignored) {}
        }, 20L * 60 * 5, 20L * 60 * 5).getTaskId();

        ready = true;
        plugin.getLogger().info("[等价交换] 服务已启用（插件 id = XinZhiExchange，数据目录独立于 xinzhitech）");
    }

    public void disable() {
        ready = false;
        if (abilities != null) {
            try { abilities.disable(); } catch (Throwable ignored) {}
            abilities = null;
        }
        if (saveTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(saveTaskId); } catch (Throwable ignored) {}
            saveTaskId = -1;
        }
        if (emcListener != null) {
            EmcRegistry.removeListener(emcListener);
            emcListener = null;
        }
        try { balances.save(); } catch (Throwable ignored) {}
        try { viewStore.save(); } catch (Throwable ignored) {}
        INSTANCE = null;
        plugin.getLogger().info("[等价交换] 服务已关闭");
    }

    // ============================================================

    /**
     * 启动补扫：首次扫描完成后等 {@code scan.settle-ticks}，再全量扫一遍。
     *
     * <p>为什么要多扫一次（2026-10-18 实测）：有些附属启动后还在往配方表里填东西
     * （FinalTECH 最明显），扫太早会拿到半成品 —— 同一台服 +20 秒扫出 3,698 个有价、
     * +38 秒重扫变 3,899（差 201 个），配方条数只差 0~1 条，所以必须比<b>内容摘要</b>，
     * 不能只比条数。内容没变就不动价表（省一次重建，日志也说得清）。
     */
    private void scheduleSettleScan() {
        int ticks = config.settleTicks;
        if (ticks <= 0) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled()) return;
            if (recipes.isScanning()) {
                plugin.getLogger().info("[等价交换] 启动补扫跳过：上一轮扫描还没结束");
                return;
            }
            String before = recipes.registry().digest();
            int beforeCount = recipes.registry().size();
            int beforePriced = engine.pricedCount();
            plugin.getLogger().info("[等价交换] 启动补扫（首次扫描后 +" + (ticks / 20) + " 秒）：核对配方库是否已定型");
            recipes.scanAsync(n -> {
                if (recipes.registry().digest().equals(before)) {
                    plugin.getLogger().info("[等价交换] 启动补扫：配方库与首次扫描一致（" + n
                            + " 条），价表不动（有价 " + beforePriced + " 个）");
                    return;
                }
                rebuildPrices("启动补扫（配方库已定型）");
                plugin.getLogger().info("[等价交换] 启动补扫：配方库有变化（" + beforeCount + " → " + n
                        + " 条），价表已按定型结果重建：有价 " + beforePriced + " → " + engine.pricedCount() + " 个");
            }, null);
        }, ticks);
    }

    // ============================================================
    // 等价终端「刷新价表」的全服冷却（腐竹 2026-10-28：改成 2 小时、全服共用）
    // ============================================================

    private File refreshStampFile() { return new File(plugin.getDataFolder(), "refresh.yml"); }
    /** 上一次手动刷新的时间戳（毫秒，0 = 从没刷过）；写盘，重启后仍生效 */
    private long lastManualRefreshAt;

    /** 距离下次可手动刷新还剩多少毫秒；0 = 现在就能刷 */
    public long refreshCooldownLeft() {
        long cd = Math.max(0, config.terminalRefreshCooldownSeconds) * 1000L;
        if (cd <= 0) return 0L;
        return Math.max(0L, lastManualRefreshAt + cd - System.currentTimeMillis());
    }

    /** 记一次手动刷新（全服共用 + 落盘） */
    public void markManualRefresh() {
        lastManualRefreshAt = System.currentTimeMillis();
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    new org.bukkit.configuration.file.YamlConfiguration();
            y.set("last-manual-refresh", lastManualRefreshAt);
            y.save(refreshStampFile());
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 写 refresh.yml 失败: " + t);
        }
    }

    private void loadManualRefreshStamp() {
        try {
            File f = refreshStampFile();
            if (!f.isFile()) return;
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            lastManualRefreshAt = Math.max(0L, y.getLong("last-manual-refresh", 0L));
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读 refresh.yml 失败: " + t);
        }
    }

    /** 把毫秒差写成「1 小时 23 分」这种给玩家看的字 */
    public static String fmtCooldown(long ms) {
        long sec = Math.max(0L, (ms + 999L) / 1000L);
        long h = sec / 3600L, m = (sec % 3600L) / 60L, s = sec % 60L;
        if (h > 0) return h + " 小时" + (m > 0 ? " " + m + " 分" : "");
        if (m > 0) return m + " 分" + (s > 0 ? " " + s + " 秒" : "");
        return s + " 秒";
    }

    /** 重建价表（配方库 / 定价表变了就调） */
    public void rebuildPrices() { rebuildPrices("手动"); }

    public void rebuildPrices(String reason) {
        try {
            book.applyEmbedded();       // 运行期注册的附属值也要生效（不必重读 prices.yml）
            engine.rebuild(recipes.registry(), book.anchors(), config.staged, tier,
                    config.requireMachine ? this::isFreeMachine : null, geoDefaults(), dropDefaults(),
                    new PriceEngine.SellRules(config.compressedSellCap, config.compressedKeywords,
                            config.generatorSellRate, config.generatorKeywords));
            manager.refreshCatalog();
            planner.invalidate();
            priceVersion++;
            int total = recipes.registry().size();
            plugin.getLogger().info("[等价交换] 价表重建（" + reason + "）: 有价 "
                    + engine.pricedCount() + " 个"
                    + "（锚点 " + engine.anchorCount()
                    + " / 地理默认 " + engine.geoCount()
                    + " / 掉落兜底 " + engine.dropCount()
                    + " / 配方推导 " + engine.recipeCount()
                    + " / 卖价下调 " + engine.sellAdjustedCount()
                    + " / 算不出 " + engine.unpricedCount() + "）"
                    + "；配方 " + total + " 条 / " + recipes.registry().machines().size() + " 台机器"
                    + "；两阶段 " + (config.staged
                        ? "本体先定 " + engine.stage1Count() + " / 附属补 " + engine.stage2Count()
                        : "关")
                    + "；机器依赖 " + (config.requireMachine
                        ? "开（机器没价 ⇒ 它的配方不报价，跳过 " + engine.machineBlockedRecipes() + " 条）"
                        : "关")
                    + "；严格顺序（抬到早期附属配方）" + engine.orderForcedCount() + " 个物品"
                    + "；耗时 " + engine.lastBuildMillis() + " ms"
                    + "；手续费 " + config.tax);
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 价表重建失败（继续用旧价表）: " + t);
        }
    }

    /**
     * 这台机器是不是「随手可得」（不需要先算出价）：
     * <ul>
     *   <li>原版方块（{@code VANILLA:*}）—— 玩家自带；</li>
     *   <li>虚拟机器 {@code SF:RT_*}（多方块结构 / 原版工作台这类没有实物物品的配方类型）。</li>
     * </ul>
     * 其余真实机器都必须<b>先被定价</b>，它的配方才能给别的物品定价。
     *
     * <p>⚠ 不要用 {@code SlimefunItem.getById() == null} 兜底：本附属比很多附属先 enable，
     * 那时候它们的物品还没注册，会把一堆真实机器误判成「随手可得」，
     * 导致引导期价表和扫描后的价表对不上（实测 6,360 vs 5,108）。只认键本身的形态。
     */
    public boolean isFreeMachine(ItemKey machine) {
        if (machine == null) return true;
        if (!machine.isSlimefun()) return true;
        String id = machine.getSfId();
        return id != null && id.startsWith("RT_");
    }

    /**
     * 地理资源默认价（腐竹 2026-10-18 第七轮：「除非有手动定价否则地理资源都按30算」）。
     *
     * <p>地理资源由矿机产出、没有合成配方，按原定价链路永远算不出价 —— 补一条固定价，
     * 让它们能出现在界面里、也能被换入/换出。定价表与手工价优先（只补空缺）。
     *
     * <p>两处来源都要扫：附属注册的资源是 {@code SlimefunItem implements GEOResource}，
     * 粘液本体的资源则只挂在 {@code getGEOResources()} 那张表里、不在物品注册表。
     */
    private Map<ItemKey, Double> geoDefaults() {
        double v = config.geoResourcePrice;
        if (!(v > 0)) return null;
        Map<ItemKey, Double> out = new LinkedHashMap<>();
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (item instanceof GEOResource geo) putGeo(out, geo, v);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读取附属地理资源失败（不影响其它定价）: " + t);
        }
        try {
            for (Map.Entry<org.bukkit.NamespacedKey, GEOResource> e
                    : Slimefun.getRegistry().getGEOResources()) {
                putGeo(out, e.getValue(), v);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 所有「掉落兜底价」合起来：生物掉落（{@link MobDropPrices}）+ 破坏方块概率掉落
     * （{@link BlockDropPrices}）。同一个物品两边都给价时取更便宜的（更容易拿到的方式定价）。
     */
    private Map<ItemKey, Double> dropDefaults() {
        Map<ItemKey, Double> merged = new LinkedHashMap<>();
        Map<ItemKey, Double> mob = mobDropDefaults();
        if (mob != null) merged.putAll(mob);
        Map<ItemKey, Double> block = blockDropDefaults();
        if (block != null) {
            for (Map.Entry<ItemKey, Double> e : block.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                merged.merge(e.getKey(), e.getValue(), Math::min);
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    /**
     * 破坏方块概率掉落的兜底价（腐竹 2026-10-28）：RSC 子附属的物品常常只有
     * {@code items.yml} 里一句「挖某方块有概率掉」，没有配方 ⇒ 按配方链永远算不出价。
     *
     * <p>口径见 {@link BlockDropPrices#priceFor(int, boolean, BlockDropPrices.Rules)}：
     * 能反复刷取的方块按概率分档（≥30% → 5 / ≥5% → 15 / 更低 → 30），
     * 不可再生的按固定价（默认 100）。<b>只补空缺</b>。
     * 关掉用 {@code price.block-drop.enabled: false}。
     */
    private Map<ItemKey, Double> blockDropDefaults() {
        if (!config.blockDropPricing) return null;
        try {
            BlockDropPrices.Rules rules = new BlockDropPrices.Rules(
                    config.blockDropHighChance, config.blockDropHighEmc,
                    config.blockDropMidChance, config.blockDropMidEmc,
                    config.blockDropLowEmc, config.blockDropNonRenewableEmc,
                    new java.util.LinkedHashSet<>(config.blockDropRenewableBlocks));
            List<BlockDropPrices.Entry> entries =
                    BlockDropPrices.collect(plugin.getDataFolder().getParentFile(), rules);
            writeBlockDropReport(entries);
            if (entries.isEmpty()) {
                plugin.getLogger().info("[等价交换] 方块掉落兜底价: 没读到 drop_from 配置（没装 RSC 或不涉及）");
                return null;
            }
            Map<ItemKey, Double> out = BlockDropPrices.defaults(entries);
            plugin.getLogger().info("[等价交换] 方块掉落兜底价: 配置 " + entries.size() + " 条 / 涉及物品 "
                    + out.size() + " 个（只补配方算不出的缺口）");
            return out;
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读取方块掉落配置失败（不影响其它定价）: " + t);
            return null;
        }
    }

    /** 把方块掉落表导成一份人看的清单（插件家目录 block-drop-prices.yml），方便腐竹对账 / 提意见。 */
    private void writeBlockDropReport(List<BlockDropPrices.Entry> entries) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) return;
            List<BlockDropPrices.Entry> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(BlockDropPrices.Entry::addon, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(BlockDropPrices.Entry::itemId, String.CASE_INSENSITIVE_ORDER));
            StringBuilder sb = new StringBuilder(4096);
            sb.append("# XinZhiExchange 破坏方块掉落兜底价表（自动生成，仅供查看）\n");
            sb.append("# 口径（腐竹 2026-10-28）：可反复刷取的方块按概率 —— >=30% → 5 EMC / >=5% → 15 / 更低 → 30\n");
            sb.append("# 不可再生（挖了不掉回自身、也造不出来）→ ")
                    .append(Text.num(config.blockDropNonRenewableEmc)).append(" EMC\n");
            sb.append("# 只补「配方链算不出价」的物品；已经有价的物品不受影响。想改价用 /xzex set\n");
            String last = null;
            for (BlockDropPrices.Entry e : sorted) {
                if (!e.addon().equals(last)) {
                    last = e.addon();
                    sb.append('\n').append(last).append(":\n");
                }
                sb.append("  - ").append(e.itemId()).append("  # ").append(Text.plain(e.displayName()))
                        .append(" · 挖 ").append(e.blockName())
                        .append("（").append(e.renewable() ? "可再生" : "不可再生").append("）· ")
                        .append(e.chance()).append("% × ").append(e.amount())
                        .append(" → ").append(Text.num(e.price())).append(" EMC\n");
            }
            File out = new File(dir, "block-drop-prices.yml");
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
        } catch (Throwable ignored) {
            // 报表只是给人看的，写不出来不影响定价
        }
    }

    /**
     * 生物掉落物兜底价（腐竹 2026-10-26）：附属注册的特殊掉落物没配方、没锚点，
     * 按「配方链」永远算不出价。这里读粘液的 {@code getMobDrops()} 表，
     * 按掉落概率分档补一条价 —— <b>只补空缺</b>，不推翻已经算出来的价。
     *
     * <p>概率档位见 {@link MobDropPrices#priceFor(int, double)}；读不到概率的按
     * {@code price.mob-drop.unknown-emc}（默认 60）。
     * 关掉用 {@code price.mob-drop.enabled: false}。
     */
    private Map<ItemKey, Double> mobDropDefaults() {
        if (!config.mobDropPricing) return null;
        try {
            List<MobDropPrices.Entry> entries = MobDropPrices.collect(config.mobDropUnknownPrice);
            writeMobDropReport(entries);
            if (entries.isEmpty()) {
                plugin.getLogger().info("[等价交换] 掉落兜底价: 掉落表为空（没有附属注册 MOB_DROP，或读取失败）");
                return null;
            }
            Map<ItemKey, Double> out = MobDropPrices.defaults(entries);
            plugin.getLogger().info("[等价交换] 掉落兜底价: 掉落表 " + entries.size() + " 条 / 涉及物品 "
                    + out.size() + " 个（只补配方算不出的缺口）");
            return out;
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读取生物掉落表失败（不影响其它定价）: " + t);
            return null;
        }
    }

    /** 把掉落表导成一份人看的清单（插件家目录 mob-drop-prices.yml），方便腐竹对账 / 提意见。 */
    private void writeMobDropReport(List<MobDropPrices.Entry> entries) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) return;
            List<MobDropPrices.Entry> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing((MobDropPrices.Entry e) -> String.valueOf(e.entity().name()))
                    .thenComparing(MobDropPrices.Entry::displayName, String.CASE_INSENSITIVE_ORDER));
            StringBuilder sb = new StringBuilder(4096);
            sb.append("# XinZhiExchange 生物掉落物兜底价表（自动生成，仅供查看）\n");
            sb.append("# 口径（腐竹 2026-10-26）：概率 >=50% → 5 EMC / >=20% → 20 / >=10% → 100 / >=5% → 200 / <5% → 500\n");
            sb.append("# 读不到概率 → ").append(Text.num(config.mobDropUnknownPrice)).append(" EMC\n");
            sb.append("# 只补「配方链算不出价」的物品；已经有价的物品不受影响。想改价用 /xzex set\n");
            org.bukkit.entity.EntityType last = null;
            for (MobDropPrices.Entry e : sorted) {
                if (e.entity() != last) {
                    last = e.entity();
                    sb.append('\n').append(last.name()).append(":\n");
                }
                sb.append("  - ").append(e.item()).append("  # ").append(Text.plain(e.displayName()))
                        .append(" · ").append(e.chance() < 0 ? "概率读不到" : e.chance() + "%")
                        .append(" → ").append(Text.num(e.price())).append(" EMC\n");
            }
            File out = new File(dir, "mob-drop-prices.yml");
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
        } catch (Throwable ignored) {
            // 报表只是给人看的，写不出来不影响定价
        }
    }

    private static void putGeo(Map<ItemKey, Double> out, GEOResource geo, double v) {
        if (geo == null) return;
        try {
            ItemKey k = ItemKey.of(geo.getItem());
            if (k != null) out.putIfAbsent(k, v);
        } catch (Throwable ignored) {
        }
    }

    /** 重读 prices.yml 后重建 */
    public void reloadPrices() {
        book.reload();
        rebuildPrices("重读 prices.yml");
    }

    /** 重扫配方库（异步），结束后重建价表 */
    public void rescan(Runnable done) {
        recipes.scanAsync(n -> {
            rebuildPrices("手动重扫");
            if (done != null) done.run();
        });
    }

    // ============================================================

    private void registerItem() {
        try {
            this.group = new ItemGroup(new NamespacedKey(plugin, "exchange"),
                    GuiItems.of(Material.ENDER_EYE, "§b§l等价交换",
                            "§7独立版：自带配方库与定价表"));
            group.register(XinZhiExchange.get());

            ItemStack iron = new ItemStack(Material.IRON_INGOT);
            ItemStack gold = new ItemStack(Material.GOLD_INGOT);
            ItemStack diamond = new ItemStack(Material.DIAMOND);
            ItemStack emerald = new ItemStack(Material.EMERALD);

            // ---------- 初级终端（腐竹 2026-10-26）：开局就能做，只看到 100 EMC ----------
            // 铁锭/钻石/铁锭 · 金锭/绿宝石/金锭 · 铁锭/钻石/铁锭
            ItemStack[] basicRecipe = {
                    iron, diamond, iron,
                    gold, emerald, gold,
                    iron, diamond, iron
            };
            long basicCap = (long) config.terminalBasicCap;
            long basicCeiling = (long) config.terminalBasicCapCeiling;
            SlimefunItemStack basicStack = new SlimefunItemStack(TerminalItem.ID_BASIC, Material.ENDER_PEARL,
                    "§a§l✦ 初级等价交换 ✦",
                    "",
                    "§7右键: §f打开兑换界面（只看便宜货）",
                    "",
                    "§8▸ 只按「材料 → 物品」定价",
                    "§8▸ 自带配方库与服务器 yml 定价表",
                    "",
                    "§8独立附属 · 与 xinzhitech 可共存");
            applyLore(basicStack, TerminalItem.loreFor(basicCap, basicCeiling));
            TradeCap.setCap(basicStack, basicCap);   // 原型就带上限：刚做出来还没右键过的终端也能放进机器
            TerminalItem basic = new TerminalItem(group, basicStack, RecipeType.ENHANCED_CRAFTING_TABLE,
                    basicRecipe, basicCap, basicCeiling);
            basic.register(XinZhiExchange.get());
            Bukkit.getPluginManager().registerEvents(basic, plugin);

            // ---------- 中级终端（原道具，要下界之星）：初始 2000，道具内可无限升 ----------
            // 4 × 下界之星(2000) + 4 × 附魔金苹果(700) + 1 × 钻石块(270) ≈ 11,070 价值
            ItemStack[] recipe = new ItemStack[]{
                    new ItemStack(Material.NETHER_STAR), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), new ItemStack(Material.NETHER_STAR),
                    new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), new ItemStack(Material.DIAMOND_BLOCK), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE),
                    new ItemStack(Material.NETHER_STAR), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), new ItemStack(Material.NETHER_STAR)
            };
            long midCap = (long) config.terminalMidCap;
            SlimefunItemStack stack = new SlimefunItemStack(TerminalItem.ID, Material.ENDER_EYE,
                    "§b§l✦ 等价交换 ✦",
                    "",
                    "§7右键: §f打开兑换界面",
                    "",
                    "§8▸ 只按「材料 → 物品」定价",
                    "§8▸ 不考虑配方环，直接跳过",
                    "§8▸ 自带配方库与服务器 yml 定价表",
                    "",
                    "§8独立附属 · 与 xinzhitech 可共存");
            applyLore(stack, TerminalItem.loreFor(midCap, TradeCap.UNLIMITED));
            TradeCap.setCap(stack, midCap);
            TerminalItem item = new TerminalItem(group, stack, RecipeType.ENHANCED_CRAFTING_TABLE, recipe,
                    midCap, TradeCap.UNLIMITED);
            item.register(XinZhiExchange.get());
            Bukkit.getPluginManager().registerEvents(item, plugin);
            plugin.getLogger().info("[等价交换] 终端道具已注册: " + TerminalItem.ID_BASIC + " / " + TerminalItem.ID);
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 终端道具注册失败（界面仍可用 /xzex 打开）: " + t);
        }
    }

    private static void applyLore(ItemStack stack, java.util.List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.setLore(lore);
        stack.setItemMeta(meta);
    }

    /**
     * 功能道具（腐竹 2026-10-26）：飞行护符 / 范围伤害护符。
     *
     * <p>道具只是「开关」，真正的结算与扣费在 {@link net.me.xinzhi.exchange.ability.AbilityService}：
     * 每秒从余额扣 EMC，余额不足自动关闭。道具不在背包里也会自动关闭。
     */
    private void registerAbility() {
        try {
            abilities = new AbilityService(plugin, config, manager);
            Bukkit.getPluginManager().registerEvents(abilities, plugin);
            abilities.enable();

            ItemStack iron = new ItemStack(Material.IRON_INGOT);
            ItemStack gold = new ItemStack(Material.GOLD_INGOT);
            ItemStack diamond = new ItemStack(Material.DIAMOND);
            ItemStack feather = new ItemStack(Material.FEATHER);
            ItemStack redstone = new ItemStack(Material.REDSTONE);

            ItemStack[] flightRecipe = {
                    gold, feather, gold,
                    feather, new ItemStack(Material.EMERALD), feather,
                    gold, feather, gold
            };
            SlimefunItemStack flightStack = new SlimefunItemStack(FlightTokenItem.ID, Material.FEATHER,
                    "§b§l✦ 飞行护符 ✦",
                    "",
                    "§7右键: §f开启 / 关闭飞行",
                    "§7开启时每秒消耗 §e" + Text.num(config.abilityFlightEmcPerSecond) + " §7EMC",
                    "",
                    "§8道具必须在背包里",
                    "§8余额不足会自动关闭");
            FlightTokenItem flight = new FlightTokenItem(group, flightStack,
                    RecipeType.ENHANCED_CRAFTING_TABLE, flightRecipe, abilities);
            flight.register(XinZhiExchange.get());
            Bukkit.getPluginManager().registerEvents(flight, plugin);

            ItemStack[] auraRecipe = {
                    redstone, diamond, redstone,
                    diamond, new ItemStack(Material.EMERALD), diamond,
                    redstone, diamond, redstone
            };
            SlimefunItemStack auraStack = new SlimefunItemStack(AuraTokenItem.ID, Material.BLAZE_POWDER,
                    "§c§l✦ 范围伤害护符 ✦",
                    "",
                    "§7右键: §f开启 / 关闭范围伤害",
                    "§7潜行 + 右键: §f调整半径",
                    "",
                    "§7初始 §e" + config.abilityAuraBaseRadius + " 格 §7每秒 §e"
                            + Text.num(config.abilityAuraEmcPerSecond) + " §7EMC",
                    "§7半径每 +1 格，每秒消耗 ×" + Text.num(config.abilityAuraRadiusMultiplier),
                    "",
                    "§8道具必须在背包里",
                    "§8余额不足会自动关闭");
            AuraTokenItem aura = new AuraTokenItem(group, auraStack,
                    RecipeType.ENHANCED_CRAFTING_TABLE, auraRecipe, abilities);
            aura.register(XinZhiExchange.get());
            Bukkit.getPluginManager().registerEvents(aura, plugin);

            plugin.getLogger().info("[等价交换] 功能道具已注册: " + FlightTokenItem.ID + " / " + AuraTokenItem.ID);
            plugin.getLogger().info("[等价交换] 功能服务已启动: 飞行 " + Text.num(config.abilityFlightEmcPerSecond)
                    + " EMC/s，范围伤害 " + Text.num(config.abilityAuraEmcPerSecond) + " EMC/s @ "
                    + config.abilityAuraBaseRadius + " 格（每格 ×" + Text.num(config.abilityAuraRadiusMultiplier) + "）");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 功能道具注册失败: " + t);
        }
    }

    /**
     * 自动机器（腐竹 2026-10-19）：卖出机 / 买入机 / EMC 发电机。
     *
     * <p>独立版原来没有任何方块机器设施，2026-10-19 补了最小基类
     * {@link net.me.xinzhi.exchange.machines.AbstractMachine}。
     */
    private void registerMachines() {
        try {
            // 腐竹 2026-10-19：放进「等价交换」主分类，不单独开分类
            ItemGroup machineGroup = this.group;
            if (machineGroup == null) {
                machineGroup = new ItemGroup(new NamespacedKey(plugin, "exchange"),
                        GuiItems.of(Material.ENDER_EYE, "§b§l等价交换",
                                "§7独立版：自带配方库与定价表"));
                machineGroup.register(XinZhiExchange.get());
            }

            ItemStack iron = new ItemStack(Material.IRON_INGOT);
            ItemStack gold = new ItemStack(Material.GOLD_INGOT);
            ItemStack diamond = new ItemStack(Material.DIAMOND);
            ItemStack redstoneBlock = new ItemStack(Material.REDSTONE_BLOCK);

            ItemStack[] sellRecipe = {
                    iron, iron, iron,
                    iron, new ItemStack(Material.HOPPER), iron,
                    iron, iron, iron
            };
            new net.me.xinzhi.exchange.machines.AutoSellMachine(machineGroup,
                    new SlimefunItemStack("XZEX_AUTO_SELL", Material.HOPPER,
                            "§6§l自动卖出机",
                            "",
                            "§7输入槽：要卖的物品",
                            "§7每 5 秒按卖价结算一次",
                            "§7手续费 1%",
                            "",
                            "§8钱进机主余额"),
                    RecipeType.ENHANCED_CRAFTING_TABLE, sellRecipe)
                    .register(XinZhiExchange.get());

            ItemStack[] buyRecipe = {
                    gold, gold, gold,
                    gold, new ItemStack(Material.CHEST), gold,
                    gold, gold, gold
            };
            new net.me.xinzhi.exchange.machines.AutoBuyMachine(machineGroup,
                    new SlimefunItemStack("XZEX_AUTO_BUY", Material.CHEST,
                            "§6§l自动买入机",
                            "",
                            "§7左侧 24 格：模板物品",
                            "§7右侧 24 格：输出",
                            "§7每 2 tick 买一次，数量 = 模板堆叠数",
                            "§7手续费 1%",
                            "",
                            "§8余额低于阈值时待机",
                            "§8只有机主在线才买"),
                    RecipeType.ENHANCED_CRAFTING_TABLE, buyRecipe)
                    .register(XinZhiExchange.get());

            // 等价合成台（腐竹 2026-10-26 m00564）：放在目标机器下方，读正上方那台
            ItemStack[] synRecipe = {
                    diamond, redstoneBlock, diamond,
                    redstoneBlock, new SlimefunItemStack(TerminalItem.ID, Material.ENDER_EYE, "§b§l✦ 等价交换 ✦"), redstoneBlock,
                    diamond, redstoneBlock, diamond
            };
            new net.me.xinzhi.exchange.machines.SynthesisMachine(machineGroup,
                    new SlimefunItemStack(net.me.xinzhi.exchange.machines.SynthesisMachine.ID,
                            Material.CRAFTING_TABLE,
                            "§b§l等价合成台",
                            "",
                            "§7放在目标机器 §f下方 §7，读正上方那台",
                            "§7左键合成一次，Shift+左键合成 64 次",
                            "§7Shift+右键尽可能多的合成",
                            "§7材料优先从背包中扣除，如果不够会自动购买",
                            "§7　EMC = 配方耗时 × 100 + 缺少材料的买价",
                            "",
                            "§8只合成，不管运输"),
                    RecipeType.ENHANCED_CRAFTING_TABLE, synRecipe)
                    .register(XinZhiExchange.get());

            // 等价运输机（腐竹 m00819：合成与运输拆成两台机器）
            ItemStack[] trRecipe = {
                    iron, redstoneBlock, iron,
                    new ItemStack(Material.GLASS), new ItemStack(Material.HOPPER), new ItemStack(Material.GLASS),
                    iron, redstoneBlock, iron
            };
            new net.me.xinzhi.exchange.machines.TransporterMachine(machineGroup,
                    new SlimefunItemStack(net.me.xinzhi.exchange.machines.TransporterMachine.ID,
                            Material.DROPPER,   // 必须是方块：BlockTicker 只挂得上方块材质
                            "§b§l等价运输机",
                            "",
                            "§7放在目标机器 §f下方 §7",
                            "§7主界面 0~44 槽：可被利用的物品",
                            "§7「设置投料映射」里拖物品 = 那个槽投什么",
                            "§7先查输入槽，没有才按买价买",
                            "§7界面第 46 槽放等价道具：买东西受它上限约束",
                            "§7周期 2 tick，只投空槽",
                            "",
                            "§8每成功一槽 100 EMC，机主付"),
                    RecipeType.ENHANCED_CRAFTING_TABLE, trRecipe)
                    .register(XinZhiExchange.get());
            // 运输机的「设置映射」界面是独立的临时箱子，监听器要注册
            Bukkit.getPluginManager().registerEvents(
                    new net.me.xinzhi.exchange.machines.TransporterMachine.SettingListener(), plugin);

            ItemStack[] genRecipe = {
                    diamond, redstoneBlock, diamond,
                    redstoneBlock, new ItemStack(Material.GLOWSTONE), redstoneBlock,
                    diamond, redstoneBlock, diamond
            };
            new net.me.xinzhi.exchange.machines.EmcGenerator(machineGroup,
                    new SlimefunItemStack("XZEX_EMC_GENERATOR", Material.REDSTONE_LAMP,
                            "§6§lEMC 发电机",
                            "",
                            "§7消耗 EMC 发电",
                            "§71 EMC = §f500 J",
                            "§7输入槽放物品 或 直接充 EMC",
                            "",
                            "§8不接调节器：电量可超上限",
                            "§8接调节器：超出部分被截断"),
                    RecipeType.ENHANCED_CRAFTING_TABLE, genRecipe)
                    .register(XinZhiExchange.get());

            plugin.getLogger().info("[等价交换] 自动机器已注册: XZEX_AUTO_SELL / XZEX_AUTO_BUY / XZEX_SYNTHESIZER / XZEX_TRANSPORTER / XZEX_EMC_GENERATOR");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 自动机器注册失败: " + t);
        }
    }

    /**
     * 两个管理道具（腐竹 2026-10-18 第五轮：「把 emc 定制那两个道具也加进这个附属。注意改 id」）。
     *
     * <p>id 用 {@code XZEX_} 前缀，和主附属的 {@code XINZHI_EMC_CUSTOMIZER} /
     * {@code XINZHI_EMC_BATCH} 不冲突，可以同服共存。无配方、只有 xzex.admin 能开。
     */
    private void registerPricingItems() {
        try {
            SlimefunItemStack custom = new SlimefunItemStack("XZEX_PRICE_CUSTOM", Material.NAME_TAG,
                    "§6§l✦ 价格定制 ✦", "",
                    "§7右键: §f打开手工价面板", "",
                    "§8直接改 prices.yml 的 manual 段",
                    "§8删掉手工价 = 回落到基础价", "",
                    "§c仅管理员可用");
            PricingAdminItem customItem = new PricingAdminItem(group, custom,
                    RecipeType.NULL, new ItemStack[9], PricingAdminItem.Kind.CUSTOM, pricingGui);
            customItem.register(XinZhiExchange.get());
            Bukkit.getPluginManager().registerEvents(customItem, plugin);

            SlimefunItemStack batch = new SlimefunItemStack("XZEX_PRICE_BATCH", Material.WRITABLE_BOOK,
                    "§6§l✦ 批量定价 ✦", "",
                    "§7右键: §f打开批量定价面板", "",
                    "§7Shift+左键背包物品: §f快速加入清单",
                    "§7统一定价 / 单独定价 / 一次性写盘", "",
                    "§c仅管理员可用");
            PricingAdminItem batchItem = new PricingAdminItem(group, batch,
                    RecipeType.NULL, new ItemStack[9], PricingAdminItem.Kind.BATCH, pricingGui);
            batchItem.register(XinZhiExchange.get());
            Bukkit.getPluginManager().registerEvents(batchItem, plugin);

            Bukkit.getPluginManager().registerEvents(new PricingAdminListener(pricingGui), plugin);
            plugin.getLogger().info("[等价交换] 管理道具已注册: XZEX_PRICE_CUSTOM / XZEX_PRICE_BATCH");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 管理道具注册失败: " + t);
        }
    }

    private void registerCommand() {
        ExchangeCommand cmd = new ExchangeCommand(plugin, this);
        var c = plugin.getCommand("xzex");
        if (c != null) {
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        } else {
            plugin.getLogger().warning("[等价交换] plugin.yml 里没有 xzex 命令");
        }
    }
}
