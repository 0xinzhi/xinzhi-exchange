package net.me.xinzhi.exchange.exchange;

import net.me.xinzhi.exchange.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家价值余额（本附属自己的账，不读也不写主附属的市场余额）。
 *
 * <p>落盘 {@code plugins/XinZhiExchange/balance.yml}：
 * <pre>balances:
 *   &lt;uuid&gt;: 12345.67</pre>
 *
 * <p>⚠ 只在主线程改内存，{@link #save()} 由定时任务 / 关服时调用。
 */
public final class BalanceStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();

    public BalanceStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balance.yml");
    }

    public File file() { return file; }

    public synchronized void load() {
        balances.clear();
        if (!file.isFile()) return;
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = yml.getConfigurationSection("balances");
            if (sec == null) return;
            for (String k : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    balances.put(id, sec.getDouble(k, 0));
                } catch (Throwable ignored) {}
            }
            plugin.getLogger().info("[等价交换] 余额已加载: " + balances.size() + " 个玩家");
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 读 balance.yml 失败: " + t);
        }
    }

    public synchronized void save() {
        if (balances.isEmpty() && !file.isFile()) return;
        try {
            YamlConfiguration yml = new YamlConfiguration();
            for (Map.Entry<UUID, Double> e : balances.entrySet()) {
                yml.set("balances." + e.getKey(), e.getValue());
            }
            yml.save(file);
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 写 balance.yml 失败: " + t);
        }
    }

    public double get(UUID id) { return balances.getOrDefault(id, 0.0); }

    public void set(UUID id, double v) {
        balances.put(id, Math.max(0, v));
    }

    public void add(UUID id, double delta) {
        balances.merge(id, delta, (a, b) -> Math.max(0, a + b));
    }

    public int size() { return balances.size(); }
}
