package net.me.xinzhi.exchange.menu;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 线格式编码器 —— 与客户端 {@code com.xinzhi.menu.MenuWire} 逐字节对齐（OPEN 0x81 / CLOSE 0x83）。
 * 只实现兑换界面用得到的部分：OPEN（含调色板 + 行程编码）与 CLOSE。
 */
public final class MenuWire {

    public static final int OPEN = 0x81;
    public static final int CLOSE = 0x83;
    /** Paper 的插件消息硬上限（实测 1 MiB）。 */
    public static final int MAX_FRAME_BYTES = 1048576;

    private MenuWire() {
    }

    public static byte[] encodeOpen(int sessionId, MenuModel menu) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        out.write(OPEN);
        writeVarInt(out, sessionId);
        writeVarInt(out, menu.cols());
        writeVarInt(out, menu.rows());
        writeString(out, menu.title());

        int size = menu.size();
        int[] refs = new int[size];
        List<ItemStack> palette = new ArrayList<>();
        Map<ItemStack, Integer> index = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ItemStack item = menu.itemAt(i);
            if (item == null || item.getType().isAir()) {
                refs[i] = -1;
                continue;
            }
            Integer at = index.get(item);
            if (at == null) {
                at = palette.size();
                palette.add(item);
                index.put(item, at);
            }
            refs[i] = at;
        }

        writeVarInt(out, palette.size());
        for (ItemStack item : palette) {
            byte[] nbt = item.serializeAsBytes();
            writeVarInt(out, nbt.length);
            out.write(nbt, 0, nbt.length);
        }

        List<int[]> runs = new ArrayList<>();
        int start = 0;
        while (start < size) {
            SlotRole role = menu.roleAt(start);
            int ref = refs[start];
            int end = start;
            while (end + 1 < size && menu.roleAt(end + 1) == role && refs[end + 1] == ref) {
                end++;
            }
            runs.add(new int[]{start, end, role.wireId, ref});
            start = end + 1;
        }
        writeVarInt(out, runs.size());
        for (int[] run : runs) {
            writeVarInt(out, run[0]);
            writeVarInt(out, run[1]);
            writeVarInt(out, run[2]);
            writeVarInt(out, run[3] + 1);
        }
        return out.toByteArray();
    }

    public static byte[] encodeClose(int sessionId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(6);
        out.write(CLOSE);
        writeVarInt(out, sessionId);
        return out.toByteArray();
    }

    public static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String text) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
