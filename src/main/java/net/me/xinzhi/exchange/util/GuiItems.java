package net.me.xinzhi.exchange.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** 界面图标小工具（全项目一处）。 */
public final class GuiItems {

    private GuiItems() {}

    public static ItemStack of(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack of(Material mat, String name, String... lore) {
        return of(mat, name, List.of(lore));
    }
}
