package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.text.MessageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-click "watch this player, then put me back".
 *
 * <p>The value here is entirely in the second half. Switching to spectator and flying over is two
 * commands; remembering the exact position, rotation and game mode you were in twenty minutes and
 * three teleports ago is what people actually get wrong. The session stores all of it and restores
 * it on exit, including when the exit is involuntary: the target disconnecting, the spectator
 * disconnecting, or the server stopping.</p>
 *
 * @author vyrriox
 */
public final class SpectateManager {

    /** What to restore when the session ends. */
    public record Session(UUID target, String targetName, BackManager.Waypoint origin,
                          GameType previousMode, long startedAt) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private SpectateManager() {}

    // -- State ---------------------------------------------------------------

    public static boolean isSpectating(UUID staff) { return SESSIONS.containsKey(staff); }

    @Nullable
    public static Session get(UUID staff) { return SESSIONS.get(staff); }

    public static int count() { return SESSIONS.size(); }

    // -- Start / stop --------------------------------------------------------

    /**
     * Puts {@code staff} into spectator mode locked to {@code target}'s camera.
     *
     * @return false when the target is offline, is the staff member themselves, or a session is
     *         already running
     */
    public static boolean start(ServerPlayer staff, ServerPlayer target) {
        if (staff.getUUID().equals(target.getUUID())) return false;
        if (SESSIONS.containsKey(staff.getUUID())) return false;

        SESSIONS.put(staff.getUUID(), new Session(target.getUUID(), target.getName().getString(),
                BackManager.Waypoint.of(staff), staff.gameMode.getGameModeForPlayer(),
                System.currentTimeMillis()));

        staff.setGameMode(GameType.SPECTATOR);
        if (target.level() instanceof ServerLevel level) {
            staff.teleportTo(level, target.getX(), target.getY(), target.getZ(),
                    target.getYRot(), target.getXRot());
        }
        // Deferred one tick: the camera attach has to land after the teleport, or the client snaps
        // back to the spectator's own body on the next position sync.
        com.arcadia.lib.scheduler.SchedulerService.delayed(1, () -> {
            if (staff.hasDisconnected() || target.hasDisconnected()) return;
            staff.setCamera(target);
        });

        staff.sendSystemMessage(ArcadiaMessages.info(
                LanguageHelper.getText("spectate.started", staff)
                        .replace("%player%", target.getName().getString())));
        MessageHelper.sendActionBar(staff,
                Component.literal("§b" + LanguageHelper.getText("spectate.hint", staff)));

        AuditManager.record(staff, AdminAction.SPECTATE, target.getUUID(),
                target.getName().getString(), "start");
        return true;
    }

    /** Ends the session and restores mode and position. Returns false when none was running. */
    public static boolean stop(ServerPlayer staff) {
        Session session = SESSIONS.remove(staff.getUUID());
        if (session == null) return false;

        staff.setCamera(staff);
        staff.setGameMode(session.previousMode());

        MinecraftServer server = staff.getServer();
        if (server != null) {
            ServerLevel level = session.origin().resolveLevel(server);
            if (level != null) {
                staff.teleportTo(level, session.origin().x(), session.origin().y(),
                        session.origin().z(), session.origin().yaw(), session.origin().pitch());
            }
        }

        staff.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("spectate.stopped", staff)));
        AuditManager.record(staff, AdminAction.SPECTATE, session.target(),
                session.targetName(), "stop");
        return true;
    }

    public static boolean toggle(ServerPlayer staff, ServerPlayer target) {
        if (isSpectating(staff.getUUID())) {
            stop(staff);
            return false;
        }
        return start(staff, target);
    }

    // -- Involuntary endings -------------------------------------------------

    /** The spectator disconnected: restore their state so they do not log back in as a spectator. */
    public static void onStaffQuit(ServerPlayer staff) {
        Session session = SESSIONS.remove(staff.getUUID());
        if (session == null) return;
        staff.setCamera(staff);
        staff.setGameMode(session.previousMode());
        MinecraftServer server = staff.getServer();
        if (server == null) return;
        ServerLevel level = session.origin().resolveLevel(server);
        if (level != null) {
            staff.teleportTo(level, session.origin().x(), session.origin().y(),
                    session.origin().z(), session.origin().yaw(), session.origin().pitch());
        }
    }

    /** The target disconnected: end every session watching them. */
    public static void onTargetQuit(ServerPlayer target) {
        MinecraftServer server = target.getServer();
        if (server == null) return;
        for (var e : Map.copyOf(SESSIONS).entrySet()) {
            if (!e.getValue().target().equals(target.getUUID())) continue;
            ServerPlayer staff = server.getPlayerList().getPlayer(e.getKey());
            if (staff != null) {
                staff.sendSystemMessage(ArcadiaMessages.warning(
                        LanguageHelper.getText("spectate.target_left", staff)
                                .replace("%player%", e.getValue().targetName())));
                stop(staff);
            } else {
                SESSIONS.remove(e.getKey());
            }
        }
    }

    /** Server stop: restore everyone so nobody is saved mid-session. */
    public static void restoreAll(MinecraftServer server) {
        for (UUID staffId : Map.copyOf(SESSIONS).keySet()) {
            ServerPlayer staff = server.getPlayerList().getPlayer(staffId);
            if (staff != null) onStaffQuit(staff);
            else SESSIONS.remove(staffId);
        }
    }
}
