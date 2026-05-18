package com.arcadia.adminpanel.item;

import com.arcadia.adminpanel.AdminPanelMod;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The Jail Baton — a "matraque" that jails another player on right-click.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Right-click another player → jail them for the default duration (30 minutes), with the
 *       wielder recorded as the jailer. Re-clicking an already-jailed player releases them.</li>
 *   <li>Wielder must have the {@code arcadia.adminpanel.jail} perm — checked on every interaction,
 *       same gate as the GUI button. Players without the perm get a silent no-op.</li>
 *   <li>Self-target is rejected (can't whack yourself with the baton).</li>
 *   <li>Other staff cannot be jailed by the baton — protects against staff infighting / pranks.
 *       Use the GUI explicitly if you really want to jail a staffer.</li>
 * </ul>
 *
 * <p>The item is decorative steampunk: wooden body, three copper bands, leather grip, metal tip.
 * Renders with the vanilla {@code handheld} model so it shows the diagonal in-hand pose like a
 * tool. Stack size 1 — it's a staff tool, not loot.</p>
 *
 * @author vyrriox
 */
public class JailBatonItem extends Item {

    /** Default jail duration applied by a baton hit. 30 minutes mirrors the GUI's quick-jail. */
    private static final long DEFAULT_JAIL_MS = 30L * 60_000L;

    public JailBatonItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult interactLivingEntity(@NotNull ItemStack stack,
                                                           @NotNull Player wielder,
                                                           @NotNull LivingEntity target,
                                                           @NotNull InteractionHand hand) {
        // Server-only logic. Client just returns SUCCESS for swing animation.
        if (wielder.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(target instanceof net.minecraft.server.level.ServerPlayer victim)) return InteractionResult.PASS;
        if (!(wielder instanceof net.minecraft.server.level.ServerPlayer staff)) return InteractionResult.PASS;

        // Permission gate — same node as the GUI jail button.
        if (!AdminPermissions.JAIL.check(staff)) {
            staff.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("baton.no_perm", staff)));
            return InteractionResult.FAIL;
        }

        // Don't allow self-jail through the baton (an admin auto-jail would lock the baton inside
        // the cell, awkward). The slash command still lets you do it if you really want to.
        if (victim.getUUID().equals(staff.getUUID())) {
            staff.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("baton.no_self", staff)));
            return InteractionResult.FAIL;
        }

        // Don't whack other staff. Catches the "two admins fight" griefing path.
        if (AdminPanelMod.canOpenAdminPanel(victim)) {
            staff.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("baton.no_staff", staff)));
            return InteractionResult.FAIL;
        }

        JailManager jm = JailManager.getInstance();
        if (!jm.hasJailLocation()) {
            staff.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("jail.no_location", staff)));
            return InteractionResult.FAIL;
        }

        if (jm.isJailed(victim.getUUID())) {
            // Already jailed — release them (parity with the GUI toggle button).
            jm.unjail(victim.getUUID(), staff.getServer());
            staff.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("jail.unjail.success", staff)
                            .replace("%player%", victim.getName().getString())));
            victim.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("jail.released", victim)));
            SoundHelper.playAt(staff, SoundHelper.SUCCESS, 0.5f, 1.2f);
        } else {
            String reason = LanguageHelper.getText("baton.reason", staff);
            jm.jail(victim, reason, staff.getName().getString(), DEFAULT_JAIL_MS, staff.getServer());
            victim.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("jail.notify", victim)
                            .replace("%time%", TextFormatter.formatMs(DEFAULT_JAIL_MS))
                            .replace("%reason%", reason)));
            staff.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("jail.success", staff)
                            .replace("%player%", victim.getName().getString())
                            .replace("%time%", "30m")));
            SoundHelper.playAt(staff, SoundHelper.CLICK);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @NotNull TooltipContext ctx,
                                @NotNull List<Component> tooltip,
                                @NotNull TooltipFlag flag) {
        tooltip.add(Component.translatable("arcadiaadminpanel.baton.tooltip.line1")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("arcadiaadminpanel.baton.tooltip.line2")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(@NotNull ItemStack stack) {
        return true;
    }

    /** Right-click in air does nothing — must hit a player. */
    @Override
    public @NotNull net.minecraft.world.InteractionResultHolder<ItemStack> use(
            @NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
    }
}
