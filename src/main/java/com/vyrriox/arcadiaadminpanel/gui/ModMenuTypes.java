package com.vyrriox.arcadiaadminpanel.gui;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Custom MenuType registration for themed admin screens.
 *
 * @author vyrriox
 */
public final class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, "arcadiaadminpanel");

    public static final Supplier<MenuType<AdminPanelMenu>> ADMIN_PANEL =
            MENUS.register("admin_panel", () -> IMenuTypeExtension.create(
                    (windowId, inv, data) -> {
                        String filter = data != null ? data.readUtf(256) : "";
                        return new AdminPanelMenu(windowId, inv, filter);
                    }));

    public static final Supplier<MenuType<PlayerDetailMenu>> PLAYER_DETAIL =
            MENUS.register("player_detail", () -> IMenuTypeExtension.create(
                    (windowId, inv, data) -> {
                        if (data == null) return new PlayerDetailMenu(windowId, inv);
                        String targetName = data.readUtf(64);
                        long uuidMost = data.readLong();
                        long uuidLeast = data.readLong();
                        boolean online = data.readBoolean();
                        return new PlayerDetailMenu(windowId, inv,
                                new java.util.UUID(uuidMost, uuidLeast), targetName, online);
                    }));

    public static final Supplier<MenuType<WarnListMenu>> WARN_LIST =
            MENUS.register("warn_list", () -> IMenuTypeExtension.create(
                    (windowId, inv, data) -> {
                        if (data == null) return new WarnListMenu(windowId, inv);
                        String targetName = data.readUtf(64);
                        long uuidMost = data.readLong();
                        long uuidLeast = data.readLong();
                        return new WarnListMenu(windowId, inv,
                                new java.util.UUID(uuidMost, uuidLeast), targetName);
                    }));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    private ModMenuTypes() {}
}
