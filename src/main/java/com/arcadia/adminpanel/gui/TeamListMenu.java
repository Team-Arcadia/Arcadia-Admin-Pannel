package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.adminpanel.util.FTBChunksReader;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists all FTB Teams (parties + server teams) — entry point for the team browser.
 * Player teams are not shown here because they're 1:1 with players and the player list
 * already gives access to them.
 *
 * @author vyrriox
 */
public class TeamListMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final String filter;
    private int page = 0;
    private static final int ITEMS_PER_PAGE = 45;

    public static void open(ServerPlayer admin) { open(admin, ""); }

    public static void open(ServerPlayer admin, String filter) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new TeamListMenu(id, inv, (ServerPlayer) p, filter),
                Component.literal(LanguageHelper.getText("team.list.title", admin))
        ));
    }

    public TeamListMenu(int id, Inventory playerInv, ServerPlayer admin, String filter) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.filter = filter != null ? filter : "";
        buildMenu();
    }

    public TeamListMenu(int id, Inventory playerInv) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.filter = "";
    }

    private void buildMenu() {
        if (admin == null) return;

        var filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        if (!FTBTeamsReader.isAvailable()) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("team.unavailable", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("team.unavailable.hint", admin)))
                    .build());
            this.getContainer().setItem(49, backButton());
            return;
        }

        List<FTBTeamsReader.Team> teams = new ArrayList<>();
        teams.addAll(FTBTeamsReader.getParties());
        teams.addAll(FTBTeamsReader.getServerTeams());

        if (!filter.isEmpty()) {
            String lower = filter.toLowerCase(Locale.ROOT);
            teams.removeIf(t -> !t.displayName.toLowerCase(Locale.ROOT).contains(lower));
        }

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, teams.size());
        for (int i = start; i < end; i++) {
            FTBTeamsReader.Team team = teams.get(i);
            var banner = team.type == FTBTeamsReader.TeamType.SERVER
                    ? Items.PURPLE_BANNER : Items.WHITE_BANNER;
            var builder = ItemBuilder.of(banner)
                    .name(Component.literal((team.type == FTBTeamsReader.TeamType.SERVER ? "§5" : "§b")
                            + team.displayName))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("team.type", admin)
                            + " §f" + team.type.name().toLowerCase()))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("team.members", admin)
                            + " §e" + team.memberCount()));
            if (FTBChunksReader.isAvailable()) {
                FTBChunksReader.ClaimStats st = FTBChunksReader.getStatsFor(team.id);
                if (st != null) {
                    builder.addLore(Component.literal("§7" + LanguageHelper.getText("team.claims", admin)
                            + " §e" + st.totalClaims()
                            + (st.maxClaims() > 0 ? " §8/ §7" + st.maxClaims() : "")));
                    builder.addLore(Component.literal("§7" + LanguageHelper.getText("team.force_loaded", admin)
                            + " §e" + st.forceLoaded()
                            + (st.maxForceLoaded() > 0 ? " §8/ §7" + st.maxForceLoaded() : "")));
                }
            }
            builder.addLore(Component.literal("§8" + team.id.toString().substring(0, 8)));
            builder.addLore(Component.literal("§e" + LanguageHelper.getText("team.click.view", admin)));
            this.getContainer().setItem(i - start, builder.build());
        }

        if (teams.isEmpty()) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("team.list.empty", admin))).build());
        }

        if (page > 0) {
            this.getContainer().setItem(45, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e<< " + LanguageHelper.getText("nav.previous", admin))).build());
        }
        if (end < teams.size()) {
            this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e" + LanguageHelper.getText("nav.next", admin) + " >>")).build());
        }
        this.getContainer().setItem(49, backButton());
    }

    private net.minecraft.world.item.ItemStack backButton() {
        return ItemBuilder.of(Items.OAK_DOOR)
                .name(Component.literal("§e" + LanguageHelper.getText("action.back", admin))).build();
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;
        var clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE)) return;

        if (slotId == 49) {
            sp.closeContainer();
            AdminPanelMenu.open(sp);
            return;
        }
        if (slotId == 45 && page > 0) { page--; buildMenu(); return; }
        if (slotId == 53) { page++; buildMenu(); return; }

        if (slotId >= 0 && slotId < ITEMS_PER_PAGE) {
            List<FTBTeamsReader.Team> teams = new ArrayList<>();
            teams.addAll(FTBTeamsReader.getParties());
            teams.addAll(FTBTeamsReader.getServerTeams());
            if (!filter.isEmpty()) {
                String lower = filter.toLowerCase(Locale.ROOT);
                teams.removeIf(t -> !t.displayName.toLowerCase(Locale.ROOT).contains(lower));
            }
            int index = page * ITEMS_PER_PAGE + slotId;
            if (index < teams.size()) {
                sp.closeContainer();
                TeamDetailMenu.open(sp, teams.get(index).id);
            }
        }
    }

    @Override
    public @NotNull net.minecraft.world.item.ItemStack quickMoveStack(@NotNull Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
