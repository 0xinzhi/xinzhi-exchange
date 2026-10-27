package net.me.xinzhi.exchange.menu;

import org.bukkit.entity.Player;

/** 一次超大菜单点击（服务端从 CLICK 报文还原）。客户端只能表达「第几格、哪个键、有没有 shift」。 */
public record MenuClick(Player player, int slot, int button, boolean shift) {
}
