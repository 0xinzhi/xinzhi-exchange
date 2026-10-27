package net.me.xinzhi.exchange.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 「等玩家下一条聊天消息」的统一入口（全附属只有这一份）。
 *
 * <p>回调一律回主线程执行；输入 {@code cancel} 表示取消。
 * 1 分钟没输入就作废（过期的那句话<b>照常公开</b>，不当成输入值）。
 */
public final class ChatInput implements Listener {

    public static final long TIMEOUT_MS = 60_000L;

    private record Pending(Consumer<String> value, Runnable onCancel, long expireAt) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static JavaPlugin PLUGIN;
    private static boolean registered = false;

    private ChatInput() {}

    public static void init(JavaPlugin plugin) {
        if (plugin != null) PLUGIN = plugin;
        if (!registered && PLUGIN != null) {
            Bukkit.getPluginManager().registerEvents(new ChatInput(), PLUGIN);
            registered = true;
        }
    }

    /** 请求输入；同一玩家重复请求时只有最后一次生效 */
    public static void request(JavaPlugin plugin, Player p, String prompt,
                               Consumer<String> callback) {
        request(plugin, p, prompt, null, callback);
    }

    public static void request(JavaPlugin plugin, Player p, String prompt,
                               Runnable onCancel, Consumer<String> callback) {
        if (p == null || callback == null) return;
        init(plugin);
        PENDING.put(p.getUniqueId(),
                new Pending(callback, onCancel, System.currentTimeMillis() + TIMEOUT_MS));
        if (prompt != null) p.sendMessage(prompt);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Pending pending = PENDING.remove(p.getUniqueId());
        if (pending == null) return;

        if (System.currentTimeMillis() > pending.expireAt()) {
            Player who = p;
            Bukkit.getScheduler().runTask(PLUGIN, () -> who.sendMessage(
                    "§7上次的输入已超过 1 分钟未填写，已作废"));
            return;                       // 不拦这句话
        }
        e.setCancelled(true);

        String msg = e.getMessage() == null ? "" : e.getMessage().trim();
        boolean cancelled = msg.equalsIgnoreCase("cancel");
        Bukkit.getScheduler().runTask(PLUGIN, () -> {
            if (cancelled) {
                p.sendMessage("§7已取消");
                if (pending.onCancel() != null) pending.onCancel().run();
                return;
            }
            pending.value().accept(msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PENDING.remove(e.getPlayer().getUniqueId());
    }
}
