package net.me.xinzhi.exchange.menu;

/**
 * 超大菜单里一个格子的角色。wireId 与客户端 {@code ClientMenu} 的常量一一对应，
 * 也与主附属 {@code SlotRole} 的前三个值一致（0 空 / 1 展示 / 2 功能）。
 */
public enum SlotRole {
    EMPTY(0),
    DISPLAY(1),
    BUTTON(2);

    public final int wireId;

    SlotRole(int wireId) {
        this.wireId = wireId;
    }
}
