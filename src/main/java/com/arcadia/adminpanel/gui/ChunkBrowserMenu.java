package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.BackManager;
import com.arcadia.adminpanel.util.ChunkReport;
import com.arcadia.adminpanel.util.LagMonitor;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Who is keeping chunks loaded.
 *
 * <p>Two views, toggled from the control strip. The team view lists FTB Chunks footprints ranked by
 * force-loaded count, which is the number that actually costs tick time. The vanilla view lists
 * genuinely forced chunks, which usually means a command or a mod rather than a player, and which is
 * the first thing to check when the server is busy with nobody online.</p>
 *
 * @author vyrriox
 */
public class ChunkBrowserMenu extends PagedMenu {

    private static final int SLOT_VIEW = 47;
    private static final int SLOT_SUMMARY = 51;

    private boolean teamView = true;
    private List<ChunkReport.TeamFootprint> teams = List.of();
    private List<ChunkReport.ForcedChunk> forced = List.of();

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ChunkBrowserMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("chunks.title", admin))));
    }

    public ChunkBrowserMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        MinecraftServer server = admin.getServer();
        if (server == null) return;
        teams = ChunkReport.teamFootprints(server);
        forced = ChunkReport.forcedChunks(server);
        // FTB Chunks data may be absent entirely; fall back to the view that has something in it.
        if (teams.isEmpty() && !forced.isEmpty()) teamView = false;
    }

    @Override
    protected int contentSize() {
        return teamView ? teams.size() : forced.size();
    }

    @Override
    protected String emptyMessageKey() {
        return teamView ? "chunks.no_teams" : "chunks.no_forced";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        if (teamView) {
            ChunkReport.TeamFootprint f = teams.get(index);
            this.getContainer().setItem(slot, ItemBuilder.of(Items.WHITE_BANNER)
                    .name(Component.literal("§b" + f.teamName()))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("chunks.claims", admin)
                            + " §f" + f.claims()))
                    .addLore(Component.literal("§c" + LanguageHelper.getText("chunks.forced", admin)
                            + " §f" + f.forceLoaded()))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("chunks.members", admin)
                            + " §f" + f.members()))
                    .addLore(Component.literal("§8#" + (index + 1)))
                    .build());
            return;
        }

        ChunkReport.ForcedChunk c = forced.get(index);
        ItemBuilder b = ItemBuilder.of(Items.BEDROCK)
                .name(Component.literal("§c" + c.x() + ", " + c.z()))
                .addLore(Component.literal("§7" + shortDim(c.dimension())))
                .addLore(Component.literal("§8" + LanguageHelper.getText("chunks.block_pos", admin)
                        + " " + (c.x() * 16) + ", " + (c.z() * 16)));
        if (AdminPermissions.TELEPORT.check(admin)) {
            b.addLore(Component.literal("§a" + LanguageHelper.getText("misc.click_tp", admin)));
        }
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (teamView) {
            ChunkReport.TeamFootprint f = teams.get(index);
            if (!AdminPermissions.TEAMS.check(admin)) return;
            admin.closeContainer();
            TeamDetailMenu.open(admin, f.teamId());
            return;
        }

        if (!AdminPermissions.TELEPORT.check(admin)) return;
        MinecraftServer server = admin.getServer();
        if (server == null) return;
        ChunkReport.ForcedChunk c = forced.get(index);
        ServerLevel level = LagMonitor.levelOf(server, c.dimension());
        if (level == null) return;

        BackManager.push(admin);
        admin.closeContainer();
        double x = c.x() * 16 + 8;
        double z = c.z() * 16 + 8;
        double y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                (int) x, (int) z) + 2;
        admin.teleportTo(level, x, y, z, admin.getYRot(), admin.getXRot());
        SoundHelper.playAt(admin, SoundHelper.TELEPORT);
        AuditManager.recordServer(admin, AdminAction.TELEPORT,
                "forced chunk " + c.x() + "," + c.z() + " @" + c.dimension());
    }

    @Override
    protected void renderExtraControls() {
        MinecraftServer server = admin.getServer();
        this.getContainer().setItem(SLOT_VIEW, ItemBuilder.of(Items.HOPPER)
                .name(Component.literal("§e" + LanguageHelper.getText("chunks.view", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText(
                        teamView ? "chunks.view.teams" : "chunks.view.forced", admin)))
                .build());

        if (server != null) {
            this.getContainer().setItem(SLOT_SUMMARY, ItemBuilder.of(Items.FILLED_MAP)
                    .name(Component.literal("§6" + LanguageHelper.getText("chunks.summary", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("chunks.claims", admin)
                            + " §f" + ChunkReport.totalClaims(server)))
                    .addLore(Component.literal("§c" + LanguageHelper.getText("chunks.forced", admin)
                            + " §f" + ChunkReport.totalForceLoaded(server)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("chunks.vanilla_forced", admin)
                            + " §f" + forced.size()))
                    .build());
        }
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (slot != SLOT_VIEW) return;
        teamView = !teamView;
        page = 0;
        rebuild();
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }

    private static String shortDim(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
