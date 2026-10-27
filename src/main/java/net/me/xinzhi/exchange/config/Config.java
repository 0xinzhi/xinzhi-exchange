package net.me.xinzhi.exchange.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 配置读取。
 *
 * <p>⚠ 每个值都<b>在代码里内建兜底</b>：老服的 config.yml 里没有新键时
 * （{@code saveDefaultConfig()} 只在文件不存在时写），也要拿到合理默认值。
 */
public final class Config {

    /** 手续费：卖出扣、买入加（0 = 平价） */
    public final double tax;
    /** 单笔兑换上限 */
    public final int maxPerTransaction;
    /** 是否把算不出价的物品也列进界面 */
    public final boolean allowUnpriced;
    /**
     * 卖入时是否拒收「被改过」的物品（名称 / Lore / 附魔 / 耐久 / PDC 等与模板不一致）。
     * 腐竹 2026-10-18：「不允许卖改过lore或名称等的物品」。
     */
    public final boolean rejectModified;
    /** 两阶段定价：先本体/原版，再其它附属（腐竹 2026-10-18 要求） */
    public final boolean staged;
    /**
     * 机器依赖：一条配方必须等它的机器先算出价，才能用来定价
     * （否则「用自己造的高级机器把自己造得更便宜」这种隐性环会把普通材料压到地板价）。
     */
    public final boolean requireMachine;

    /**
     * 配方机器黑名单（腐竹 2026-10-18 点名 LOGITECH_CARD_MAKER）：
     * 这些机器扫出来的配方一律不入库，免得它们的产出把整条材料链的价格带偏。
     * 写 SF id（不带 SF: 前缀），大小写不敏感。
     */
    public final java.util.Set<String> machineBlacklist;

    public final boolean scanOnStart;
    /**
     * 启动时是否先读上次的配方文件当临时价表（腐竹 2026-10-18：「每次服务器重启都需要
     * 重新扫描所有配方」）。默认 <b>false</b>：每次重启都全量重扫，不沿用旧结果，
     * 免得旧配方在这几秒里给出错价。
     */
    public final boolean reuseDisk;
    public final int scanDelayTicks;
    /**
     * 附属读取顺序（腐竹 2026-10-26）：「先读逻辑，再读无尽，乱序，魔法，陌陌科技等，最后读rsc」——
     * RSC 的附属太杂乱，里面有大量「把配方改简单」的复制品，读早了会让昂贵物品变白菜价。
     * 小写匹配：依次比对 {@code SlimefunItem.getAddon().getName()} 与插件名，包含即命中；
     * 未列出的附属排在最后（保持原注册顺序）。
     */
    public final java.util.List<String> addonOrder;
    /**
     * 永远最后读的附属（腐竹：「rsc一定要最后就行，rsc的附属太杂乱了」）——
     * 排在 {@link #addonOrder} 与「未列出」之后。
     */
    public final java.util.List<String> addonOrderLast;
    /**
     * 启动补扫：首次扫描完成后，再等这么多 tick 重扫一遍，只有内容真的变了才重建价表。
     * 为什么需要：有些附属（实测 FinalTECH）启动后还在往配方表里填东西，20 秒扫出来的
     * 是半成品 —— 同一份服 +20 秒扫出 3,698 个有价、+38 秒重扫变 3,899。默认 1200 = 60 秒。
     * 0 / 负数 = 关。
     */
    public final int settleTicks;
    public final boolean extractMultiblock;
    public final boolean extractOwnRecipes;
    public final boolean dropSelfLoop;

    /**
     * 地理资源（Slimefun {@code GEOResource}）的默认价：它们没有合成配方，
     * 原来一律算不出价。腐竹 2026-10-18 第七轮：「除非有手动定价否则地理资源都按30算」。
     * 0 / 负数 = 关（恢复"没配方就没价"）。
     */
    public final double geoResourcePrice;

    // ---- 生物掉落物兜底价（腐竹 2026-10-26）----
    /** 是否按「生物掉落物概率」给算不出价的附属掉落物补价 */
    public final boolean mobDropPricing;
    /**
     * 读不到掉落概率时的兜底价（腐竹 m01261：「兜底按 60 计算」「兜底 60」）。
     * 概率能读到的按档位：≥50% → 5，≥20% → 20，≥10% → 100，≥5% → 200，&lt;5% → 500。
     * 0 / 负数 = 读不到概率的物品不给价。
     */
    public final double mobDropUnknownPrice;

