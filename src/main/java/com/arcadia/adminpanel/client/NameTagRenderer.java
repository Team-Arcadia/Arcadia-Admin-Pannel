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
        // Drop occlusion-cache entries for players we haven't queried in a while (out of view /
        // left). Cheap sweep once a second; keeps the map bounded without per-frame work.
        if ((animTick & 0x1F) == 0 && !OCCLUSION_CACHE.isEmpty()) {
            OCCLUSION_CACHE.values().removeIf(r -> animTick - r.tick > 40);
        }
    }

    /** Wipe synced state on disconnect so a relog / server switch never shows stale names. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNameTagState.clear();
        OCCLUSION_CACHE.clear();
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Minecraft mc = Minecraft.getInstance();
        UUID uuid = player.getUUID();
        boolean self = isSelf(mc, player);

        // ── 0. Total hide (1.2.9) ──────────────────────────────────────────────
        // A player force-hidden by an admin is invisible to everyone, always. The global event
        // blackout hides everyone too — but exempt players (staff) stay visible through it. Both
        // win over occlusion/styling: nothing below runs once we suppress.
        if (!self && ClientDisguiseState.isDisguised(uuid)) {
            // A disguised player shows the mob, not their name (decision: hide the pseudo).
            event.setCanRender(TriState.FALSE);
            return;
        }
        if (!self && ClientNameTagState.isForceHidden(uuid)) {
            event.setCanRender(TriState.FALSE);
            return;
        }
        if (!self && ClientNameTagState.isHideAll() && !ClientNameTagState.isHideExempt(uuid)) {
            event.setCanRender(TriState.FALSE);
            return;
        }

        // ── 1. Occlusion: hide the name if a wall is in the way ────────────────
        if (ClientNameTagState.isHideEnabled()
                && !ClientNameTagState.isHideExempt(uuid)
                && !self
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
     * Per-player occlusion cache. The {@link RenderNameTagEvent} fires once per <em>frame</em> per
     * visible named player — at 120 fps that is 120 raytraces/s per player, almost all redundant
     * since neither the camera nor the player moves perceptibly between frames. We therefore recompute
     * the raytrace at most once every {@link #OCCLUSION_RECHECK_TICKS} client ticks and reuse the
     * cached verdict in between. This is the "optimise" half of the 1.2.9 wall-occlusion work: the
     * heavy {@code level.clip} now runs ~10×/s instead of per-frame, with no visible lag (a name
     * blinks at most ~100 ms late when someone steps behind a wall). Keyed on UUID; entries are
     * pruned when their player isn't re-queried for a while, and wiped on disconnect.
     */
    private record OcclusionResult(boolean occluded, int tick) {}
    private static final java.util.Map<UUID, OcclusionResult> OCCLUSION_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Re-run the raytrace at most this often (2 ticks ≈ 100 ms). */
    private static final int OCCLUSION_RECHECK_TICKS = 2;

    /**
     * Raytrace from the active camera position to the target player's eye height. A non-MISS hit
     * means a block sits between camera and player → the name is occluded. When the config does not
     * count transparent blocks, a hit on a non-opaque block (glass, leaves, fences…) is treated as
     * "not occluding" so you still see names through windows. Result is cached for a couple of ticks
     * (see {@link #OCCLUSION_CACHE}) to keep the per-frame cost negligible.
     */
    private static boolean isOccluded(Minecraft mc, Player player) {
        UUID uuid = player.getUUID();
        OcclusionResult cached = OCCLUSION_CACHE.get(uuid);
        if (cached != null && animTick - cached.tick < OCCLUSION_RECHECK_TICKS) {
            return cached.occluded;
        }
        boolean result = computeOccluded(mc, player);
        OCCLUSION_CACHE.put(uuid, new OcclusionResult(result, animTick));
        return result;
    }

    private static boolean computeOccluded(Minecraft mc, Player player) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return false;
        Level level = player.level();
        Vec3 from = camera.getPosition();
        Vec3 to = new Vec3(player.getX(), player.getEyeY(), player.getZ());

        // Beyond the configured range (default 128 blocks, raised from the old hard 64-block gate
        // that let distant players stay readable through walls) we skip the raytrace and show the name.
        if (from.distanceToSqr(to) > ClientNameTagState.maxHideDistanceSqr()) return false;

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
