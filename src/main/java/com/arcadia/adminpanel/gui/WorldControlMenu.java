package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.WorldControl;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

/**
 * Time, weather, difficulty and the common game rules, as buttons.
 *
 * <p>Time and weather apply to the dimension the staff member is standing in, which is what people
 * mean when they ask for day: the game stores both per level, and a button that silently changed the
 * Nether instead would be a bug report. Difficulty and game rules are server-wide and labelled as
 * such.</p>
 *
 * @author vyrriox
 */
public class WorldControlMenu extends ChestMenu {

    private static final int SLOT_DIM = 4;
    private static final int TIME_ROW = 10;
    private static final int WEATHER_ROW = 19;
    private static final int SLOT_DIFFICULTY = 25;
    private static final int RULES_ROW = 28;
    private static final int SLOT_BACK = 49;

    private final ServerPlayer admin;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WorldControlMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("world.title", admin))));
    }

    public WorldControlMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        build();
    }

    // -- Rendering -----------------------------------------------------------

    private void build() {
        if (admin == null) return;
        MinecraftServer server = admin.getServer();
        if (server == null || !(admin.level() instanceof ServerLevel level)) return;

        ItemStack filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE)
                .name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        long tod = WorldControl.timeOfDay(level);
        this.getContainer().setItem(SLOT_DIM, ItemBuilder.of(Items.END_PORTAL_FRAME)
                .name(Component.literal("§6§l" + level.dimension().location().getPath()))
                .addLore(Component.literal("§7" + t("world.current_time") + " §f" + tod))
                .addLore(Component.literal("§7" + t("world.current_weather") + " §f"
                        + t("world.weather." + WorldControl.currentWeather(level).name().toLowerCase())))
                .addLore(Component.literal("§8" + t("world.scope_dimension")))
                .build());

        int slot = TIME_ROW;
        for (WorldControl.TimePreset preset : WorldControl.TimePreset.values()) {
            boolean active = Math.abs(tod - preset.ticks) < 1000;
            this.getContainer().setItem(slot++, ItemBuilder
                    .of(active ? Items.CLOCK : Items.SUNFLOWER)
                    .name(Component.literal((active ? "§a" : "§e")
                            + t("world.time." + preset.name().toLowerCase())))
                    .addLore(Component.literal("§8" + preset.ticks))
                    .build());
        }

        slot = WEATHER_ROW;
        for (WorldControl.WeatherPreset preset : WorldControl.WeatherPreset.values()) {
            boolean active = WorldControl.currentWeather(level) == preset;
            this.getContainer().setItem(slot++, ItemBuilder
                    .of(switch (preset) {
                        case CLEAR -> Items.SUNFLOWER;
                        case RAIN -> Items.WATER_BUCKET;
                        case THUNDER -> Items.LIGHTNING_ROD;
                    })
                    .name(Component.literal((active ? "§a" : "§b")
                            + t("world.weather." + preset.name().toLowerCase())))
                    .build());
        }

        Difficulty difficulty = WorldControl.difficulty(server);
        this.getContainer().setItem(SLOT_DIFFICULTY, ItemBuilder.of(Items.IRON_SWORD)
                .name(Component.literal("§c" + t("world.difficulty") + " §f"
                        + t("world.difficulty." + difficulty.getKey())))
                .addLore(Component.literal("§7" + t("world.difficulty.hint")))
                .addLore(Component.literal("§8" + t("world.scope_server")))
                .build());

        slot = RULES_ROW;
        for (WorldControl.RuleToggle toggle : WorldControl.TOGGLES) {
            if (slot >= 45) break;
            boolean on = WorldControl.ruleValue(server, toggle);
            this.getContainer().setItem(slot++, ItemBuilder.of(toggle.icon())
                    .name(Component.literal((on ? "§a" : "§7") + toggle.id()))
                    .addLore(Component.literal("§7" + t(on ? "misc.on" : "misc.off")))
                    .addLore(Component.literal("§8" + t("world.scope_server")))
                    .build());
        }

        this.getContainer().setItem(SLOT_BACK, ItemBuilder.of(Items.BARRIER)
                .name(Component.literal("§c" + t("action.back"))).build());
        this.broadcastChanges();
    }

    private String t(String key) {
        return LanguageHelper.getText(key, admin);
    }

    // -- Clicks --------------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;

        if (slotId == SLOT_BACK) {
            sp.closeContainer();
            StaffToolsMenu.open(sp);
            return;
        }
        if (!AdminPermissions.WORLD.check(sp)) return;

        MinecraftServer server = sp.getServer();
        if (server == null || !(sp.level() instanceof ServerLevel level)) return;

        if (slotId >= TIME_ROW && slotId < TIME_ROW + WorldControl.TimePreset.values().length) {
            WorldControl.setTime(sp, level, WorldControl.TimePreset.values()[slotId - TIME_ROW]);
            SoundHelper.success(sp);
            build();
            return;
        }
        if (slotId >= WEATHER_ROW && slotId < WEATHER_ROW + WorldControl.WeatherPreset.values().length) {
            WorldControl.setWeather(sp, level, WorldControl.WeatherPreset.values()[slotId - WEATHER_ROW]);
            SoundHelper.success(sp);
            build();
            return;
        }
        if (slotId == SLOT_DIFFICULTY) {
            WorldControl.cycleDifficulty(sp, server);
            SoundHelper.success(sp);
            build();
            return;
        }
        if (slotId >= RULES_ROW && slotId < RULES_ROW + WorldControl.TOGGLES.size()) {
            WorldControl.toggleRule(sp, server, WorldControl.TOGGLES.get(slotId - RULES_ROW));
            SoundHelper.playAt(sp, SoundHelper.CLICK);
            build();
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
