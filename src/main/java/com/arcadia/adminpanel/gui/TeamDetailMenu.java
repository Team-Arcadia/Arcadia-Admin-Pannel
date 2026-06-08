package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.FTBChunksReader;
import com.arcadia.adminpanel.util.FTBDataReader;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shows the members of a single FTB Team plus quick actions (TP to a member's last-known position).
 *
 * @author vyrriox
 */
public class TeamDetailMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final UUID teamId;
    private int page = 0;
    private static final int MEMBERS_PER_PAGE = 45;

    public static void open(ServerPlayer admin, UUID teamId) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new TeamDetailMenu(id, inv, (ServerPlayer) p, teamId),
                Component.literal(LanguageHelper.getText("team.detail.title", admin))
        ));
    }

    public TeamDetailMenu(int id, Inventory playerInv, ServerPlayer admin, UUID teamId) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.teamId = teamId;
        buildMenu();
    }

    public TeamDetailMenu(int id, Inventory playerInv) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.teamId = new UUID(0, 0);
    }

    private FTBTeamsReader.Team resolveTeam() {
        for (var t : FTBTeamsReader.getParties())     if (t.id.equals(teamId)) return t;
        for (var t : FTBTeamsReader.getServerTeams()) if (t.id.equals(teamId)) return t;
        for (var t : FTBTeamsReader.getPlayerTeams()) if (t.id.equals(teamId)) return t;
        return null;
    }

    /** Rank-then-name sort using a precomputed name map (no resolveName calls inside compare). */
    private List<FTBTeamsReader.Member> sortedMembers(FTBTeamsReader.Team team, Map<UUID, String> names) {
        List<FTBTeamsReader.Member> members = new ArrayList<>(team.members);
        members.sort((a, b) -> {
            int ra = a.rank().ordinal();
            int rb = b.rank().ordinal();
            if (ra != rb) return Integer.compare(ra, rb);
            return names.get(a.uuid()).compareToIgnoreCase(names.get(b.uuid()));
        });
        return members;
    }

    private void buildMenu() {
        if (admin == null) return;
        var filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        FTBTeamsReader.Team team = resolveTeam();
        if (team == null) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("team.not_found", admin))).build());
            this.getContainer().setItem(49, backButton());
            return;
        }

        // Member skulls — sorted by rank (owner first), then name. Names are precomputed ONCE (out
        // of the comparator) so we don't do O(n log n) profile-cache lookups, and we no longer read
        // each member's FTB data file from disk on the server thread per redraw — last-seen is
        // fetched lazily on right-click instead.
        // Visibility gate (layer 1): only render member skulls for viewers holding the TEAMS node —
        // matches the gate that exposes the team browser in the first place.
        boolean canSeeMembers = AdminPermissions.TEAMS.check(admin);
        Map<UUID, String> memberNames = new HashMap<>();
        for (var mm : team.members) memberNames.computeIfAbsent(mm.uuid(), this::resolveName);

        List<FTBTeamsReader.Member> members = sortedMembers(team, memberNames);

        int start = page * MEMBERS_PER_PAGE;
        int end = Math.min(start + MEMBERS_PER_PAGE, members.size());
        for (int i = canSeeMembers ? start : end; i < end; i++) {
            FTBTeamsReader.Member m = members.get(i);
            String name = memberNames.get(m.uuid());
            var skull = SkullCache.createSkull(m.uuid(), name);
            SkullCache.warmTextures(admin.getServer(), m.uuid());
            skull.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(rankColor(m.rank()) + name));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7" + LanguageHelper.getText("team.rank", admin)
                    + " " + rankColor(m.rank()) + m.rank().name().toLowerCase()));
            lore.add(Component.literal("§8" + m.uuid()));
            lore.add(Component.literal("§e" + LanguageHelper.getText("team.member.actions", admin)));
            skull.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(lore));

            this.getContainer().setItem(i - start, skull);
        }

        // Header — team banner (slot 49 placeholder is back). Show team meta in slot 45 area.
        if (page > 0) {
            this.getContainer().setItem(45, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e<< " + LanguageHelper.getText("nav.previous", admin))).build());
        }
        if (end < members.size()) {
            this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e" + LanguageHelper.getText("nav.next", admin) + " >>")).build());
        }

        var headerBuilder = ItemBuilder.of(
                team.type == FTBTeamsReader.TeamType.SERVER ? Items.PURPLE_BANNER : Items.WHITE_BANNER)
                .name(Component.literal("§b" + team.displayName))
                .addLore(Component.literal("§7" + LanguageHelper.getText("team.type", admin)
                        + " §f" + team.type.name().toLowerCase()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("team.members", admin)
                        + " §e" + team.memberCount()));
        if (FTBChunksReader.isAvailable()) {
            FTBChunksReader.ClaimStats st = FTBChunksReader.getStatsFor(team.id);
            if (st != null) {
                headerBuilder.addLore(Component.literal("§7" + LanguageHelper.getText("team.claims", admin)
                        + " §e" + st.totalClaims()
                        + (st.maxClaims() > 0 ? " §8/ §7" + st.maxClaims() : "")));
                headerBuilder.addLore(Component.literal("§7" + LanguageHelper.getText("team.force_loaded", admin)
                        + " §e" + st.forceLoaded()
                        + (st.maxForceLoaded() > 0 ? " §8/ §7" + st.maxForceLoaded() : "")));
            }
        }
        this.getContainer().setItem(47, headerBuilder.build());

        this.getContainer().setItem(49, backButton());
    }

    private String rankColor(FTBTeamsReader.Rank r) {
        return switch (r) {
            case OWNER -> "§6";
            case OFFICER -> "§e";
            case MEMBER -> "§a";
            case ALLY -> "§b";
            case INVITED -> "§7";
            case ENEMY -> "§c";
            case NONE -> "§8";
        };
    }

    private String resolveName(UUID uuid) {
        if (admin == null) return uuid.toString().substring(0, 8);
        ServerPlayer online = admin.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) return online.getName().getString();
        var cached = OfflinePlayerManager.getInstance().getCache().get(uuid);
        if (cached != null) return cached.name();
        Optional<com.mojang.authlib.GameProfile> profile = admin.getServer().getProfileCache().get(uuid);
        return profile.map(com.mojang.authlib.GameProfile::getName).orElse(uuid.toString().substring(0, 8));
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
            TeamListMenu.open(sp);
            return;
        }
        if (slotId == 45 && page > 0) { page--; buildMenu(); return; }
        if (slotId == 53) { page++; buildMenu(); return; }

        // Member click — left-click: open player detail, right-click: TP to last-seen.
        if (slotId >= 0 && slotId < MEMBERS_PER_PAGE) {
            // Action gate (layer 2): seeing/acting on members requires the TEAMS node. A forged
            // slot-click packet can't reach the member roster without it.
            if (!AdminPermissions.TEAMS.check(sp)) return;
            FTBTeamsReader.Team team = resolveTeam();
            if (team == null) return;
            Map<UUID, String> names = new HashMap<>();
            for (var mm : team.members) names.computeIfAbsent(mm.uuid(), this::resolveName);
            List<FTBTeamsReader.Member> members = sortedMembers(team, names);
            int index = page * MEMBERS_PER_PAGE + slotId;
            if (index >= members.size()) return;
            FTBTeamsReader.Member m = members.get(index);

            if (button == 1) {
                // Right-click — TP to member's last-known position. Re-check TELEPORT (layer 2):
                // a forged right-click must not teleport a viewer who lacks the teleport node.
                if (!AdminPermissions.TELEPORT.check(sp)) return;
                FTBDataReader.PlayerFTBData fd = FTBDataReader.readPlayerData(m.uuid());
                if (fd != null && fd.lastSeen != null) {
                    teleport(sp, fd.lastSeen.dimension, fd.lastSeen.x, fd.lastSeen.y, fd.lastSeen.z);
                    sp.closeContainer();
                }
            } else {
                // Left-click — open the player detail panel.
                String name = resolveName(m.uuid());
                boolean online = sp.getServer().getPlayerList().getPlayer(m.uuid()) != null;
                sp.closeContainer();
                PlayerDetailMenu.open(sp, m.uuid(), name, online);
            }
        }
    }

    private void teleport(ServerPlayer sp, String dim, double x, double y, double z) {
        ServerLevel level = null;
        for (ServerLevel w : sp.getServer().getAllLevels()) {
            if (w.dimension().location().toString().equals(dim)) { level = w; break; }
        }
        if (level == null) level = sp.serverLevel();
        sp.teleportTo(level, x, y, z, sp.getYRot(), sp.getXRot());
        sp.sendSystemMessage(ArcadiaMessages.success(
                String.format(LanguageHelper.getText("tp.success", sp), x, y, z)));
        SoundHelper.playAt(sp, SoundHelper.TELEPORT);
    }

    @Override
    public @NotNull net.minecraft.world.item.ItemStack quickMoveStack(@NotNull Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
