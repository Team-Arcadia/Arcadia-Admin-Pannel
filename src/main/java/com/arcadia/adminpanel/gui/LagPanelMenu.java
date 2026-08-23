package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.BackManager;
import com.arcadia.adminpanel.util.LagMonitor;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * The performance sheet: tick time, memory, dimensions, hot chunks and the players nearest to them.
 *
 * <p>Laid out as one screen rather than a paged list because the point is to see the whole picture
 * at a glance during an incident. The sample behind it is computed when this menu opens and reused
 * for a few seconds, so a refresh spam does not become the lag it is meant to diagnose.</p>
 *
 * <p>Clicking a hot chunk teleports there, pushing the current position first so the way back is one
 * click. Clicking a player row opens their sheet.</p>
 *
 * @author vyrriox
 */
public class LagPanelMenu extends ChestMenu {

    private static final int SLOT_SUMMARY = 4;
    private static final int DIM_ROW = 9;
    private static final int CHUNK_ROW = 27;
    private static final int PLAYER_ROW = 36;
    private static final int SLOT_REFRESH = 47;
    private static final int SLOT_BACK = 49;

    private final ServerPlayer admin;
    private LagMonitor.Sample sample;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new LagPanelMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("perf.title", admin))));
    }

    public LagPanelMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        build();
    }

    // -- Rendering -----------------------------------------------------------

    private void build() {
        MinecraftServer server = admin == null ? null : admin.getServer();
        if (server == null) return;
        sample = LagMonitor.sample(server);

        ItemStack filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE)
                .name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        this.getContainer().setItem(SLOT_SUMMARY, ItemBuilder.of(Items.CLOCK)
                .name(Component.literal("§6§l" + t("perf.summary")))
                .addLore(Component.literal("§7" + t("tools.status.tps") + " "
                        + LagMonitor.tpsColor(sample.tps()) + String.format("%.2f", sample.tps())))
                .addLore(Component.literal("§7" + t("perf.mspt_mean") + " §f"
                        + String.format("%.2f", sample.msptMean()) + " ms"))
                .addLore(Component.literal("§7" + t("perf.mspt_peak") + " §f"
                        + String.format("%.2f", sample.msptPeak()) + " ms"))
                .addLore(Component.literal("§7" + t("tools.status.memory") + " §f"
                        + sample.usedMemoryMb() + " / " + sample.maxMemoryMb() + " MB"))
                .addLore(Component.literal("§7" + t("perf.entities_total") + " §f" + sample.totalEntities()))
                .addLore(Component.literal("§7" + t("perf.chunks_total") + " §f" + sample.totalChunks()))
                .addLore(Component.literal("§8" + t("perf.sampled") + " "
                        + (System.currentTimeMillis() - sample.takenAt()) / 1000 + "s"))
                .build());

        int slot = DIM_ROW;
        for (LagMonitor.DimensionStat dim : sample.dimensions()) {
            if (slot >= DIM_ROW + 9) break;
            this.getContainer().setItem(slot++, ItemBuilder.of(Items.END_PORTAL_FRAME)
                    .name(Component.literal("§b" + shortDim(dim.id())))
                    .addLore(Component.literal("§7" + t("perf.entities") + " §f" + dim.entities()))
                    .addLore(Component.literal("§7" + t("perf.chunks") + " §f" + dim.chunks()))
                    .addLore(Component.literal("§7" + t("perf.forced") + " §f" + dim.forcedChunks()))
                    .build());
        }

        this.getContainer().setItem(18, ItemBuilder.of(Items.MAGMA_BLOCK)
                .name(Component.literal("§c§l" + t("perf.hot_chunks")))
                .addLore(Component.literal("§7" + t("perf.hot_chunks.hint")))
                .build());

        slot = CHUNK_ROW;
        for (LagMonitor.HotChunk hot : sample.hotChunks()) {
            if (slot >= CHUNK_ROW + 9) break;
            ItemBuilder b = ItemBuilder.of(Items.MAGMA_BLOCK)
                    .name(Component.literal("§c" + hot.entities() + " " + t("perf.entities")))
                    .addLore(Component.literal("§7" + shortDim(hot.dimension())))
                    .addLore(Component.literal("§8" + t("perf.chunk") + " "
                            + hot.chunkX() + ", " + hot.chunkZ()
                            + " §7(" + (hot.chunkX() * 16) + ", " + (hot.chunkZ() * 16) + ")"));
            if (hot.nearestPlayer() != null) {
                b.addLore(Component.literal("§7" + t("perf.nearest") + " §e" + hot.nearestPlayer()));
            }
            if (AdminPermissions.TELEPORT.check(admin)) {
                b.addLore(Component.literal("§a" + t("misc.click_tp")));
            }
            this.getContainer().setItem(slot++, b.build());
        }

        slot = PLAYER_ROW;
        for (LagMonitor.PlayerLoad load : sample.playerLoads()) {
            if (slot >= PLAYER_ROW + 9) break;
            this.getContainer().setItem(slot++, ItemBuilder
                    .of(SkullCache.createSkull(load.uuid(), load.name()))
                    .name(Component.literal((load.afk() ? "§7" : "§a") + load.name()))
                    .addLore(Component.literal("§7" + t("perf.nearby") + " §f" + load.nearbyEntities()))
                    .addLore(Component.literal("§8" + shortDim(load.dimension())))
                    .addLore(Component.literal(load.afk() ? "§8" + t("perf.afk") : "§8"))
                    .build());
        }

        this.getContainer().setItem(SLOT_REFRESH, ItemBuilder.of(Items.REPEATER)
                .name(Component.literal("§e" + t("perf.refresh"))).build());
        this.getContainer().setItem(SLOT_BACK, ItemBuilder.of(Items.BARRIER)
                .name(Component.literal("§c" + t("action.back"))).build());

        this.broadcastChanges();
    }

    private String t(String key) {
        return LanguageHelper.getText(key, admin);
    }

    private static String shortDim(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
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
        if (slotId == SLOT_REFRESH) {
            LagMonitor.invalidate();
            build();
            SoundHelper.playAt(sp, SoundHelper.CLICK);
            return;
        }
        if (sample == null) return;

        if (slotId >= CHUNK_ROW && slotId < CHUNK_ROW + 9) {
            int index = slotId - CHUNK_ROW;
            if (index >= sample.hotChunks().size()) return;
            if (!AdminPermissions.TELEPORT.check(sp)) return;
            LagMonitor.HotChunk hot = sample.hotChunks().get(index);
            MinecraftServer server = sp.getServer();
            if (server == null) return;
            ServerLevel level = LagMonitor.levelOf(server, hot.dimension());
            if (level == null) return;

            BackManager.push(sp);
            sp.closeContainer();
            double x = hot.chunkX() * 16 + 8;
            double z = hot.chunkZ() * 16 + 8;
            // Land above the terrain rather than inside it: the target is a diagnostic position, not
            // a place the admin picked.
            double y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    (int) x, (int) z) + 2;
            sp.teleportTo(level, x, y, z, sp.getYRot(), sp.getXRot());
            SoundHelper.playAt(sp, SoundHelper.TELEPORT);
            AuditManager.recordServer(sp, AdminAction.TELEPORT,
                    "hot chunk " + hot.chunkX() + "," + hot.chunkZ() + " @" + hot.dimension());
            return;
        }

        if (slotId >= PLAYER_ROW && slotId < PLAYER_ROW + 9) {
            int index = slotId - PLAYER_ROW;
            if (index >= sample.playerLoads().size()) return;
            LagMonitor.PlayerLoad load = sample.playerLoads().get(index);
            sp.closeContainer();
            PlayerDetailMenu.open(sp, load.uuid(), load.name(), true);
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
