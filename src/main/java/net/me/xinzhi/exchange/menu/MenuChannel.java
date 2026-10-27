package net.me.xinzhi.exchange.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端超大菜单通道 {@code xinzhi:menu} 的<b>独立实现</b>。
 *
 * <p>本附属不依赖主附属：即使服务器只装「正常版」xinzhitech（不注册菜单通道）、
 * 甚至不装 xinzhitech，兑换界面也能通过这里下发 mod 版菜单。</p>
 *
 * <h2>安全模型</h2>
 * 客户端只发 HELLO{protocol} 与 CLICK{sessionId, area, slot, button, shift}；
 * 服务端逐项自证：会话存在 → sessionId 对得上 → area=GRID → 格号在界内 → 那格是 BUTTON →
 * 回调是开菜单时服务端自己注册的。客户端表达不了「我要买/卖/给多少」。</p>
 *
 * <h2>与主附属共存</h2>
 * 两个插件可以同时注册同一个入站通道（Paper 会把消息发给每个监听者）。
 * 本类会话 id 从 {@link #SESSION_BASE}（100 万）起，和主附属从 1 起的 id 不相交，
 * 所以双方都只会认领自己的点击。</p>
 */
public final class MenuChannel {

    public static final String CHANNEL = "xinzhi:menu";
    /** 必须与客户端 {@code MenuClient.PROTOCOL_VERSION} 一致。 */
    public static final int PROTOCOL_VERSION = 5;

    private static final int C2S_HELLO = 0x01;
    private static final int C2S_CLICK = 0x02;
    private static final int AREA_GRID = 0;
    private static final int MAX_PACKETS_PER_SECOND = 20;
    /** 和主附属的会话 id 错开，避免同时注册通道时互相认领点击。 */
    private static final int SESSION_BASE = 1_000_000;

    private static final Map<UUID, Integer> HANDSHAKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> RATE = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_SESSION = new AtomicInteger(SESSION_BASE);

    private static JavaPlugin plugin;

    private MenuChannel() {
    }

    private record Session(int id, MenuModel menu) {
    }

    public static void register(JavaPlugin owner) {
        plugin = owner;
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
        owner.getServer().getMessenger().registerIncomingPluginChannel(owner, CHANNEL, MenuChannel::onMessage);
        owner.getServer().getPluginManager().registerEvents(new Watcher(), owner);
        owner.getLogger().info("[等价交换] 超大菜单通道已注册: " + CHANNEL + "（协议 " + PROTOCOL_VERSION + "）");
    }

    public static void shutdown() {
        HANDSHAKED.clear();
        SESSIONS.clear();
        RATE.clear();
    }

    /** 这个玩家是不是装了客户端 mod 并且握手成功。 */
    public static boolean isHandshaked(Player p) {
        return p != null && HANDSHAKED.containsKey(p.getUniqueId());
    }

    /** 下发一张菜单；返回 false = 调用方必须回退原版界面。 */
    public static boolean open(Player p, MenuModel menu) {
        if (plugin == null || p == null || menu == null || !isHandshaked(p)) return false;
        int id = NEXT_SESSION.getAndIncrement();
        if (id <= SESSION_BASE) {
            NEXT_SESSION.set(SESSION_BASE + 1);
            id = SESSION_BASE + 1;
        }
        byte[] frame;
        try {
            frame = MenuWire.encodeOpen(id, menu);
        } catch (Throwable t) {
            plugin.getLogger().warning("[等价交换] 超大菜单编码失败（回退原版）: " + t);
            return false;
        }
        if (frame.length > MenuWire.MAX_FRAME_BYTES) {
            plugin.getLogger().warning("[等价交换] 超大菜单帧过大 " + frame.length + " B（回退原版）");
            return false;
        }
        SESSIONS.put(p.getUniqueId(), new Session(id, menu));
        try {
            // 关掉服务端认为还开着的容器，否则客户端被切到自绘界面后，
            // 服务端还挂着一个 syncId 对不上的旧容器（箱子/机器菜单）。
            // ⚠ 只在真有容器时关：mod 界面下服务端看到的是 CRAFTING（玩家背包），
            //   对它发 close 可能把客户端正开着的 SuperMenuScreen 一起关掉。
            if (p.getOpenInventory().getType() != InventoryType.CRAFTING) {
                p.closeInventory();
            }
            p.sendPluginMessage(plugin, CHANNEL, frame);
            return true;
        } catch (Throwable t) {
            SESSIONS.remove(p.getUniqueId());
            plugin.getLogger().warning("[等价交换] 超大菜单下发失败（回退原版）: " + t);
            return false;
        }
    }

    public static void close(Player p) {
        if (p == null) return;
        Session s = SESSIONS.remove(p.getUniqueId());
        if (s == null || plugin == null || !isHandshaked(p)) return;
        try {
            p.sendPluginMessage(plugin, CHANNEL, MenuWire.encodeClose(s.id));
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    // 收包
    // ============================================================

    private static void onMessage(String channel, Player player, byte[] message) {
        if (message == null || message.length < 1 || player == null) return;
        if (!allowPacket(player)) return;
        try {
            int[] pos = {0};
            int op = message[pos[0]++] & 0xFF;
            if (op == C2S_HELLO) {
                int protocol = readVarInt(message, pos);
                if (protocol == PROTOCOL_VERSION) {
                    HANDSHAKED.put(player.getUniqueId(), protocol);
                } else {
                    HANDSHAKED.remove(player.getUniqueId());
                    SESSIONS.remove(player.getUniqueId());
                }
            } else if (op == C2S_CLICK) {
                int sessionId = readVarInt(message, pos);
                int area = message[pos[0]++] & 0xFF;
                int slot = readVarInt(message, pos);
                int button = message[pos[0]++] & 0xFF;
                boolean shift = message[pos[0]++] != 0;
                onClick(player, sessionId, area, slot, button, shift);
            }
            // 其余操作码（DRAG / PROBE …）是主附带的语义，本类不处理，静默忽略。
        } catch (Throwable ignored) {
        }
    }

    private static void onClick(Player player, int sessionId, int area, int slot, int button, boolean shift) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.id != sessionId) return;
        if (area != AREA_GRID) return;
        if (slot < 0 || slot >= session.menu.size()) return;
        if (session.menu.roleAt(slot) != SlotRole.BUTTON) return;
        var handler = session.menu.handlerAt(slot);
        if (handler == null) return;
        try {
            handler.accept(new MenuClick(player, slot, button, shift));
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().warning("[等价交换] 处理 " + player.getName()
                        + " 的菜单点击失败（格 " + slot + "）: " + t);
            }
        }
    }

    private static boolean allowPacket(Player player) {
        long second = System.currentTimeMillis() / 1000L;
        int[] window = RATE.computeIfAbsent(player.getUniqueId(), k -> new int[]{(int) second, 0});
        synchronized (window) {
            if (window[0] != (int) second) {
                window[0] = (int) second;
                window[1] = 0;
            }
            return ++window[1] <= MAX_PACKETS_PER_SECOND;
        }
    }

    private static int readVarInt(byte[] data, int[] pos) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = data[pos[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 35) throw new IllegalArgumentException("VarInt 过长");
        }
    }

    /** 退出 / 死亡即清状态，免得攒死会话。 */
    private static final class Watcher implements Listener {

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            HANDSHAKED.remove(id);
            SESSIONS.remove(id);
            RATE.remove(id);
        }

        @EventHandler
        public void onDeath(PlayerDeathEvent event) {
            UUID id = event.getEntity().getUniqueId();
            SESSIONS.remove(id);
        }
    }
}
