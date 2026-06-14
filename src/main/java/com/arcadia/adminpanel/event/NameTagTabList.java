package com.arcadia.adminpanel.event;

import com.arcadia.adminpanel.util.NameTagEffect;
import com.arcadia.adminpanel.util.NameTagManager;
import com.arcadia.adminpanel.util.NameTagStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Server half of the name-tag system for the <b>tab list</b> (the player list opened with TAB).
 *
 * <p>Where {@code NameTagRenderer} restyles the floating head-tag client-side every frame, the tab
 * list is built server-side: vanilla fires {@link PlayerEvent.TabListNameFormat} when assembling the
 * {@code ClientboundPlayerInfoUpdatePacket}, asking listeners for the display name. We answer with
 * the same composition the floating tag uses — {@code grade-prefix + custom/real name + grade-suffix}
 * — so a custom pseudo and its colours show up in the tab list too, with the grade preserved.</p>
 *
 * <p>The tab list is a static snapshot (no per-frame clock), so animated effects are rendered at a
 * fixed tick: colours/gradients appear, motion is frozen. To push a change live,
 * {@code NameTagManager.refreshTabName} re-sends the display-name packet after a mutation.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel")
public final class NameTagTabList {

    private NameTagTabList() {}

    @SubscribeEvent
    public static void onTabListName(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        NameTagStyle style = NameTagManager.getInstance().getStyle(sp.getUUID());
        if (style == null || style.isNoOp()) return; // leave the vanilla tab name untouched

        event.setDisplayName(buildTabName(sp, style));
    }

    /** Composes the tab display name: grade prefix + styled (custom-or-real) name + grade suffix. */
    public static Component buildTabName(ServerPlayer sp, NameTagStyle style) {
        String base = style.hasCustomName() ? style.name() : sp.getName().getString();
        // Static tick (0): tab entries aren't re-pushed each frame, so animation can't run here.
        Component styledName = NameTagEffect.render(base, style, 0f, 0f);

        if (!style.showGrade()) return styledName;

        PlayerTeam team = sp.getTeam() instanceof PlayerTeam pt ? pt : null;
        if (team == null) return styledName;
        Component prefix = team.getPlayerPrefix();
        Component suffix = team.getPlayerSuffix();
        boolean hasPrefix = prefix != null && !prefix.getString().isEmpty();
        boolean hasSuffix = suffix != null && !suffix.getString().isEmpty();
        if (!hasPrefix && !hasSuffix) return styledName;

        MutableComponent out = Component.empty();
        if (hasPrefix) out.append(prefix);
        out.append(styledName);
        if (hasSuffix) out.append(suffix);
        return out;
    }
}
