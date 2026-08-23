package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.staff.StaffService;
import com.arcadia.lib.text.TextFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * In-game notification fan-out for staff-visible events.
 *
 * <p>Three audiences, deliberately separated:</p>
 * <ul>
 *   <li><b>Staff</b> see every sanction and every watchlist hit, silent or not. This is the channel
 *       that keeps a team coordinated, so nothing is filtered out of it.</li>
 *   <li><b>Everyone</b> sees a sanction only when the operator enabled public broadcasts and the
 *       acting staff member is not in silent mode.</li>
 *   <li><b>The spy channel</b> (command spy, social spy) reaches only the staff who opted in, since
 *       it is high volume by nature.</li>
 * </ul>
 *
 * <p>Every send is a bounded loop over the online staff list. There is no tick hook and no polling:
 * a quiet server costs nothing.</p>
 *
 * @author vyrriox
 */
public final class StaffFeed {

    private static volatile MinecraftServer server;

    private StaffFeed() {}

    public static void bind(MinecraftServer srv) { server = srv; }

    public static void unbind() { server = null; }

    @Nullable
    public static MinecraftServer server() { return server; }

    // -- Audit fan-out -------------------------------------------------------

    /** Called by {@link AuditManager} for every recorded action. */
    static void onAudit(AdminAction action, AuditManager.AuditEntry entry, boolean silent) {
        if (!action.isSanction()) return;
        MinecraftServer srv = server;
        if (srv == null) return;

        String duration = entry.durationMs() > 0
                ? " §7(" + TextFormatter.formatMs(entry.durationMs()) + ")"
                : "";
        String reason = entry.detail() == null || entry.detail().isBlank()
                ? "" : " §7- §f" + entry.detail();

        for (ServerPlayer staff : StaffService.getStaffOnline()) {
            String label = LanguageHelper.getText(action.labelKey(), staff);
            staff.sendSystemMessage(Component.literal(
                    "§8[§bStaff§8] " + (silent ? "§8[§7silent§8] " : "")
                  + "§e" + entry.actorName() + " §7" + label + " §e" + entry.targetName()
                  + duration + reason));
        }

        if (silent || !AdminConfig.get().broadcastSanctions) return;
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            String label = LanguageHelper.getText(action.labelKey(), p);
            p.sendSystemMessage(ArcadiaMessages.warning(
                    entry.targetName() + " §7" + label + duration + reason));
        }
    }

    // -- Targeted alerts -----------------------------------------------------

    /** Sends a line to every online staff member. Used by watchlist hits and automated alerts. */
    public static void alertStaff(String message) {
        for (ServerPlayer staff : StaffService.getStaffOnline()) {
            staff.sendSystemMessage(ArcadiaMessages.warning(message));
        }
    }

    /** Sends a pre-translated line to every online staff member, translated per viewer. */
    public static void alertStaffKey(String key, java.util.function.Function<ServerPlayer, String> render) {
        for (ServerPlayer staff : StaffService.getStaffOnline()) {
            staff.sendSystemMessage(ArcadiaMessages.warning(render.apply(staff)));
        }
    }

    /** Sends a raw component to every online staff member except {@code except}. */
    public static void toStaff(Component line, @Nullable UUID except) {
        for (ServerPlayer staff : StaffService.getStaffOnline()) {
            if (except != null && staff.getUUID().equals(except)) continue;
            staff.sendSystemMessage(line);
        }
    }
}
