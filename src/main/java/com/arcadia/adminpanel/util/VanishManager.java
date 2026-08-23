package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative vanish: the staff member is genuinely removed from the other clients, not
 * merely asked not to be drawn.
 *
 * <p><b>Why server-side.</b> Hiding a player by telling the client not to render them is fine for a
 * cosmetic feature, and useless for the one job vanish actually has: watching someone who is
 * suspected of cheating. A patched client would simply ignore the instruction. So the entity is
 * removed from the observer with {@link ClientboundRemoveEntitiesPacket} and the TAB entry with
 * {@link ClientboundPlayerInfoRemovePacket}. There is nothing left on the observer's side to
 * un-hide.</p>
 *
 * <p><b>How un-vanish works without a chunk reload.</b> The naive way to make the entity come back
 * is to remove and re-add the player to the chunk map, which also drops and re-sends every chunk
 * they can see: multiple seconds of freeze on a heavy modpack. Instead the pairing packets are
 * rebuilt directly through a throwaway {@link ServerEntity} and sent to the observers that the
 * tracker still believes are watching. The server-side {@code seenBy} set was never touched, so
 * movement, equipment and animation updates resume on their own.</p>
 *
 * <p><b>Cost.</b> Zero per tick. Everything happens on the two toggle edges and on the tracking
 * events, both of which are already dispatched by the engine.</p>
 *
 * @author vyrriox
 */
public final class VanishManager {

    private static final Set<UUID> VANISHED = ConcurrentHashMap.newKeySet();

    private VanishManager() {}

    // -- State ---------------------------------------------------------------

    public static boolean isVanished(UUID uuid) { return VANISHED.contains(uuid); }

    public static boolean isVanished(ServerPlayer player) {
        return player != null && VANISHED.contains(player.getUUID());
    }

    public static int count() { return VANISHED.size(); }

    public static Set<UUID> all() { return Set.copyOf(VANISHED); }

    /**
     * An observer sees vanished staff when they hold the see node. Everyone else, including staff
     * without it, gets the same view as a regular player.
     */
    public static boolean canSee(ServerPlayer observer) {
        return AdminPermissions.VANISH_SEE.check(observer);
    }

    // -- Toggle --------------------------------------------------------------

    /** Flips vanish for {@code target} and applies it to every online client. Returns the new state. */
    public static boolean toggle(ServerPlayer actor, ServerPlayer target) {
        boolean next = !isVanished(target.getUUID());
        set(actor, target, next);
        return next;
    }

    public static void set(ServerPlayer actor, ServerPlayer target, boolean vanished) {
        if (vanished) VANISHED.add(target.getUUID());
        else VANISHED.remove(target.getUUID());

        applyToAll(target);

        MinecraftServer server = target.getServer();
        boolean silent = actor != null && SilentMode.isSilent(actor.getUUID());
        if (server != null && AdminConfig.get().vanishFakeJoinLeave && !silent) {
            // The point of the fake line is that the disappearance reads as an ordinary disconnect
            // to anyone watching the chat. It uses the vanilla wording for exactly that reason.
            Component line = Component.translatable(
                    vanished ? "multiplayer.player.left" : "multiplayer.player.joined",
                    target.getDisplayName()).withStyle(net.minecraft.ChatFormatting.YELLOW);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getUUID().equals(target.getUUID())) continue;
                if (canSee(p)) continue;
                p.sendSystemMessage(line);
            }
        }

        target.sendSystemMessage(ArcadiaMessages.info(
                LanguageHelper.getText(vanished ? "vanish.on" : "vanish.off", target)));

        AuditManager.record(actor, AdminAction.VANISH, target.getUUID(),
                target.getName().getString(), vanished ? "on" : "off");
    }

    /** Clears vanish without announcing. Used on logout when persistence is off. */
    public static void clearSilently(UUID uuid) {
        VANISHED.remove(uuid);
    }

    // -- Application ---------------------------------------------------------

    /** Pushes {@code target}'s current visibility to every other online player. */
    public static void applyToAll(ServerPlayer target) {
        MinecraftServer server = target.getServer();
        if (server == null) return;
        boolean vanished = isVanished(target.getUUID());
        for (ServerPlayer observer : server.getPlayerList().getPlayers()) {
            if (observer.getUUID().equals(target.getUUID())) continue;
            if (vanished && !canSee(observer)) hideFrom(observer, target);
            else showTo(observer, target);
        }
    }

    /** Removes {@code target} from one observer's world and TAB list. */
    public static void hideFrom(ServerPlayer observer, ServerPlayer target) {
        observer.connection.send(new ClientboundRemoveEntitiesPacket(target.getId()));
        if (AdminConfig.get().vanishHideFromTab) {
            observer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
        }
    }

    /**
     * Restores {@code target} for one observer. Rebuilds the spawn, metadata, equipment and rotation
     * packets the tracker sent the first time, rather than forcing a re-track.
     */
    public static void showTo(ServerPlayer observer, ServerPlayer target) {
        observer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));

        if (!(target.level() instanceof ServerLevel level)) return;
        // Only respawn the entity for observers the tracker still pairs with; anyone out of range
        // will get the normal spawn packet when they come back into range.
        List<ServerPlayer> watching = level.getChunkSource().chunkMap.getPlayersWatching(target);
        if (!watching.contains(observer)) return;

        ServerEntity pairing = new ServerEntity(level, target, 0, false, p -> {});
        pairing.sendPairingData(observer,
                new PacketAndPayloadAcceptor<>(p -> observer.connection.send(p)));
    }

    // -- Engine hooks --------------------------------------------------------

    /**
     * Re-hides a vanished player the moment an observer starts tracking them. Without this, walking
     * back into someone's view range would spawn the entity again.
     */
    public static void onStartTracking(ServerPlayer observer, ServerPlayer target) {
        if (!isVanished(target.getUUID())) return;
        if (canSee(observer)) return;
        hideFrom(observer, target);
    }

    /** A freshly-connected player must not see anyone who is currently vanished. */
    public static void onJoin(ServerPlayer joining) {
        MinecraftServer server = joining.getServer();
        if (server == null) return;

        if (!canSee(joining)) {
            for (UUID id : VANISHED) {
                if (id.equals(joining.getUUID())) continue;
                ServerPlayer hidden = server.getPlayerList().getPlayer(id);
                if (hidden != null) hideFrom(joining, hidden);
            }
        }

        if (isVanished(joining.getUUID())) {
            if (AdminConfig.get().vanishPersist) {
                applyToAll(joining);
                joining.sendSystemMessage(ArcadiaMessages.warning(
                        LanguageHelper.getText("vanish.still_on", joining)));
            } else {
                VANISHED.remove(joining.getUUID());
            }
        }
    }

    /** Drops the flag on disconnect unless the operator asked for it to survive. */
    public static void onQuit(ServerPlayer leaving) {
        if (!AdminConfig.get().vanishPersist) VANISHED.remove(leaving.getUUID());
    }

    /** Server stop: nothing is persisted, so the set just goes away. */
    public static void reset() { VANISHED.clear(); }
}