    // ---- 破坏方块概率掉落兜底价（腐竹 2026-10-28）----
    /**
     * 是否给「挖方块概率掉落」的物品补价。
     *
     * <p>来源是 RSC 子附属的 {@code items.yml}（{@code drop_from} / {@code drop_chance} /
     * {@code drop_amount}），见 {@link net.me.xinzhi.exchange.price.BlockDropPrices}。
     */
    public final boolean blockDropPricing;
    /**
     * 能反复刷取的方块（挖了会掉回自身，玩家摆了再挖成本≈0）按概率分档：
     * 概率 ≥ {@link #blockDropHighChance}% → {@link #blockDropHighEmc}；
     * ≥ {@link #blockDropMidChance}% → {@link #blockDropMidEmc}；更低 → {@link #blockDropLowEmc}。
     */
    public final int blockDropHighChance;
    public final double blockDropHighEmc;
    public final int blockDropMidChance;
    public final double blockDropMidEmc;
    public final double blockDropLowEmc;
    /**
     * 不可再生方块（挖掉不会掉回自身、玩家也造不出来，如紫水晶母岩）的掉落价。
     */
    public final double blockDropNonRenewableEmc;
    /**
     * 额外算作「玩家能自己造出来 / 能反复刷」的方块（没有物品形态但原版能建，
     * 例如下界传送门 —— 腐竹：「还有就是那个下界传送门算是可再生」）。写材质名。
     */
    public final java.util.List<String> blockDropRenewableBlocks;

    // ---- 卖价规则（腐竹 2026-10-18 第八轮：「只降卖价不降买价」） ----
    /** 压缩物品卖价封顶（>0 生效；0 = 关）。腐竹：卖价超过 100 就固定 100。 */
    public final double compressedSellCap;
    /** 什么算「压缩物品」：id 或显示名命中这些关键词（压缩 / 魔法糖 / COMPRESSED / MAGIC_SUGAR）。 */
    public final java.util.List<String> compressedKeywords;
    /** 生成器产物的卖价 = 机器价 × 这个比例（默认 0.1%），不看产出数量；0 = 关。 */
    public final double generatorSellRate;
    /** 什么算「生成器」：机器 id 命中这些关键词（默认 GENERATOR）。 */
    public final java.util.List<String> generatorKeywords;

    /**
     * 兑换上限：单价（EMC 值）超过它的物品整件禁止交易（买卖都不行）。
     * 腐竹 2026-10-19：「增加兑换价格上限的功能，比如腐竹设置上限为10w，那么所有 emc 值
     * 高于 10w 的物品都不允许交易。」0 / 负数 = 不限。
     */
    public final double tradeMax;

    // ---- 自动机器（腐竹 2026-10-19）----
    /** 自动买入机：余额高于它才买（每台机器可在界面里改，这里是新机器的默认值） */
    public final double machineBuyMinBalance;
    /** EMC 发电机：1 EMC 换多少 J（默认 500） */
    public final long machineGenJoulesPerEmc;
    /** EMC 发电机：容量（接能源调节器时超出部分按它截断；默认 Long.MAX_VALUE = 不截断） */
    public final long machineGenCapacity;
    /** 自动卖出机：结算周期（粘液 tick，1 tick = 0.5 秒；默认 2 = 1 秒） */
    public final int machineSellInterval;

    // ---- 功能道具（腐竹 2026-10-26）----
    /** 飞行道具：每秒消耗 EMC */
    public final double abilityFlightEmcPerSecond;
    /** 范围伤害道具：3 格（基础半径）时每秒消耗的 EMC */
    public final double abilityAuraEmcPerSecond;
    /** 范围伤害道具：初始半径（格） */
    public final int abilityAuraBaseRadius;
    /** 范围伤害道具：半径每 +1 格，消耗量乘这个倍数 */
    public final double abilityAuraRadiusMultiplier;
    /** 范围伤害道具：每次攻击对范围内的伤害 */
    public final double abilityAuraDamage;
    /** 范围伤害道具：两次挥击之间的最短间隔（毫秒），防止一次点击触发两次 */
    public final int abilityAuraCooldownMs;
    /** 范围伤害道具：是否连玩家一起打（默认只打非玩家生物） */
    public final boolean abilityAuraHitPlayers;
    /** 范围伤害道具：不打的实体类型（逗号或列表，如 ARMOR_STAND） */
    public final java.util.List<String> abilityAuraIgnoredEntities;
    /** 等价终端「刷新价表」的全服冷却（秒；腐竹 2026-10-28：2 小时）。0 = 不限制 */
    public final int terminalRefreshCooldownSeconds;
    /** 等价终端：每提升 1 点显示上限花费的 EMC */
    public final double terminalUpgradeEmcPerCap;
    /** 初级等价终端：初始显示上限 / 升级封顶 */
    public final double terminalBasicCap;
    public final double terminalBasicCapCeiling;
    /** 中级等价终端（下界之星）：初始显示上限 */
    public final double terminalMidCap;

