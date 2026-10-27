package net.me.xinzhi.exchange;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import net.me.xinzhi.exchange.core.ItemNames;
import net.me.xinzhi.exchange.exchange.ExchangeService;
import net.me.xinzhi.exchange.menu.MenuChannel;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 独立版「等价交换」附属。
 *
 * <p>它<b>不依赖</b> XinZhiTech：自己的配方库（扫描 + 落盘）、自己的价表
 * （prices.yml）、自己的余额（balance.yml）、自己的界面与命令。
 * 因此可以和主附属同时装在服务器上，互不干扰。
 *
 * <p>启动顺序：{@code depend: Slimefun} 保证 Slimefun 先加载；
 * 但<b>其它附属可能在之后才注册物品</b>，所以配方扫描不在 onEnable 里同步跑，
 * 而是延迟 {@code scan.delay-ticks}（默认 100 tick = 5 秒）后异步扫。
 */
public final class XinZhiExchange extends JavaPlugin implements SlimefunAddon {

    private static XinZhiExchange instance;

    private ExchangeService service;

    public static XinZhiExchange get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        ItemNames.load(this);
        MenuChannel.register(this);

        service = new ExchangeService(this);
        try {
            service.enable();
        } catch (Throwable t) {
            getLogger().severe("[等价交换] 初始化失败: " + t);
            t.printStackTrace();
        }

        // 启动时异步查一次 GitHub Releases（配置 update.check / update.download）
        net.me.xinzhi.exchange.util.UpdateChecker.start(this);
    }

    @Override
    public void onDisable() {
        try { MenuChannel.shutdown(); } catch (Throwable ignored) {}
        if (service != null) {
            try { service.disable(); } catch (Throwable ignored) {}
            service = null;
        }
        instance = null;
    }

    public ExchangeService service() { return service; }

    // ---- SlimefunAddon ----

    @Override
    public JavaPlugin getJavaPlugin() { return this; }

    @Override
    public String getBugTrackerURL() { return null; }
}
