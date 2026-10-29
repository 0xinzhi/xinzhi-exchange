package net.me.xinzhi.exchange.exchange;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import net.me.xinzhi.exchange.core.ItemKey;
import net.me.xinzhi.exchange.price.PriceEngine;
import net.me.xinzhi.exchange.recipe.Recipe;
import net.me.xinzhi.exchange.recipe.VanillaRecipes;
import net.me.xinzhi.exchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /xzex} —— 信息、诊断、重扫、手工价、余额。 */
public final class ExchangeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "help", "info", "price", "why", "roots", "top", "scan", "rebuild", "reload",
            "export", "set", "unset", "balance", "give", "admin", "menu", "emc", "limit", "craft",
            "norecipe", "recalc");

    private final JavaPlugin plugin;
    private final ExchangeService svc;

    public ExchangeCommand(JavaPlugin plugin, ExchangeService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (args.length == 0) {
            // 腐竹 2026-10-26：无参 /xzex 直接把等价界面开出来 = 管理员权限
            // （普通玩家只能靠等价终端道具开界面）
            if (s instanceof Player p) {
                if (!s.hasPermission("xzex.open")) {
                    s.sendMessage("§c[等价交换] 只有管理员能直接用 /xzex 打开界面；请用等价终端道具");
                    return true;
                }
                svc.gui().open(p, 0, sortDefault(), null);
            } else {
                help(s);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(s);
            case "info" -> info(s);
            case "price" -> price(s, args);
            case "why" -> why(s, args);
            case "roots" -> {
                if (!viewAll(s)) return true;
                roots(s, args);
            }
            case "top" -> {
                if (!viewAll(s)) return true;
                top(s, args);
            }
            case "scan" -> {
                if (!admin(s)) return true;
                if (svc.recipes().isScanning()) {
                    s.sendMessage("§c[等价交换] 正在扫描中，请稍候");
                    return true;
                }
                VanillaRecipes.rescan();
                s.sendMessage("§7[等价交换] 开始全量扫描配方库…（完成后会打印一行结果）");
                svc.rescan(() -> s.sendMessage("§a[等价交换] 扫描完成："
                        + svc.registry().size() + " 条配方 / "
                        + svc.registry().machines().size() + " 台机器"));
            }
            case "recalc" -> {
                if (!viewAll(s)) return true;
                recalc(s, args);
            }
            case "norecipe" -> {
                if (!viewAll(s)) return true;
                noRecipe(s, args);
            }
            case "rebuild" -> {
                if (!admin(s)) return true;
                svc.rebuildPrices("命令 " + s.getName());
                s.sendMessage("§a[等价交换] 价表已重建：" + svc.engine().pricedCount() + " 个物品有价");
            }
            case "reload" -> {
                if (!admin(s)) return true;
                svc.reloadPrices();
                s.sendMessage("§a[等价交换] prices.yml 已重读，价表已重建");
            }
            case "export" -> {
                if (!admin(s)) return true;
                export(s);
            }
            case "set" -> {
                if (!admin(s)) return true;
                set(s, args);
            }
            case "unset" -> {
                if (!admin(s)) return true;
                unset(s, args);
            }
            case "balance" -> balance(s, args);
            case "give" -> give(s, args);
            case "admin" -> adminItems(s);
            case "menu" -> menu(s, args);
            case "craft" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("machines")) {
                    if (!admin(s)) return true;
                    craftMachines(s);
                    return true;
                }
                if (args.length >= 3 && args[1].equalsIgnoreCase("quote")) {
                    if (!admin(s)) return true;
                    craftQuote(s, args[2]);
                    return true;
                }
                if (!(s instanceof Player p)) {
                    s.sendMessage("§c[等价交换] 只有玩家能打开合成台");
                    return true;
                }
                if (!p.hasPermission("xzex.use")) {
                    p.sendMessage("§c[等价交换] 你没有权限使用合成台");
                    return true;
                }
                svc.craftGui().open(p);
            }
            case "limit" -> {
                if (!admin(s)) return true;
                limit(s, args);
            }
            case "emc" -> {
                if (!admin(s)) return true;
                emc(s);
            }
            default -> help(s);
        }
        return true;
    }

    // ============================================================

    /** 诊断：用「全部有价机器」给一个物品报价，不扣料不扣钱。 */
    private void craftQuote(CommandSender s, String raw) {
        if (!svc.isReady()) {
            s.sendMessage("§c[等价交换] 服务未就绪（配方还没扫完）");
            return;
        }
        ItemKey key = parseItem(raw);
        if (key == null) {
            s.sendMessage("§c[等价交换] 认不出物品: " + raw);
            return;
        }
        java.util.Set<ItemKey> machines = new java.util.HashSet<>();
        for (ItemKey m : svc.registry().machines()) {
            if (svc.engine().priceOf(m) > 0) machines.add(m);
        }
        CraftPlanner.Plan plan = svc.planner().plan(key, machines);
        s.sendMessage("§6[合成台] " + Text.plain(key.getDisplayName())
                + " §7标价 §f" + Text.num(svc.engine().priceOf(key))
                + " §7机器 §f" + machines.size());
        if (!plan.feasible) {
            s.sendMessage("§c不可造: §7" + plan.reason);
            return;
        }
        s.sendMessage("§a可造 §7总价 §f" + Text.num(plan.cost)
                + " §7产出 §f" + plan.outputAmount
                + " §7深度 §f" + plan.depth
                + " §7叶子 §f" + plan.leaves);
        if (!plan.machines.isEmpty()) {
            StringBuilder ms = new StringBuilder("§7需要机器 §f" + plan.machines.size() + " §7台：");
            int i = 0;
            for (ItemKey m : plan.machines) {
                if (i++ >= 8) { ms.append(" §8…"); break; }
                ms.append(" §f").append(Text.plain(m.getDisplayName()));
            }
            s.sendMessage(ms.toString());
        }
        int n = 0;
        for (Map.Entry<ItemKey, Double> e : plan.leafNeeds.entrySet()) {
            if (n++ >= 12) {
                s.sendMessage("§8  …");
                break;
            }
            s.sendMessage("§8  - §f" + Text.plain(e.getKey().getDisplayName())
                    + " §7×" + (long) Math.ceil(e.getValue() - 1e-9)
                    + " §8@ §7" + Text.num(svc.engine().priceOf(e.getKey())));
        }
        for (Map.Entry<ItemKey, Integer> e : plan.byproducts.entrySet()) {
            s.sendMessage("§8  副产物 §f" + Text.plain(e.getKey().getDisplayName())
                    + " §7×" + e.getValue());
        }
    }

    /**
     * 诊断：机器配方覆盖报告（腐竹 2026-10-19「好多机器放进去说没配方」）。
     *
     * <p>把「看着像机器」的物品全列一遍：id / 扫到的配方数 / 价格 / 是否在配方库里 /
     * 类名。写入 {@code plugins/XinZhiExchange/diag/craft-machines.txt}，聊天栏只给汇总。
     */
    private void craftMachines(CommandSender s) {
        List<SlimefunItem> items = net.me.xinzhi.exchange.recipe.RecipeService.collectAllSlimefunItems();
        java.util.Set<ItemKey> known = new java.util.HashSet<>(svc.registry().machines());
        StringBuilder sb = new StringBuilder();
        sb.append("# 机器配方覆盖报告（生成于 ").append(java.time.LocalDateTime.now()).append("）\n");
        sb.append("# 遍历 Slimefun 物品 ").append(items.size())
                .append("；配方库机器 ").append(known.size()).append(" 台\n");
        sb.append("# 列：物品 id  配方数  价格  在库  类名\n");

        int candidates = 0, zero = 0;
        java.util.List<String> zeroList = new ArrayList<>();
        for (SlimefunItem it : items) {
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            String sfId = k.getSfId();
            String idUp = sfId == null ? "" : sfId.toUpperCase(Locale.ROOT);
            String cls = it.getClass().getSimpleName().toUpperCase(Locale.ROOT);
            boolean inLib = known.contains(k);
            boolean multi = it instanceof io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
            boolean getter = hasRecipeGetter(it);
            boolean nameish = idUp.contains("MACHINE") || idUp.contains("CRAFTER")
                    || idUp.contains("MAKER") || idUp.contains("FORGE")
                    || idUp.contains("FACTORY") || idUp.contains("TABLE")
                    || cls.contains("MACHINE") || cls.contains("CRAFTER");
            if (!(inLib || multi || getter || nameish)) continue;

            int n = svc.registry().ofMachine(k).size();
            double price = svc.engine().priceOf(k);
            candidates++;
            if (n == 0) {
                zero++;
                if (zeroList.size() < 12) zeroList.add(k.toString() + " §8(" + it.getClass().getSimpleName() + ")");
            }
            sb.append(k.toString()).append('\t').append(n)
                    .append('\t').append(Text.num(price))
                    .append('\t').append(inLib ? "库内" : "库外")
                    .append('\t').append(it.getClass().getSimpleName()).append('\n');
        }

        int written = 0;
        try {
            File dir = new File(plugin.getDataFolder(), "diag");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "craft-machines.txt");
            Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            written = 1;
            s.sendMessage("§a[合成台] 报告已写入 §f" + out.getPath());
        } catch (Throwable t) {
            s.sendMessage("§c[合成台] 写报告失败: " + t);
        }
        s.sendMessage("§7[合成台] 物品 §f" + items.size()
                + " §7/ 配方库机器 §f" + known.size()
                + " §7/ 候选机器 §f" + candidates
                + " §c/ 其中 0 配方 §f" + zero);
        if (!zeroList.isEmpty()) {
            s.sendMessage("§7[合成台] 0 配方的机器（前 " + zeroList.size() + " 个）：");
            for (String line : zeroList) s.sendMessage("  §8- §f" + line);
        }
        if (written == 0) s.sendMessage("§7[合成台] （文件没写成，汇总见上面）");
    }

    private static boolean hasRecipeGetter(SlimefunItem it) {
        for (String name : new String[]{"getRecipes", "getMachineRecipes", "getRecipeList"}) {
            try {
                it.getClass().getMethod(name);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 数字解析：支持 10w / 100k / 1万 / 1_000 / 1,000 */
    private static double parseAmount(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        double mul = 1;
        if (v.endsWith("w") || v.endsWith("万")) {
            mul = 10_000;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("k")) {
            mul = 1_000;
            v = v.substring(0, v.length() - 1);
        }
        return Double.parseDouble(v) * mul;
    }

    private static ItemKey parseItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.startsWith("SF:") || t.startsWith("VANILLA:")) return ItemKey.fromStored(t);
        ItemKey sf = ItemKey.slimefun(t);
        if (sf != null && sf.toTemplate() != null) return sf;
        Material m = Material.matchMaterial(t);
        return m == null ? null : ItemKey.vanilla(m);
    }

    private void help(CommandSender s) {
        s.sendMessage("§6§l✦ 等价交换（独立版）✦");
        s.sendMessage("§7/xzex §f打开兑换界面");
        s.sendMessage("§7/xzex info §f价表 / 配方库规模");
        s.sendMessage("§7/xzex price <物品|光标> §f这个物品怎么算出来的");
        s.sendMessage("§7/xzex why <物品|光标> §f它为什么没价 / 卡在哪");
        s.sendMessage("§7/xzex roots [n] §f断链的根 §8（仅管理）");
        s.sendMessage("§7/xzex top [n] §f最贵的 n 个物品 §8（仅管理）");
        s.sendMessage("§7/xzex norecipe [n|add] §f原版·无配方的物品（只能手工定价）§8（仅管理）");
        s.sendMessage("§7/xzex recalc [n] §f有配方但吃锚点价的物品，用配方试算一遍做对照（只读）§8（仅管理）");
        s.sendMessage("§7/xzex balance [玩家] §f查余额");
        s.sendMessage("§7/xzex menu [on|off] §f开关超级菜单（客户端装 mod 才生效）");
        s.sendMessage("§c—— 管理 ——");
        s.sendMessage("§7/xzex scan §f重扫配方库");
        s.sendMessage("§7/xzex rebuild §f重建价表");
        s.sendMessage("§7/xzex reload §f重读 prices.yml");
        s.sendMessage("§7/xzex export §f导出全量价表到 diag/");
        s.sendMessage("§7/xzex set <物品> <价格> §f写手工价（进 prices.yml 的 manual）");
        s.sendMessage("§7/xzex unset <物品> §f删手工价");
        s.sendMessage("§7/xzex give <玩家> <数量> §f给价值");
        s.sendMessage("§7/xzex admin §f给自己发两个管理道具（价格定制 / 批量定价）");
        s.sendMessage("§7/xzex emc §f看第三方附属注册了哪些 EMC（§8仅管理§7）");
        s.sendMessage("§7/xzex limit <金额|off> §f兑换价格上限，超过就不准交易（§8仅管理，写回 config.yml§7）");
    }

    private void info(CommandSender s) {
        PriceEngine e = svc.engine();
        s.sendMessage("§6===== 等价交换（独立版）=====");
        s.sendMessage("§7版本: §f" + svc.pluginVersion());
        s.sendMessage("§7价表: §e" + e.pricedCount() + " §7个物品有价（锚点 §e"
                + e.anchorCount() + " §7/ 地理默认 §e" + e.geoCount()
                  + " §7/ 掉落兜底 §e" + e.dropCount()
                + " §7/ 配方推导 §e" + e.recipeCount()
                + " §7/ 算不出 §c" + e.unpricedCount() + "§7）");
        if (e.orderForcedCount() > 0) {
            s.sendMessage("§7严格顺序: §e" + e.orderForcedCount()
                    + " §7个物品按「读取顺序更靠前的附属配方」重新定价（高于后期附属的廉价配方）");
        }
        s.sendMessage("§7配方库: §e" + svc.registry().size() + " §7条 / §e"
                + svc.registry().machines().size() + " §7台机器"
                + (svc.recipes().isScanning() ? " §c（正在扫描）" : ""));
        s.sendMessage("§7口径: §f买卖不同价（卖价只降不升） §7· 手续费 §e" + svc.config().tax
                + " §7· 不考虑环（结算后不下调） §7· 卖价下调 §e" + e.sellAdjustedCount() + " §7个");
        s.sendMessage("§7定价阶段: §f" + (svc.config().staged
                ? "两阶段 §7（本体/原版先定 §e" + e.stage1Count()
                  + " §7→ 附属补 §e" + e.stage2Count() + "§7）"
                : "单阶段（所有配方一起算）"));
        s.sendMessage("§7机器依赖: §f" + (svc.config().requireMachine
                ? "开 §7（机器本身没算出价的配方不参与定价，跳过 §e"
                  + e.machineBlockedRecipes() + " §7条）"
                : "关 §7（任何机器的配方都能拿来定价）"));
        s.sendMessage("§7定价表: §f" + svc.book().file().getAbsolutePath()
                + " §7（§e" + svc.book().count() + " §7条）");
        s.sendMessage("§7余额存储: §f" + svc.manager().balances().file().getAbsolutePath());
        s.sendMessage("§7附属内嵌 EMC: §e" + net.me.xinzhi.exchange.api.EmcRegistry.size()
                + " §7条（§8/xzex emc 查看§7）");
        s.sendMessage("§7兑换上限: §f" + (svc.manager().tradeMax() > 0
                ? Text.num(svc.manager().tradeMax()) + " §7（单价超过就不准交易，当前 §c"
                  + svc.manager().overLimitCount() + " §7个被拦；/xzex limit 改）"
                : "不限 §8（/xzex limit <金额> 设置）"));
        s.sendMessage("§7上次建表耗时: §e" + e.lastBuildMillis() + " ms §7（结算 "
                + e.lastSteps() + " 步）");
    }

    private void price(CommandSender s, String[] args) {
        ItemKey key = args.length >= 2 ? resolve(args[1]) : (s instanceof Player p ? keyInHandOrCursor(p) : null);
        if (key == null) {
            s.sendMessage("§c[等价交换] 找不到这个物品：给个 id / 名称，或者把物品拿在手上");
            return;
        }
        PriceEngine e = svc.engine();
        double v = e.priceOf(key);
        s.sendMessage("§6===== " + key.getDisplayName() + " §8(" + key + ")");
        s.sendMessage("§7单价: §e" + Text.num(v)
                + " §7（换出 §e" + Text.num(svc.manager().buyUnit(key))
                + " §7/ 换入 §a" + Text.num(svc.manager().sellUnit(key)) + "§7）");
        if (svc.manager().overLimit(key)) {
            s.sendMessage("§c超过兑换上限 " + Text.num(svc.manager().tradeMax()) + "：已禁止交易");
        }
        PriceEngine.Derivation dv = e.derive(key);
        if (dv == null) {
            s.sendMessage("§c没有价：材料链算不通，也没有手工价");
        } else if (dv.origin == PriceEngine.Origin.ANCHOR) {
            String org = svc.book().originOf(key);
            s.sendMessage("§7来源: §f" + ("addon".equals(org)
                    ? "附属内嵌（api.EmcRegistry）" : "基础定价表（" + org + "）"));
        } else if (dv.origin == PriceEngine.Origin.DROP) {
            s.sendMessage("§7来源: §f生物掉落物兜底价 §8（配方链算不出，按掉落概率补 "
                    + Text.num(v) + "；读不到概率按 "
                    + Text.num(svc.config().mobDropUnknownPrice) + "；/xzex set 可覆盖）");
        } else if (dv.origin == PriceEngine.Origin.GEO) {
            s.sendMessage("§7来源: §f地理资源默认价 §8（没有合成配方，按配置补 "
                    + Text.num(svc.config().geoResourcePrice) + "；/xzex set 可覆盖）");
        } else {
            s.sendMessage("§7来源: §f配方推导 §8（机器: " + dv.machine.getDisplayName() + "）");
            Recipe r = dv.recipe;
            double total = e.costOf(r);
            s.sendMessage("§7  配方总成本 §e" + Text.num(total)
                    + " §7→ 产出 §e" + r.getOutputs().getOrDefault(key, 1) + " §7个");
            for (Map.Entry<ItemKey, Integer> it : r.getInputs().entrySet()) {
                s.sendMessage("§8   - " + it.getValue() + " × " + it.getKey().getDisplayName()
                        + " §8@ " + Text.num(e.priceOf(it.getKey())));
            }
        }
        if (dv != null && dv.recipe != null) {
            Recipe r = dv.recipe;
            int out = Math.max(1, r.getOutputs().getOrDefault(key, 1));
            StringBuilder sb = new StringBuilder();
            double off = 0;
            for (Map.Entry<ItemKey, Integer> it : r.getInputs().entrySet()) {
                int n = (int) Math.ceil(it.getValue() * 1.0 / out - 1e-9);
                if (n <= 0) continue;
                double up = e.priceOf(it.getKey());
                if (up > 0) off += n * up;
                if (sb.length() < 70) {
                    if (sb.length() > 0) sb.append(" §7+ ");
                    sb.append("§f").append(n).append("×").append(it.getKey().getDisplayName());
                }
            }
            if (sb.length() > 0) {
                double need = Math.max(0, svc.manager().buyUnit(key) - off);
                s.sendMessage("§7材料抵扣: §f1 个 = " + sb + " §8（抵 §e" + Text.num(off)
                        + " §8，还需余额 §e" + Text.num(need) + "§8；交齐就不扣余额）");
            }
        }
        List<Recipe> cands = svc.registry().producersOf(key);
        if (cands.size() > 1) {
            s.sendMessage("§7其它候选配方（" + cands.size() + " 条）:");
            for (Recipe r : cands) {
                double cost = e.costOf(r);
                boolean machineReady = svc.isFreeMachine(r.getMachine()) || e.hasPrice(r.getMachine());
                s.sendMessage("§8   · " + r.getMachine().getDisplayName()
                        + (machineReady ? "" : " §c（机器没价，这条不算）")
                        + " → " + (Double.isNaN(cost) ? "算不出" : Text.num(cost / Math.max(1, r.getOutputs().getOrDefault(key, 1)))));
            }
        }
    }

    /** 追一条「为什么没价」的链条（最多 3 层，避免刷屏） */
    private void why(CommandSender s, String[] args) {
        ItemKey key = args.length >= 2 ? resolve(args[1])
                : (s instanceof Player p ? keyInHandOrCursor(p) : null);
        if (key == null) {
            s.sendMessage("§c[等价交换] 找不到这个物品：给个 id / 名称，或者把物品拿在手上");
            return;
        }
        if (svc.engine().hasPrice(key)) {
            s.sendMessage("§a[等价交换] " + key.getDisplayName() + " §7有价：§e"
                    + Text.num(svc.engine().priceOf(key)));
            return;
        }
        s.sendMessage("§6===== 为什么没有价: " + key.getDisplayName() + " §8(" + key + ")§6 =====");
        explain(s, key, 0, new java.util.HashSet<>());
        s.sendMessage("§8（机器本身没价的，可以先用 /xzex set 给那台机器定价，再 /xzex reload）");
    }

    private void explain(CommandSender s, ItemKey key, int depth, java.util.Set<ItemKey> seen) {
        String pad = "§8" + "  ".repeat(depth);
        PriceEngine e = svc.engine();
        if (e.hasPrice(key)) {
            s.sendMessage(pad + "§7" + key.getDisplayName() + " §a有价");
            return;
        }
        if (!seen.add(key)) {
            s.sendMessage(pad + "§7" + key.getDisplayName() + " §8（成环，跳过）");
            return;
        }
        List<Recipe> ps = svc.registry().producersOf(key);
        if (ps.isEmpty()) {
            s.sendMessage(pad + "§c" + key + " §7没有任何合成配方（掉落 / 创造 / 仅展示）");
            return;
        }
        s.sendMessage(pad + "§f" + key.getDisplayName() + " §8(" + key + ")");
        int shown = 0;
        for (Recipe r : ps) {
            if (shown++ >= 3) {
                s.sendMessage(pad + "  §8… 还有 " + (ps.size() - 3) + " 条配方");
                break;
            }
            ItemKey m = r.getMachine();
            boolean ready = svc.isFreeMachine(m) || e.hasPrice(m);
            s.sendMessage(pad + "  §8· " + m.getDisplayName()
                    + (ready ? " §a机器已有价" : " §c机器没价（不能用这条）"));
            List<ItemKey> missing = new ArrayList<>();
            for (ItemKey in : r.getInputs().keySet()) if (!e.hasPrice(in)) missing.add(in);
            if (!missing.isEmpty()) {
                StringBuilder sb = new StringBuilder(pad + "    §c缺材料: ");
                int k = 0;
                for (ItemKey mk : missing) {
                    if (k++ >= 4) { sb.append(" …"); break; }
                    sb.append(mk.getDisplayName()).append(" §8(").append(mk).append("§8) ");
                }
                s.sendMessage(sb.toString());
            }
            if (depth < 2) {
                List<ItemKey> next = new ArrayList<>();
                if (!ready && m != null) next.add(m);
                for (int i = 0; i < Math.min(2, missing.size()); i++) next.add(missing.get(i));
                for (ItemKey n : next) explain(s, n, depth + 1, seen);
            }
        }
    }

    /**
     * 断链的根：没配方、或所有配方都被「机器没价」卡住的物品。
     * 给它们一个锚点价（/xzex set 或 prices.yml），下游就能一次解锁一堆。
     */
    private void roots(CommandSender s, String[] args) {
        int n = 15;
        if (args.length >= 2) {
            try { n = Math.max(1, Math.min(50, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
        }
        PriceEngine e = svc.engine();
        java.util.Set<ItemKey> all = new java.util.HashSet<>();
        for (Recipe r : svc.registry().all()) {
            all.addAll(r.getInputs().keySet());
            all.addAll(r.getOutputs().keySet());
        }
        List<ItemKey> cands = new ArrayList<>();
        for (ItemKey k : all) {
            if (e.hasPrice(k)) continue;
            boolean allBlocked = true;
            for (Recipe r : svc.registry().producersOf(k)) {
                if (svc.isFreeMachine(r.getMachine()) || e.hasPrice(r.getMachine())) {
                    allBlocked = false;
                    break;
                }
            }
            if (allBlocked) cands.add(k);
        }
        List<Object[]> scored = new ArrayList<>(cands.size());
        for (ItemKey k : cands) scored.add(new Object[]{k, downstream(e, k)});
        scored.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        s.sendMessage("§6===== 断链的根 " + cands.size() + " 个（按下游影响面排序）=====");
        for (int i = 0; i < n && i < scored.size(); i++) {
            ItemKey k = (ItemKey) scored.get(i)[0];
            int c = (Integer) scored.get(i)[1];
            s.sendMessage("§7" + (i + 1) + ". §f" + k.getDisplayName() + " §8(" + k + ") §e"
                    + c + " §7个下游");
        }
        s.sendMessage("§8给根定价: /xzex set <id> <价格> 然后 /xzex reload；或直接改 prices.yml 的 slimefun: 段");
    }

    /**
     * 给这个物品一个价，估算能连带补上多少下游物品。
     *
     * <p>只数两层（直接产物 + 再下一层）：整张图的连通性太强，
     * 无限往下数每个根都是"三千多个下游"，排名就没有意义了。
     */
    private int downstream(PriceEngine e, ItemKey root) {
        java.util.Set<ItemKey> seen = new java.util.HashSet<>();
        java.util.List<ItemKey> frontier = new java.util.ArrayList<>();
        frontier.add(root);
        seen.add(root);
        int count = 0;
        for (int hop = 0; hop < 2; hop++) {
            java.util.List<ItemKey> next = new java.util.ArrayList<>();
            for (ItemKey cur : frontier) {
                for (Recipe r : svc.registry().consumersOf(cur)) {
                    for (ItemKey out : r.getOutputs().keySet()) {
                        if (!seen.add(out)) continue;
                        if (!e.hasPrice(out)) count++;
                        next.add(out);
                    }
                }
            }
            frontier = next;
        }
        return count;
    }

    private void top(CommandSender s, String[] args) {
        int n = 20;
        if (args.length >= 2) {
            try { n = Math.max(1, Math.min(100, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
        }
        List<ItemKey> list = new ArrayList<>(svc.manager().catalog());
        list.sort(Comparator.comparingDouble((ItemKey k) -> -svc.engine().priceOf(k)));
        s.sendMessage("§6===== 最贵的 " + Math.min(n, list.size()) + " 个 =====");
        for (int i = 0; i < n && i < list.size(); i++) {
            ItemKey k = list.get(i);
            s.sendMessage("§7" + (i + 1) + ". §f" + k.getDisplayName()
                    + " §8(" + k + ") §e" + Text.num(svc.engine().priceOf(k)));
        }
    }

    private void export(CommandSender s) {
        PriceEngine e = svc.engine();
        StringBuilder sb = new StringBuilder();
        sb.append("# 等价交换（独立版）全量价表\n");
        sb.append("# 口径: 买卖同价 · 不考虑环（结算后不下调） · 手续费 ").append(svc.config().tax).append("\n");
        sb.append("# 配方 ").append(svc.registry().size()).append(" 条 / ")
                .append(svc.registry().machines().size()).append(" 台机器\n");
        sb.append("# 物品 ").append(e.pricedCount()).append(" 有价 · 锚点 ").append(e.anchorCount())
                .append(" · 掉落兜底 ").append(e.dropCount())
                  .append(" · 配方推导 ").append(e.recipeCount())
                .append(" · 算不出 ").append(e.unpricedCount()).append("\n");
        sb.append("## key\tname\tprice\torigin\tmachine\n");
        List<ItemKey> keys = new ArrayList<>(e.pricedKeys());
        keys.sort(Comparator.comparing(ItemKey::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        for (ItemKey k : keys) {
            PriceEngine.Derivation dv = e.derive(k);
            String origin = dv == null ? "?" : switch (dv.origin) {
                case ANCHOR -> "anchor:" + svc.book().originOf(k);
                case GEO -> "geo-default";
                case DROP -> "mob-drop";
                case RECIPE -> "recipe";
            };
            String machine = dv == null || dv.machine == null ? "" : dv.machine.toString();
            sb.append(k).append('\t').append(Text.plain(k.getDisplayName())).append('\t')
                    .append(e.priceOf(k)).append('\t').append(origin).append('\t')
                    .append(machine).append('\n');
        }
        File dir = new File(plugin.getDataFolder(), "diag");
        if (!dir.exists() && !dir.mkdirs()) {
            s.sendMessage("§c[等价交换] 无法创建 diag 目录");
            return;
        }
        File out = new File(dir, "exchange-export.txt");
        try {
            Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            s.sendMessage("§a[等价交换] 已导出 " + e.pricedCount() + " 条 → §f" + out.getPath());
        } catch (Throwable t) {
            s.sendMessage("§c[等价交换] 导出失败: " + t);
        }
    }

    private void set(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage("§c用法: /xzex set <物品> <价格>");
            return;
        }
        ItemKey key = resolve(args[1]);
        if (key == null) {
            s.sendMessage("§c[等价交换] 找不到这个物品: " + args[1]);
            return;
        }
        double v;
        try { v = Double.parseDouble(args[2]); } catch (NumberFormatException ex) {
            s.sendMessage("§c价格必须是数字");
            return;
        }
        if (!(v >= 0)) { s.sendMessage("§c价格不能是负数"); return; }
        v = Text.round2(v);                        // 精度两位小数（腐竹 2026-10-18）
        if (!svc.book().setManual(key, v)) {
            s.sendMessage("§c写 prices.yml 失败，看后台日志");
            return;
        }
        svc.reloadPrices();
        s.sendMessage("§a[等价交换] " + key.getDisplayName() + " 的手工价 = §e" + Text.num(v));
    }

    private void unset(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("§c用法: /xzex unset <物品>"); return; }
        ItemKey key = resolve(args[1]);
        if (key == null) { s.sendMessage("§c[等价交换] 找不到这个物品: " + args[1]); return; }
        svc.book().unsetManual(key);
        svc.reloadPrices();
        s.sendMessage("§a[等价交换] 已删除 " + key.getDisplayName() + " 的手工价（回到基础价）");
    }

    /**
     * 每个玩家自己的「超级菜单」开关（腐竹 2026-10-18 第七轮）。
     * 不带参数 = 切换；也可以 {@code /xzex menu on|off} 明确设置。
     */
    private void menu(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("§c请在游戏里用这条命令");
            return;
        }
        boolean on;
        if (args.length >= 2) {
            String v = args[1].toLowerCase(Locale.ROOT);
            if (v.equals("on") || v.equals("true") || v.equals("开")) on = true;
            else if (v.equals("off") || v.equals("false") || v.equals("关")) on = false;
            else {
                p.sendMessage("§c用法: /xzex menu [on|off]");
                return;
            }
        } else {
            on = !svc.viewStore().modEnabled(p.getUniqueId());
        }
        svc.viewStore().setModEnabled(p.getUniqueId(), on);
        if (on) {
            p.sendMessage("§a[等价交换] 超级菜单已开启"
                    + (net.me.xinzhi.exchange.menu.MenuChannel.isHandshaked(p)
                        ? "（下次打开界面生效）" : "§7（没检测到客户端 mod，仍用普通界面）"));
        } else {
            p.sendMessage("§7[等价交换] 超级菜单已关闭，改用普通界面（下次打开生效）");
        }
    }

    private void balance(CommandSender s, String[] args) {
        // /xzex balance set|add <玩家> <数量> —— 直接改玩家 EMC（腐竹 2026-10-19 要求）
        if (args.length >= 2 && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("add"))) {
            if (!admin(s)) return;
            if (args.length < 4) {
                s.sendMessage("§c用法: /xzex balance " + args[1].toLowerCase(Locale.ROOT) + " <玩家> <数量>");
                return;
            }
            Player t = Bukkit.getPlayerExact(args[2]);
            if (t == null) {
                s.sendMessage("§c[等价交换] 玩家不在线: " + args[2]);
                return;
            }
            double v;
            try {
                v = parseAmount(args[3]);
            } catch (NumberFormatException ex) {
                s.sendMessage("§c[等价交换] 数量要填数字（可写 10w / 100k / 1万）");
                return;
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                s.sendMessage("§c[等价交换] 数量不合法");
                return;
            }
            if (args[1].equalsIgnoreCase("set")) {
                svc.manager().setBalance(t.getUniqueId(), Math.max(0, v));
            } else {
                svc.manager().addBalance(t.getUniqueId(), v);
            }
            s.sendMessage("§a[等价交换] " + t.getName() + " 现在有 §e"
                    + Text.num(svc.manager().balance(t.getUniqueId())) + " §7EMC");
            return;
        }

        Player target;
        if (args.length >= 2) {
            if (!admin(s)) return;
            target = Bukkit.getPlayerExact(args[1]);
        } else if (s instanceof Player p) {
            target = p;
        } else {
            s.sendMessage("§c控制台请写玩家名: /xzex balance <玩家>");
            return;
        }
        if (target == null) { s.sendMessage("§c玩家不在线"); return; }
        s.sendMessage("§7" + target.getName() + " 的价值余额: §e"
                + Text.num(svc.manager().balance(target.getUniqueId())));
    }

    /** 发管理道具 + 三台自动机器（方便直接拿；机器本身也有合成配方） */
    private void adminItems(CommandSender s) {
        if (!admin(s)) return;
        if (!(s instanceof Player p)) {
            s.sendMessage("§c请在游戏里用这条命令");
            return;
        }
        int n = 0;
        for (String id : List.of("XZEX_PRICE_CUSTOM", "XZEX_PRICE_BATCH",
                "XZEX_AUTO_SELL", "XZEX_AUTO_BUY", "XZEX_EMC_GENERATOR")) {
            try {
                SlimefunItem sf = SlimefunItem.getById(id);
                if (sf == null) {
                    p.sendMessage("§c[等价交换] 找不到道具 " + id);
                    continue;
                }
                ItemStack it = sf.getItem().clone();
                p.getInventory().addItem(it);
                n++;
            } catch (Throwable t) {
                p.sendMessage("§c[等价交换] 发放 " + id + " 失败: " + t);
            }
        }
        p.sendMessage("§a[等价交换] 已发放 " + n + " 个道具（管理道具 + 三台自动机器）");
    }

    /** 第三方附属注册了哪些 EMC（诊断用；标出被服主覆盖的） */
    private void emc(CommandSender s) {
        java.util.Map<ItemKey, Double> all = net.me.xinzhi.exchange.api.EmcRegistry.all();
        s.sendMessage("§6===== 附属内嵌 EMC " + all.size() + " 条 =====");
        if (all.isEmpty()) {
            s.sendMessage("§7（没有。第三方附属用 §fapi.EmcRegistry.register(...)§7 注册）");
            return;
        }
        int n = 0;
        for (java.util.Map.Entry<ItemKey, Double> e : all.entrySet()) {
            if (++n > 50) {
                s.sendMessage("§8… 还有 " + (all.size() - 50) + " 条");
                break;
            }
            ItemKey key = e.getKey();
            String org = svc.book().originOf(key);
            String state = "addon".equals(org) ? "§a生效" : "§7被 " + org + " 覆盖";
            s.sendMessage("§7- §f" + key.getDisplayName() + " §8(" + key + ") §e"
                    + Text.num(e.getValue()) + " " + state);
        }
        s.sendMessage("§8优先级: 手工价 > prices.yml > 附属内嵌 > 内置表 > 配方推导");
    }

    /**
     * 兑换价格上限：单价超过它的物品禁止交易。
     *
     * <p>腐竹 2026-10-19：「增加兑换价格上限的功能，比如腐竹设置上限为10w，那么所有emc值
     * 高于10w的物品都不允许交易。」运行期改完写回 config.yml 的 {@code price.trade-max}，
     * 不用重启；{@code off} / {@code 0} = 不限。
     */
    private void limit(CommandSender s, String[] args) {
        double cur = svc.manager().tradeMax();
        if (args.length < 2) {
            s.sendMessage("§6兑换上限: " + (cur > 0 ? "§e" + Text.num(cur) : "§a不限"));
            s.sendMessage("§7用法: §f/xzex limit <金额|off> §7例: §f10w §7= 100000；§foff §7= 不限");
            return;
        }
        String raw = args[1].trim().toLowerCase(Locale.ROOT).replace(",", "");
        double v;
        if (raw.equals("off") || raw.equals("none") || raw.equals("关")) {
            v = 0;
        } else {
            double mul = 1;
            if (raw.endsWith("w") || raw.endsWith("万")) {
                mul = 10_000;
                raw = raw.substring(0, raw.length() - 1);
            } else if (raw.endsWith("k")) {
                mul = 1_000;
                raw = raw.substring(0, raw.length() - 1);
            }
            try {
                v = Double.parseDouble(raw) * mul;
            } catch (NumberFormatException ex) {
                s.sendMessage("§c[等价交换] 上限要填数字（可写 10w / 100k / off）");
                return;
            }
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                s.sendMessage("§c[等价交换] 上限不能是负数");
                return;
            }
        }
        svc.manager().setTradeMax(v);
        plugin.getConfig().set("price.trade-max", v);
        plugin.saveConfig();
        svc.manager().refreshCatalog();
        s.sendMessage("§a[等价交换] 兑换上限 = " + (v > 0 ? "§e" + Text.num(v) : "§a不限")
                + " §7（已写回 config.yml；当前 §c" + svc.manager().overLimitCount()
                + " §7个物品被拦，不再出现在清单里）");
    }

    private void give(CommandSender s, String[] args) {
        // 腐竹 2026-10-26：给余额 = 管理员权限（等价于凭空发钱）
        if (!admin(s)) return;
        if (args.length < 3) { s.sendMessage("§c用法: /xzex give <玩家> <数量>"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { s.sendMessage("§c玩家不在线"); return; }
        double amount;
        try { amount = Double.parseDouble(args[2]); } catch (NumberFormatException ex) {
            s.sendMessage("§c数量必须是数字"); return;
        }
        svc.manager().addBalance(target.getUniqueId(), amount);
        s.sendMessage("§a[等价交换] " + target.getName() + " 现在有 §e"
                + Text.num(svc.manager().balance(target.getUniqueId())));
    }

    // ============================================================
    // 原版·无配方清单
    // ============================================================

    /**
     * 列出「原版物品里没有任何配方能产出」的那些（腐竹 2026-10-29 原话：
     * 「能列出原版物品中没有合成配方的物品让我自行定价吗？或者制作一个扫描道具让我在游戏中定价也行」）。
     *
     * <p>为什么是「无配方」这一批：价表给所有原版材质都垫了 1.0 兜底，配方又只收粘液的，
     * 所以原版物品的价实际上全是手工/内置表拍进去的。真正<b>非拍不可</b>的，就是这批
     * 连原版配方都没有的物品 —— 它们撑起整张原版价图，改一个会顺价传下去。
     *
     * <p>用法：
     * <ul>
     *   <li>{@code /xzex norecipe} 前 30 条（按当前价降序）+ 导出完整清单</li>
     *   <li>{@code /xzex norecipe 100} 看前 100 条</li>
     *   <li>{@code /xzex norecipe add} 全部丢进批量定价面板，直接在里面填价</li>
     * </ul>
     */
    /**
     * /xzex recalc —— 「有配方、但现在吃的是锚点价」的物品，用配方重算一遍做对照。
     *
     * <p>腐竹 2026-10-29 的原话：「石英是 4，石英块咋是 36，不应该是 16 吗？」
     * 根因是 prices.yml 里每一条都是锚点，锚点压过配方推导。这条命令就是拿来看
     * 「如果不压，配方会算成多少」，只读，不改任何价。
     */
    private void recalc(CommandSender s, String[] args) {
        List<ItemKey> cand = new ArrayList<>();
        for (ItemKey k : svc.book().anchors().keySet()) {
            if (svc.registry().producersOf(k).isEmpty()) continue;
            cand.add(k);
        }
        if (cand.isEmpty()) { s.sendMessage("§7[等价交换] 没有「既有配方又吃锚点价」的物品"); return; }

        long t0 = System.currentTimeMillis();
        Map<ItemKey, Double> derived = svc.derivationWithoutAnchors(cand);
        long cost = System.currentTimeMillis() - t0;

        List<RecalcRow> rows = new ArrayList<>();
        for (ItemKey k : cand) {
            Double now = derived.get(k);
            if (now == null) continue;              // 去掉它自己的锚点后还是推不出来（配方里要它自己）
            rows.add(new RecalcRow(k, svc.engine().priceOf(k), now));
        }
        rows.sort(Comparator.comparingDouble(r -> -Math.abs(Math.log(Math.max(1e-9, r.ratio)))));

        int limit = 20;
        if (args.length >= 2) {
            try { limit = Math.max(1, Math.min(200, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) { }
        }

        s.sendMessage("§6===== 锚定价 vs 配方推导 §8(试算 " + rows.size() + " 个，耗时 " + cost + "ms) §6=====");
        int shown = 0, big = 0;
        for (RecalcRow r : rows) {
            if (Math.abs(r.ratio - 1) > 0.25) big++;
            if (shown >= limit) continue;
            shown++;
            s.sendMessage("§7" + shown + ". §f" + Text.plain(r.k.getDisplayName())
                    + " §8现 §e" + Text.num(r.cur) + " §8→ 推导 §a" + Text.num(r.now)
                    + " §8(×" + Text.num(Math.round(r.ratio * 100) / 100.0) + ") §7" + svc.book().originOf(r.k));
        }
        if (rows.size() > shown) s.sendMessage("§8… 还有 " + (rows.size() - shown) + " 个（§7/xzex recalc " + rows.size() + "§8）");
        s.sendMessage("§7偏离超过 25% 的共 §e" + big + " §7个");
        s.sendMessage("§a完整对照已导出 → §f" + writeRecalc(rows));
        s.sendMessage("§8只读试算：现在的价一个都没改");
    }

    private String writeRecalc(List<RecalcRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 等价交换｜锚定价 vs 配方推导 试算（只读，不改价）\n");
        sb.append("# 口径：把这些物品的锚点临时去掉，用现有配方重算一遍\n");
        sb.append("# key\tname\tcurrent\tderived\tratio\torigin\trecipes\n");
        for (RecalcRow r : rows) {
            sb.append(r.k).append('\t').append(Text.plain(r.k.getDisplayName())).append('\t')
                    .append(r.cur).append('\t').append(r.now).append('\t')
                    .append(r.cur <= 0 ? "" : Math.round(r.ratio * 1000) / 1000.0).append('\t')
                    .append(svc.book().originOf(r.k)).append('\t')
                    .append(svc.registry().producersOf(r.k).size()).append('\n');
        }
        File dir = new File(plugin.getDataFolder(), "diag");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "anchor-vs-recipe.tsv");
        try {
            Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return out.getPath();
        } catch (Throwable t) {
            return "写文件失败: " + t;
        }
    }

    /** 一行试算结果：现价 / 去掉锚点后的推导价 */
    private static final class RecalcRow {
        final ItemKey k;
        final double cur;
        final double now;
        final double ratio;
        RecalcRow(ItemKey k, double cur, double now) {
            this.k = k; this.cur = cur; this.now = now;
            this.ratio = cur <= 0 ? Double.MAX_VALUE : now / cur;
        }
    }

    private void noRecipe(CommandSender s, String[] args) {
        List<Material> all = new ArrayList<>(VanillaRecipes.withoutRecipe());
        int size = all.size();

        if (!VanillaRecipes.scanned()) {
            s.sendMessage("§c[等价交换] 原版配方索引还没建好（服务端不支持配方迭代或扫描失败），清单不可信");
            if (VanillaRecipes.failed()) return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("add")) {
            if (!(s instanceof Player p)) { s.sendMessage("§c这条要在游戏里用"); return; }
            LinkedHashMap<ItemKey, Double> session = svc.pricingGui().session(p);
            int added = 0;
            for (Material m : all) {
                ItemKey k = ItemKey.vanilla(m);
                if (k == null) continue;
                if (session.putIfAbsent(k, null) == null) added++;
            }
            svc.pricingGui().openBatch(p, 0);
            p.sendMessage("§a[等价交换] 已把 §e" + added + " §a个「原版·无配方」物品加进批量定价清单"
                    + "（清单共 §e" + session.size() + " §a个）");
            p.sendMessage("§7左键逐个填价，或 §f💰统一定价§7，最后 §f✔保存并应用§7 才生效");
            return;
        }

        int limit = 30;
        if (args.length >= 2) {
            try { limit = Math.max(1, Math.min(200, Integer.parseInt(args[1]))); }
            catch (NumberFormatException ignored) { }
        }

        // 按当前价降序：贵的在上面，改它们的收益最大
        all.sort(Comparator.comparingDouble(m -> {
            ItemKey k = ItemKey.vanilla(m);
            return k == null ? 0 : -svc.engine().priceOf(k);
        }));

        s.sendMessage("§6===== 原版·无配方 §8(" + size + " 个) §6=====");
        int flat = 0;
        int shown = 0;
        for (Material m : all) {
            ItemKey k = ItemKey.vanilla(m);
            if (k == null) continue;
            double v = svc.engine().priceOf(k);
            String origin = svc.book().originOf(k);
            boolean fallback = "builtin".equals(origin) && Math.abs(v - 1.0) < 1e-9;
            if (fallback) flat++;
            if (shown >= limit) continue;
            shown++;
            s.sendMessage("§7" + shown + ". §f" + k.getDisplayName() + " §8(" + m.name() + ") §7价 §e"
                    + Text.num(v) + (fallback ? " §8兜底 1.0" : " §8" + origin));
        }
        if (size > shown) {
            s.sendMessage("§8… 还有 " + (size - shown) + " 个没显示（§7/xzex norecipe " + size + "§8 看全部）");
        }
        s.sendMessage("§7其中 §e" + flat + " §7个还在内置兜底价 §e1.0 §7（等于没定过价）");
        s.sendMessage("§a完整清单已导出 → §f" + writeNoRecipe(all));
        s.sendMessage("§8游戏内直接定价: §f/xzex norecipe add");
    }

    /** 写 diag/vanilla-norecipe.tsv（tab 分隔：key / name / price / origin） */
    private String writeNoRecipe(List<Material> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 等价交换（独立版）原版·无配方清单\n");
        sb.append("# 没有任何原版配方（工作台/熔炉/高炉/烟熏/切石机/锻造台/营火）能产出它们，价只能手工给\n");
        sb.append("# key\tname\tprice\torigin\n");
        for (Material m : all) {
            ItemKey k = ItemKey.vanilla(m);
            if (k == null) continue;
            sb.append(k).append('\t')
              .append(Text.plain(k.getDisplayName())).append('\t')
              .append(svc.engine().priceOf(k)).append('\t')
              .append(svc.book().originOf(k)).append('\n');
        }
        try {
            File dir = new File(plugin.getDataFolder(), "diag");
            if (!dir.exists() && !dir.mkdirs()) return "(无法创建 diag 目录)";
            File out = new File(dir, "vanilla-norecipe.tsv");
            Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return out.getPath();
        } catch (Throwable t) {
            return "(导出失败: " + t + ")";
        }
    }

    // ============================================================

    /** 浏览全库（top / roots）：腐竹 2026-10-18「只准 op 能直接查看所有物品」 */
    private boolean viewAll(CommandSender s) {
        if (s.hasPermission("xzex.viewall")) return true;
        s.sendMessage("§c[等价交换] 只有管理员（op）能直接查看全部物品");
        return false;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("xzex.admin")) return true;
        s.sendMessage("§c你没有权限: xzex.admin");
        return false;
    }

    private ExchangeGui.Sort sortDefault() {
        return ExchangeGui.Sort.parse(svc.config().defaultSort, ExchangeGui.Sort.PRICE_DESC);
    }

    /** 解析物品：SF:id / VANILLA:MAT / 材质名 / 粘液 id / 名称包含 */
    public ItemKey resolve(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        ItemKey direct = ItemKey.fromStored(s);
        if (direct != null) return direct;

        try {
            SlimefunItem sf = SlimefunItem.getById(s.toUpperCase(Locale.ROOT));
            if (sf != null) return ItemKey.of(sf);
        } catch (Throwable ignored) {}
        Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        if (m != null && m.isItem()) return ItemKey.vanilla(m);

        String kw = s.toLowerCase(Locale.ROOT);
        for (ItemKey k : svc.manager().catalog()) {
            if (k.getDisplayName().toLowerCase(Locale.ROOT).contains(kw)
                    || k.toString().toLowerCase(Locale.ROOT).contains(kw)) return k;
        }
        return null;
    }

    private static ItemKey keyInHandOrCursor(Player p) {
        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) return ItemKey.of(cursor);
        ItemStack hand = p.getInventory().getItemInMainHand();
        return ItemKey.of(hand);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBS) if (sub.startsWith(p)) out.add(sub);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("menu")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String v : List.of("on", "off")) if (v.startsWith(p)) out.add(v);
            return out;
        }
        return List.of();
    }
}
