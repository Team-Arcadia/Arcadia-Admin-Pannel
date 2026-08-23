package com.arcadia.adminpanel.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Catalogue of the staff actions the audit log records.
 *
 * <p>Every entry carries three things the UI needs: a stable storage id (never rename one, the audit
 * table stores it verbatim), the icon its row shows in the log menu, and whether the action counts
 * as a <em>sanction</em>. Sanctions are the subset that also lands in the unified player history and
 * feeds the escalation ladder, so a single {@link AuditManager#record} call keeps the audit log, the
 * history sheet and the templates in sync without three call sites.</p>
 *
 * @author vyrriox
 */
public enum AdminAction {

    // -- Sanctions -----------------------------------------------------------
    WARN("warn", Items.PAPER, true),
    WARN_DELETE("warn_delete", Items.PAPER, false),
    WARN_CLEAR("warn_clear", Items.PAPER, false),
    MUTE("mute", Items.SCULK_SHRIEKER, true),
    UNMUTE("unmute", Items.GREEN_DYE, false),
    KICK("kick", Items.IRON_BOOTS, true),
    BAN("ban", Items.RED_DYE, true),
    TEMPBAN("tempban", Items.RED_DYE, true),
    UNBAN("unban", Items.LIME_DYE, false),
    JAIL("jail", Items.IRON_BARS, true),
    UNJAIL("unjail", Items.IRON_DOOR, false),

    // -- Investigation -------------------------------------------------------
    FREEZE("freeze", Items.PACKED_ICE, false),
    UNFREEZE("unfreeze", Items.BLUE_ICE, false),
    VANISH("vanish", Items.GLASS, false),
    SPECTATE("spectate", Items.ENDER_EYE, false),
    SPY("spy", Items.SPYGLASS, false),
    INVSEE("invsee", Items.CHEST, false),

    // -- Player data ---------------------------------------------------------
    CLEAR_INV("clear_inv", Items.LAVA_BUCKET, false),
    INV_EDIT("inv_edit", Items.SHULKER_BOX, false),
    RESTORE_DEATH("restore_death", Items.TOTEM_OF_UNDYING, false),
    GIVE_ITEM("give_item", Items.DROPPER, false),
    RESET_PROGRESS("reset_progress", Items.EXPERIENCE_BOTTLE, false),
    GAMEMODE("gamemode", Items.GRASS_BLOCK, false),
    HEAL("heal", Items.GOLDEN_APPLE, false),
    TELEPORT("teleport", Items.ENDER_PEARL, false),
    NEXT_SPAWN("next_spawn", Items.COMPASS, false),

    // -- Records -------------------------------------------------------------
    NOTE_ADD("note_add", Items.WRITABLE_BOOK, false),
    NOTE_DELETE("note_delete", Items.WRITABLE_BOOK, false),
    WATCH_ADD("watch_add", Items.ENDER_EYE, false),
    WATCH_REMOVE("watch_remove", Items.ENDER_EYE, false),
    MAIL_SEND("mail_send", Items.PAPER, false),

    // -- Server --------------------------------------------------------------
    ANNOUNCE("announce", Items.BELL, false),
    CHAT_LOCK("chat_lock", Items.BARRIER, false),
    CHAT_CLEAR("chat_clear", Items.BUCKET, false),
    WORLD_EDIT("world_edit", Items.CLOCK, false),
    RESTART("restart", Items.REDSTONE_BLOCK, false),
    BULK("bulk", Items.BEACON, false),
    NAMETAG("nametag", Items.NAME_TAG, false),
    DISGUISE("disguise", Items.CARVED_PUMPKIN, false),
    LOGIN_QUEUE("login_queue", Items.HOPPER, false),
    SETJAIL("setjail", Items.IRON_BARS, false),
    RELOAD("reload", Items.REPEATER, false);

    private final String id;
    private final Item icon;
    private final boolean sanction;

    AdminAction(String id, Item icon, boolean sanction) {
        this.id = id;
        this.icon = icon;
        this.sanction = sanction;
    }

    /** Stable storage id. Persisted verbatim in the audit table, so it must never change. */
    public String id() { return id; }

    public Item icon() { return icon; }

    /** True when the action also belongs in the player history and the escalation ladder. */
    public boolean isSanction() { return sanction; }

    /** Translation key for the human label, e.g. {@code audit.action.tempban}. */
    public String labelKey() { return "audit.action." + id; }

    /** Resolves a stored id back to its constant, or {@code null} for an id we no longer know. */
    public static AdminAction byId(String id) {
        for (AdminAction a : values()) if (a.id.equals(id)) return a;
        return null;
    }
}
