package com.arcadia.adminpanel.item;

import com.arcadia.adminpanel.AdminPanelMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item registry for the admin panel. Hooked into the mod event bus from
 * {@link AdminPanelMod#AdminPanelMod}.
 *
 * @author vyrriox
 */
public final class AdminPanelItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, AdminPanelMod.MOD_ID);

    /**
     * The Jail Baton — staff-only tool that jails the right-clicked player. Stack-size 1 (it's
     * a tool, not loot) and rare rarity for the gold-coloured name.
     */
    public static final DeferredHolder<Item, JailBatonItem> JAIL_BATON =
            ITEMS.register("jail_baton",
                    () -> new JailBatonItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE).fireResistant()));

    private AdminPanelItems() {}
}
