package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SanctionTemplates;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Applies a pre-written sanction to a player, at the rung they have earned.
 *
 * <p>Each tile shows the offence, what the next step would be for this specific player, and how many
 * times they have already been sanctioned under it. The moderator picks the offence; the ladder picks
 * the severity. That is the whole point: two moderators handling the same behaviour a week apart give
 * the same answer.</p>
 *
 * <p>Clicking asks for a confirmation, because the click applies a real sanction immediately rather
 * than opening another screen.</p>
 *
 * @author vyrriox
 */
public class TemplatesMenu extends PagedMenu {

    private final UUID target;
    private final String targetName;
    private final List<SanctionTemplates.Template> templates;
    private int pendingIndex = -1;

    public static void open(ServerPlayer admin, UUID target, String targetName) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new TemplatesMenu(id, inv, (ServerPlayer) p, target, targetName),
                PanelTitles.of(LanguageHelper.getText("templates.title", admin) + ": " + targetName)));
    }

    public TemplatesMenu(int id, Inventory playerInv, ServerPlayer admin, UUID target, String targetName) {
        super(id, playerInv, admin);
        this.target = target;
        this.targetName = targetName;
        this.templates = SanctionTemplates.all();
        rebuild();
    }

    @Override
    protected int contentSize() {
        return templates.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "templates.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        SanctionTemplates.Template template = templates.get(index);
        SanctionTemplates.Rung next = SanctionTemplates.nextRung(target, template);
        int step = SanctionTemplates.stepFor(target, template.id);
        boolean confirming = index == pendingIndex;

        ItemBuilder b = ItemBuilder.of(template.iconItem())
                .name(Component.literal("§e" + template.label(admin)))
                .addLore(Component.literal("§7" + template.reason(admin)))
                .addLore(Component.literal("§8"))
                .addLore(Component.literal("§7" + LanguageHelper.getText("templates.next", admin)
                        + " §c" + LanguageHelper.getText(next.resolve().labelKey(), admin)
                        + (next.minutes > 0 ? " §f" + TextFormatter.formatMs(next.minutes * 60_000L) : "")))
                .addLore(Component.literal("§7" + LanguageHelper.getText("templates.step", admin)
                        + " §f" + (step + 1) + " / " + Math.max(1, template.ladder.size())));

        if (!AdminConfig.get().escalationEnabled) {
            b.addLore(Component.literal("§8" + LanguageHelper.getText("templates.no_escalation", admin)));
        }
        b.addLore(Component.literal(confirming
                ? "§c" + LanguageHelper.getText("misc.confirm", admin)
                : "§a" + LanguageHelper.getText("templates.click", admin)));

        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (!AdminPermissions.TEMPLATES.check(admin)) return;
        MinecraftServer server = admin.getServer();
        if (server == null) return;

        if (pendingIndex != index) {
            pendingIndex = index;
            rebuild();
            return;
        }
        pendingIndex = -1;

        SanctionTemplates.Template template = templates.get(index);
        SanctionTemplates.Rung applied = SanctionTemplates.apply(admin, server, target, targetName, template);
        if (applied == null) {
            admin.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("templates.failed", admin)
                            .replace("%player%", targetName)));
            SoundHelper.error(admin);
            return;
        }

        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("templates.applied", admin)
                        .replace("%player%", targetName)
                        .replace("%action%", LanguageHelper.getText(applied.resolve().labelKey(), admin))));
        SoundHelper.success(admin);
        rebuild();
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(47, ItemBuilder.of(Items.PAPER)
                .name(Component.literal("§8" + LanguageHelper.getText("templates.info.title", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("templates.info.line1", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("templates.info.line2", admin)))
                .build());
    }

    @Override
    protected void goBack() {
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(target) != null;
        PlayerDetailMenu.open(admin, target, targetName, online);
    }
}
