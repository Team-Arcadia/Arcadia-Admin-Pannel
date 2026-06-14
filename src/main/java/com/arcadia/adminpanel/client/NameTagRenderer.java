package com.arcadia.adminpanel.client;

import com.arcadia.adminpanel.util.NameTagEffect;
import com.arcadia.adminpanel.util.NameTagStyle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

import java.util.UUID;

/**
 * The client half of the name-tag system. Two responsibilities, both purely client-side:
 *
 * <ol>
 *   <li><b>Hide behind walls</b> — on {@link RenderNameTagEvent}, raytrace from the camera to the
 *       target player's head; if a block occludes the line of sight, suppress the floating name
 *       ({@code setCanRender(FALSE)}). This is the only place wall-occlusion can live: the server
 *       cannot know each viewer's camera per frame. Enabled by the server-synced master switch.</li>
 *   <li><b>Colour &amp; effects</b> — replace the rendered name with the player's styled
 *       {@link Component} from {@link NameTagEffect#render}, animated by a client tick counter so
 *       rainbow / breathing / chase move smoothly.</li>
 * </ol>
 *
 * <p>Subscribed on the game event bus, client dist only. Does nothing for entities that aren't
 * players, for the local player's own tag, or for players with no synced style (the common case),
 * keeping the per-frame cost negligible.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", value = Dist.CLIENT)
public final class NameTagRenderer {

    /** Monotonic client-tick animation clock. Read on the render thread, written on the client tick. */
    private static volatile int animTick = 0;

    private NameTagRenderer() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        animTick++;
    }

    /** Wipe synced state on disconnect so a relog / server switch never shows stale names. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNameTagState.clear();
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Minecraft mc = Minecraft.getInstance();
        UUID uuid = player.getUUID();

        // ── 1. Occlusion: hide the name if a wall is in the way ────────────────
        if (ClientNameTagState.isHideEnabled()
                && !ClientNameTagState.isHideExempt(uuid)
                && !isSelf(mc, player)
                && isOccluded(mc, player)) {
            event.setCanRender(TriState.FALSE);
            return; // hidden — no point styling it
        }

        // ── 2. Styling: recolour / animate the name, keep the grade ────────────
        NameTagStyle style = ClientNameTagState.styleFor(uuid);
        if (style == null || style.isNoOp()) return; // vanilla name

        // The base text is the admin-set custom pseudo if any, otherwise the real player name.
        String base = style.hasCustomName() ? style.name() : player.getName().getString();
        Component styledName = NameTagEffect.render(base, style, animTick, event.getPartialTick());

        // Re-attach the grade (the scoreboard-team prefix/suffix that LuckPerms & co. set) so it
        // never disappears when we override the content. Vanilla builds the floating name from these
        // exact pieces; reading them straight off the team gives us the same prefix without having to
        // string-split the original content. Dropped entirely when the admin hid the grade.
        if (style.showGrade()) {
            event.setContent(withGrade(player, styledName));
        } else {
            event.setContent(styledName);
        }
    }

    /**
     * Wraps the styled name with the player's scoreboard-team prefix and suffix (the "grade"). If the
     * player is on no team, or the team carries no prefix/suffix, the styled name is returned as-is.
     */
    private static Component withGrade(Player player, Component styledName) {
        var team = player.getTeam();
        if (team == null) return styledName;
        Component prefix = team.getPlayerPrefix();
        Component suffix = team.getPlayerSuffix();
        boolean hasPrefix = prefix != null && !prefix.getString().isEmpty();
        boolean hasSuffix = suffix != null && !suffix.getString().isEmpty();
        if (!hasPrefix && !hasSuffix) return styledName;
        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        if (hasPrefix) out.append(prefix);
        out.append(styledName);
        if (hasSuffix) out.append(suffix);
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isSelf(Minecraft mc, Player player) {
        return mc.player != null && mc.player.getUUID().equals(player.getUUID());
    }

    /**
     * Raytrace from the active camera position to the target player's eye height. A non-MISS hit
     * means a block sits between camera and player → the name is occluded. When the config does not
     * count transparent blocks, a hit on a non-opaque block (glass, leaves, fences…) is treated as
     * "not occluding" so you still see names through windows.
     */
    private static boolean isOccluded(Minecraft mc, Player player) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return false;
        Level level = player.level();
        Vec3 from = camera.getPosition();
        Vec3 to = new Vec3(player.getX(), player.getEyeY(), player.getZ());

        // Same distance gate vanilla uses (64 blocks) — beyond it the tag isn't drawn anyway.
        if (from.distanceToSqr(to) > 4096.0) return false;

        ClipContext ctx = new ClipContext(
                from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player);
        BlockHitResult hit = level.clip(ctx);
        if (hit.getType() == HitResult.Type.MISS) return false;

        if (ClientNameTagState.occludeTransparent()) {
            return true; // any block hit occludes
        }
        // Only opaque, full-cube blocks should hide the name; see through glass/leaves/etc.
        BlockState state = level.getBlockState(hit.getBlockPos());
        return isOpaqueOccluder(state, level, hit);
    }

    /** True if the hit block is a solid render-opaque block (a "wall"), not a see-through one. */
    private static boolean isOpaqueOccluder(BlockState state, BlockGetter level, BlockHitResult hit) {
        if (state.isAir()) return false;
        // canOcclude is the vanilla "does this block block vision / light occlusion" flag — true for
        // stone/dirt/wool/etc., false for glass, leaves (with fancy graphics), slabs gaps, etc.
        return state.canOcclude() && state.isSolidRender(level, hit.getBlockPos());
    }
}
