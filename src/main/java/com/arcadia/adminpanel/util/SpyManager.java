package com.arcadia.adminpanel.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command spy and social spy: the two feeds that show staff what is happening off the public chat.
 *
 * <p><b>Command spy</b> echoes the commands other players run. <b>Social spy</b> echoes private
 * messages. Both are opt-in per staff member and off by default, because both are high volume and
 * because a feed nobody asked for is a feed nobody reads.</p>
 *
 * <p><b>Cost when nobody is listening.</b> Every hook starts with an emptiness check on a
 * {@code ConcurrentHashMap} key set. With no subscriber that is one volatile read per command, which
 * is far below the noise floor of parsing the command itself. There is no tick hook at all.</p>
 *
 * <p><b>What is never echoed.</b> Staff chat, and the admin panel's own staff-only subcommands. A
 * spy feed that repeats the staff channel would re-create exactly the leak the 1.2.12 fix closed,
 * one layer up.</p>
 *
 * @author vyrriox
 */
public final class SpyManager {

    private static final Set<UUID> COMMAND_SPIES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> SOCIAL_SPIES = ConcurrentHashMap.newKeySet();

    /** Command roots never echoed: they are the staff channel or contain a private message we handle. */
    private static final Set<String> NEVER_ECHO = Set.of(
            "arcadia_adminpanel", "msg", "tell", "w", "whisper", "r", "reply", "me", "staffchat");

    private SpyManager() {}

    // -- Toggles -------------------------------------------------------------

    public static boolean toggleCommandSpy(UUID staff) {
        if (COMMAND_SPIES.remove(staff)) return false;
        COMMAND_SPIES.add(staff);
        return true;
    }

    public static boolean toggleSocialSpy(UUID staff) {
        if (SOCIAL_SPIES.remove(staff)) return false;
        SOCIAL_SPIES.add(staff);
        return true;
    }

    public static boolean hasCommandSpy(UUID staff) { return COMMAND_SPIES.contains(staff); }

    public static boolean hasSocialSpy(UUID staff) { return SOCIAL_SPIES.contains(staff); }

    /** Clears both feeds for a disconnecting staff member. */
    public static void clear(UUID staff) {
        COMMAND_SPIES.remove(staff);
        SOCIAL_SPIES.remove(staff);
    }

    public static int commandSpyCount() { return COMMAND_SPIES.size(); }

    public static int socialSpyCount() { return SOCIAL_SPIES.size(); }

    // -- Feeds ---------------------------------------------------------------

    /**
     * Echoes a command to the subscribed staff. Returns immediately when nobody is listening.
     *
     * @param sender the player who ran it
     * @param raw    the full command line, with or without a leading slash
     */
    public static void onCommand(ServerPlayer sender, String raw) {
        if (COMMAND_SPIES.isEmpty()) return;
        String line = raw.startsWith("/") ? raw.substring(1) : raw;
        String root = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (NEVER_ECHO.contains(root)) return;

        Component out = Component.literal("§8[§dCMD§8] §7" + sender.getName().getString() + " §8> §f/" + line);
        for (UUID id : COMMAND_SPIES) {
            if (id.equals(sender.getUUID())) continue;
            ServerPlayer staff = sender.getServer() == null ? null
                    : sender.getServer().getPlayerList().getPlayer(id);
            if (staff != null) staff.sendSystemMessage(out);
        }
    }

    /**
     * Echoes a private message to the subscribed staff. Both ends of the conversation are skipped so
     * a spying staff member never sees their own exchange twice.
     */
    public static void onPrivateMessage(ServerPlayer from, String toName, String message) {
        if (SOCIAL_SPIES.isEmpty()) return;
        Component out = Component.literal("§8[§dMSG§8] §7" + from.getName().getString()
                + " §8-> §7" + toName + "§8: §f" + message);
        for (UUID id : SOCIAL_SPIES) {
            if (id.equals(from.getUUID())) continue;
            ServerPlayer staff = from.getServer() == null ? null
                    : from.getServer().getPlayerList().getPlayer(id);
            if (staff == null) continue;
            if (staff.getName().getString().equalsIgnoreCase(toName)) continue;
            staff.sendSystemMessage(out);
        }
    }

    /**
     * Recognises the vanilla and common modded private-message commands so the social feed can pick
     * the recipient and body out of a raw command line.
     *
     * @return {@code null} when the line is not a private message
     */
    public static String[] parsePrivateMessage(String raw) {
        String line = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 3) return null;
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (!root.equals("msg") && !root.equals("tell") && !root.equals("w") && !root.equals("whisper")) {
            return null;
        }
        return new String[] { parts[1], parts[2] };
    }
}