    /** 等价合成台：EMC 合成每 1 tick 收多少 EMC（费用 = 配方耗时 × 这个数） */
    public final double synthesisCraftEmcPerTick;
    /** 等价合成台：物流投料每成功一槽收多少 EMC */
    public final double synthesisLogisticsEmcPerSlot;
    /** 等价合成台：物流自动处理的周期（tick） */
    public final int synthesisLogisticsInterval;
    /** 自动买入机：一次处理全部模板槽的周期（tick） */
    public final int machineBuyInterval;

    public final String defaultSort;
    public final int pageSize;
    /** 数量弹窗里滑条的快速选择上限（腐竹 2026-10-18 第七轮：128）。 */
    public final int quickAmountMax;

    public Config(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();
        this.tax = clamp(c.getDouble("price.tax", 0.0), 0.0, 1.0);
        // 0 / 负数 = 不限（腐竹 2026-10-18：「这个输入不需要有限制」）
        int perTx = c.getInt("price.max-per-transaction", 1_000_000);
        this.maxPerTransaction = perTx <= 0 ? Integer.MAX_VALUE : perTx;
        this.allowUnpriced = c.getBoolean("price.allow-unpriced", false);
        this.rejectModified = c.getBoolean("price.reject-modified-items", true);
        this.staged = c.getBoolean("price.staged", true);
        this.requireMachine = c.getBoolean("price.require-machine", true);

        java.util.Set<String> black = new java.util.LinkedHashSet<>();
        for (String s : c.getStringList("scan.machine-blacklist")) {
            if (s != null && !s.isBlank()) black.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (!c.isSet("scan.machine-blacklist")) black.add("LOGITECH_CARD_MAKER");
        this.machineBlacklist = java.util.Collections.unmodifiableSet(black);

        this.scanOnStart = c.getBoolean("scan.on-start", true);
        this.reuseDisk = c.getBoolean("scan.reuse-disk", false);
        this.settleTicks = Math.max(0, c.getInt("scan.settle-ticks", 1200));
        // 默认 400 tick（20 秒）：+5 秒扫会把 FinalTECH 那类「还在慢慢填配方表」的附属扫成半成品
        // （实测同一台服：+5 秒扫出 3,698 个有价，+38 秒重扫同样配方库变 3,899 —— 差 201 个）
        this.scanDelayTicks = Math.max(20, c.getInt("scan.delay-ticks", 400));
        java.util.List<String> ao = c.getStringList("scan.addon-order");
        if (ao.isEmpty()) ao = java.util.List.of("LogiTech", "InfinityExpansion", "FinalTECH",
                "MagicExpansion", "MomoTech");
        java.util.List<String> al = c.getStringList("scan.addon-order-last");
        if (al.isEmpty()) al = java.util.List.of("RykenSlimefunCustomizer");
        this.addonOrder = lowerList(ao);
        this.addonOrderLast = lowerList(al);
        this.extractMultiblock = c.getBoolean("scan.extract-multiblock", true);
        this.extractOwnRecipes = c.getBoolean("scan.extract-own-recipes", true);
        this.dropSelfLoop = c.getBoolean("scan.drop-self-loop", true);

        this.geoResourcePrice = Math.max(0.0, c.getDouble("price.geo-resource-default", 30.0));

        this.mobDropPricing = c.getBoolean("price.mob-drop.enabled", true);
        this.mobDropUnknownPrice = Math.max(0.0, c.getDouble("price.mob-drop.unknown-emc", 60.0));

        // 方块掉落兜底（腐竹 2026-10-28）：
        // 「可重复的按概率划分，30%以上5emc，30以下15emc，5以下30emc。无法再生的资源按100emc。
        //   还有就是那个下界传送门算是可再生。」
        this.blockDropPricing = c.getBoolean("price.block-drop.enabled", true);
        this.blockDropHighChance = Math.max(1, c.getInt("price.block-drop.high-chance", 30));
        this.blockDropHighEmc = Math.max(0.0, c.getDouble("price.block-drop.high-emc", 5.0));
        this.blockDropMidChance = Math.max(0, c.getInt("price.block-drop.mid-chance", 5));
        this.blockDropMidEmc = Math.max(0.0, c.getDouble("price.block-drop.mid-emc", 15.0));
        this.blockDropLowEmc = Math.max(0.0, c.getDouble("price.block-drop.low-emc", 30.0));
        this.blockDropNonRenewableEmc = Math.max(0.0, c.getDouble("price.block-drop.non-renewable-emc", 100.0));
        java.util.List<String> rb = c.getStringList("price.block-drop.renewable-blocks");
        if (rb.isEmpty()) rb = java.util.List.of("NETHER_PORTAL");
        java.util.List<String> rbUp = new java.util.ArrayList<>(rb.size());
        for (String s : rb) {
            if (s != null && !s.isBlank()) rbUp.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
        this.blockDropRenewableBlocks = java.util.List.copyOf(rbUp);

        this.compressedSellCap = Math.max(0.0, c.getDouble("price.compressed-sell-cap", 100.0));
        java.util.List<String> ck = c.getStringList("price.compressed-keywords");
        if (ck.isEmpty()) ck = java.util.List.of("COMPRESSED", "MAGIC_SUGAR", "压缩", "魔法糖");
        this.compressedKeywords = java.util.List.copyOf(ck);
        this.generatorSellRate = Math.max(0.0, c.getDouble("price.generator-sell-rate", 0.001));
        java.util.List<String> gk = c.getStringList("price.generator-keywords");
        if (gk.isEmpty()) gk = java.util.List.of("GENERATOR");
        this.generatorKeywords = java.util.List.copyOf(gk);

        this.tradeMax = Math.max(0.0, c.getDouble("price.trade-max", 0.0));

        this.machineBuyMinBalance = Math.max(0.0, c.getDouble("machine.buy.min-balance", 0.0));
        this.machineGenJoulesPerEmc = Math.max(1L, c.getLong("machine.gen.joules-per-emc", 500L));
        this.machineGenCapacity = Math.max(1L, c.getLong("machine.gen.capacity", 2_000_000_000L));
        this.machineSellInterval = Math.max(1, c.getInt("machine.sell.interval-ticks", 2));
        this.machineBuyInterval = Math.max(1, c.getInt("machine.buy.interval-ticks", 2));

        this.abilityFlightEmcPerSecond = Math.max(0.0, c.getDouble("ability.flight.emc-per-second", 500.0));
        this.abilityAuraEmcPerSecond = Math.max(0.0, c.getDouble("ability.aura.emc-per-second", 1000.0));
        this.abilityAuraBaseRadius = Math.max(1, c.getInt("ability.aura.base-radius", 3));
        this.abilityAuraRadiusMultiplier = Math.max(1.0, c.getDouble("ability.aura.radius-multiplier", 3.0));
        this.abilityAuraDamage = Math.max(0.0, c.getDouble("ability.aura.damage", 2.0));
        this.abilityAuraCooldownMs = Math.max(0, c.getInt("ability.aura.cooldown-ms", 250));
        this.abilityAuraHitPlayers = c.getBoolean("ability.aura.hit-players", false);
        java.util.List<String> ign = c.getStringList("ability.aura.ignored-entities");
        if (ign.isEmpty()) ign = java.util.List.of("ARMOR_STAND");
        java.util.List<String> ignUp = new java.util.ArrayList<>();
        for (String s : ign) if (s != null && !s.isBlank()) ignUp.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        this.abilityAuraIgnoredEntities = java.util.List.copyOf(ignUp);

        this.terminalRefreshCooldownSeconds = Math.max(0, c.getInt("terminal.refresh-cooldown-seconds", 7200));

        this.terminalUpgradeEmcPerCap = Math.max(1.0, c.getDouble("terminal.upgrade.emc-per-cap", 1000.0));
        this.terminalBasicCap = Math.max(0.0, c.getDouble("terminal.basic.cap", 100.0));
        this.terminalBasicCapCeiling = Math.max(this.terminalBasicCap,
                c.getDouble("terminal.basic.cap-ceiling", 2000.0));
        this.terminalMidCap = Math.max(0.0, c.getDouble("terminal.mid.cap", 2000.0));

        this.synthesisCraftEmcPerTick = Math.max(0.01, c.getDouble("synthesis.craft.emc-per-tick", 100.0));
        this.synthesisLogisticsEmcPerSlot = Math.max(0.0, c.getDouble("synthesis.logistics.emc-per-slot", 100.0));
        this.synthesisLogisticsInterval = Math.max(1, c.getInt("synthesis.logistics.interval-ticks", 2));

        this.defaultSort = c.getString("gui.default-sort", "price-desc");
        this.pageSize = Math.max(9, Math.min(45, c.getInt("gui.page-size", 45)));
        this.quickAmountMax = Math.max(2, c.getInt("gui.quick-amount-max", 128));
    }

    private static java.util.List<String> lowerList(java.util.List<String> raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : raw) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(java.util.Locale.ROOT));
        return java.util.List.copyOf(out);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
