package com.arcadia.adminpanel.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Localization helper for Admin Panel — EN and FR.
 *
 * @author vyrriox
 */
public final class LanguageHelper {

    private static final Map<String, Map<String, String>> translations = new HashMap<>();

    static {
        Map<String, String> en = new HashMap<>();
        en.put("menu.title", "Admin Panel");
        en.put("menu.filter.all", "Filter: ALL");
        en.put("menu.filter.online", "Filter: ONLINE");
        en.put("player.online", "Online");
        en.put("player.offline", "Offline");
        en.put("detail.title", "Player: %s");
        en.put("detail.homes", "Homes");
        en.put("detail.tp_history", "Teleport History");
        en.put("homes.none", "No Homes");
        en.put("action.tp", "Teleport to Player");
        en.put("action.tp_here", "Teleport Player Here");
        en.put("action.tp_last", "TP Last Known Location");
        en.put("action.kick", "Kick Player");
        en.put("action.ban", "Ban Player");
        en.put("action.unban", "Unban Player");
        en.put("action.back", "Back to List");
        en.put("action.warn", "Warn Player");
        en.put("action.warn_list", "View Warns");
        en.put("action.search", "Search Players");
        en.put("action.search.hint", "Click to search by name");
        en.put("action.search.prompt", "Type a player name to search (or 'cancel'):");
        en.put("action.search.clear", "Clear Search");
        en.put("action.search.current", "Current filter:");
        en.put("action.cancelled", "Action cancelled.");
        en.put("info.full", "Full Information");
        en.put("info.banned", "Banned");
        en.put("info.whitelisted", "Whitelisted");
        en.put("info.last_seen", "Last Seen");
        en.put("misc.yes", "YES");
        en.put("misc.no", "NO");
        en.put("misc.click_tp", "Click to Teleport");
        en.put("misc.confirm", "Click again to confirm!");
        en.put("action.invsee", "View Inventory");
        en.put("action.resetprog", "Reset Progress");
        en.put("action.clearinv", "Clear Inventory");
        en.put("msg.inv_cleared", "Inventory cleared for %s");
        en.put("warn.prompt", "Type the warn reason in chat (or 'cancel'):");
        en.put("warn.prompt.cancel", "Type 'cancel' to abort.");
        en.put("warn.success", "Player warned successfully.");
        en.put("warn.list.title", "Warns: %s");
        en.put("warn.list.empty", "No warnings found.");
        en.put("warn.item.title", "Warn #%d");
        en.put("warn.item.by", "By: %s");
        en.put("warn.item.reason", "Reason: %s");
        en.put("warn.item.date", "Date: %s");
        en.put("warn.title", "WARNING");
        en.put("warn.subtitle", "You have been warned!");
        en.put("warn.notification", "You have been warned by %s");
        en.put("warn.deleted", "Warning #%d deleted for player %s.");
        en.put("warn.click_delete", "Click to delete");
        en.put("warn.cleared", "All warnings cleared for %s (%d removed).");
        en.put("error.invalid_index", "Invalid warning index.");
        en.put("error.no_warns", "This player has no warnings.");
        en.put("error.invalid_target", "Invalid target.");
        en.put("error.player_only", "This command can only be used by players.");
        en.put("action.mute", "Mute Player");
        en.put("action.unmute", "Unmute Player");
        en.put("mute.hint", "Click to mute for 10 minutes");
        en.put("mute.remaining", "Time remaining:");
        en.put("mute.reason", "Reason:");
        en.put("mute.feedback", "You are muted. Remaining: %time% — Reason: %reason%");
        en.put("staff.chat.enabled", "Staff chat enabled. All messages go to staff channel.");
        en.put("staff.chat.disabled", "Staff chat disabled. Messages go to public chat.");
        en.put("staff.online", "Staff online (%d):");
        en.put("staff.none_online", "No staff members online.");
        en.put("action.jail", "Jail Player");
        en.put("action.unjail", "Unjail Player");
        en.put("jail.hint", "Click to jail for 30 minutes");
        en.put("jail.remaining", "Time remaining:");
        en.put("jail.reason.label", "Reason:");
        en.put("jail.permanent", "Permanent");
        en.put("jail.location.set", "Jail location set at your current position!");
        en.put("jail.no_location", "No jail location set! Use /arcadia_adminpanel setjail first.");
        en.put("jail.success", "Jailed %player% for %time%.");
        en.put("jail.unjail.success", "Unjailed %player%.");
        en.put("jail.not_jailed", "%player% is not jailed.");
        en.put("jail.notify", "You have been jailed! Duration: %time% — Reason: %reason%");
        en.put("jail.notify.permanent", "You have been permanently jailed! Reason: %reason%");
        en.put("jail.released", "You have been released from jail!");
        en.put("jail.login.reminder", "You are still jailed. Remaining: %time% — Reason: %reason%");
        en.put("jail.blocked.command", "You cannot use commands while jailed.");
        en.put("jail.list.empty", "No players currently jailed.");
        en.put("jail.list.header", "Jailed players (%count%):");
        en.put("nav.previous", "Previous");
        en.put("nav.next", "Next");
        en.put("reload.start", "Reloading admin panel data...");
        en.put("reload.done", "Reload complete!");
        en.put("error.open_panel", "Failed to open admin panel: %s");
        en.put("warn.reason_prefix", "Reason:");
        en.put("warn.list_console", "Warnings for %s: %d");
        en.put("misc.admin_action", "Admin Action");
        en.put("misc.unknown", "Unknown");
        en.put("misc.dim", "Dim:");
        en.put("misc.pos", "Pos:");
        en.put("misc.server", "Server:");
        en.put("misc.warns_label", "Warns:");
        en.put("misc.warn_count", "%d warn(s)");
        en.put("tp.success", "Teleported to %.0f, %.0f, %.0f");
        en.put("search.placeholder", "Search...");
        en.put("info.last_login", "Last login:");
        en.put("info.last_logout", "Last logout:");
        en.put("info.first_seen", "First seen:");
        en.put("team.browse", "Browse Teams");
        en.put("team.browse.hint", "View all FTB Teams (parties + server teams)");
        en.put("team.list.title", "FTB Teams");
        en.put("team.list.empty", "No teams found.");
        en.put("team.detail.title", "Team Details");
        en.put("team.not_found", "Team not found (data may be stale, reload).");
        en.put("team.unavailable", "FTB Teams not detected");
        en.put("team.unavailable.hint", "The ftbteams data dir was not found on this server.");
        en.put("team.view", "View Team");
        en.put("team.name", "Name:");
        en.put("team.type", "Type:");
        en.put("team.members", "Members:");
        en.put("team.rank", "Rank:");
        en.put("team.click.view", "Click to view members");
        en.put("team.click.tp", "Right-click: TP to last-seen position");
        en.put("team.claims", "Claims:");
        en.put("team.force_loaded", "Force-loaded:");
        en.put("warn.join.header", "You have %count% active warning(s):");
        en.put("warn.join.expires", "expires in");
        en.put("warn.join.more", "more — use /arcadia_adminpanel checkwarn to view all.");
        en.put("warn.join.cmd_hint", "To view your warnings, run:");
        en.put("jail.blocked.teleport", "You cannot use teleport items or blocks while jailed.");
        en.put("warn.join.cmd_hover", "Click to run the command.");
        en.put("baton.tooltip.line1", "Right-click a player to jail them for 30 minutes.");
        en.put("baton.tooltip.line2", "Staff only — requires arcadia.adminpanel.jail.");
        en.put("baton.reason", "Whacked with a Jail Baton");
        en.put("baton.no_perm", "You do not have permission to use the Jail Baton.");
        en.put("baton.no_self", "You cannot baton yourself.");
        en.put("baton.no_staff", "You cannot baton other staff members.");
        en.put("baton.given", "Jail Baton added to your inventory.");
        translations.put("en", en);

        Map<String, String> fr = new HashMap<>();
        fr.put("menu.title", "Panneau Admin");
        fr.put("menu.filter.all", "Filtre: TOUS");
        fr.put("menu.filter.online", "Filtre: EN LIGNE");
        fr.put("player.online", "En Ligne");
        fr.put("player.offline", "Hors Ligne");
        fr.put("detail.title", "Joueur: %s");
        fr.put("detail.homes", "Homes");
        fr.put("detail.tp_history", "Historique TP");
        fr.put("homes.none", "Aucun Home");
        fr.put("action.tp", "Se TP au Joueur");
        fr.put("action.tp_here", "TP le Joueur Ici");
        fr.put("action.tp_last", "TP Dernière Position");
        fr.put("action.kick", "Expulser Joueur");
        fr.put("action.ban", "Bannir Joueur");
        fr.put("action.unban", "Débannir Joueur");
        fr.put("action.back", "Retour Liste");
        fr.put("action.warn", "Avertir Joueur");
        fr.put("action.warn_list", "Voir Avertissements");
        fr.put("action.search", "Rechercher Joueurs");
        fr.put("action.search.hint", "Cliquez pour chercher par nom");
        fr.put("action.search.prompt", "Tapez un nom de joueur (ou 'cancel') :");
        fr.put("action.search.clear", "Effacer Recherche");
        fr.put("action.search.current", "Filtre actuel :");
        fr.put("action.cancelled", "Action annulée.");
        fr.put("info.full", "Informations Complètes");
        fr.put("info.banned", "Banni");
        fr.put("info.whitelisted", "Whitelisté");
        fr.put("info.last_seen", "Dernière Vue");
        fr.put("misc.yes", "OUI");
        fr.put("misc.no", "NON");
        fr.put("misc.click_tp", "Clic pour TP");
        fr.put("misc.confirm", "Cliquez encore pour confirmer !");
        fr.put("action.invsee", "Voir Inventaire");
        fr.put("action.resetprog", "Reset Progression");
        fr.put("action.clearinv", "Vider Inventaire");
        fr.put("msg.inv_cleared", "Inventaire vidé pour %s");
        fr.put("warn.prompt", "Écrivez la raison dans le chat (ou 'cancel') :");
        fr.put("warn.prompt.cancel", "Tapez 'cancel' pour annuler.");
        fr.put("warn.success", "Joueur averti avec succès.");
        fr.put("warn.list.title", "Avertissements : %s");
        fr.put("warn.list.empty", "Aucun avertissement.");
        fr.put("warn.item.title", "Avertissement #%d");
        fr.put("warn.item.by", "Par : %s");
        fr.put("warn.item.reason", "Raison : %s");
        fr.put("warn.item.date", "Date : %s");
        fr.put("warn.title", "ATTENTION");
        fr.put("warn.subtitle", "Vous avez reçu un avertissement !");
        fr.put("warn.notification", "Vous avez été averti par %s");
        fr.put("warn.deleted", "Avertissement #%d supprimé pour le joueur %s.");
        fr.put("warn.click_delete", "Cliquez pour supprimer");
        fr.put("warn.cleared", "Tous les avertissements supprimés pour %s (%d supprimés).");
        fr.put("error.invalid_index", "Numéro d'avertissement invalide.");
        fr.put("error.no_warns", "Ce joueur n'a aucun avertissement.");
        fr.put("error.invalid_target", "Cible invalide.");
        fr.put("error.player_only", "Cette commande ne peut être utilisée que par un joueur.");
        fr.put("action.mute", "Rendre Muet");
        fr.put("action.unmute", "Retirer le Mute");
        fr.put("mute.hint", "Cliquez pour mute 10 minutes");
        fr.put("mute.remaining", "Temps restant :");
        fr.put("mute.reason", "Raison :");
        fr.put("mute.feedback", "Vous êtes muté. Restant : %time% — Raison : %reason%");
        fr.put("staff.chat.enabled", "Chat staff activé. Tous vos messages vont au canal staff.");
        fr.put("staff.chat.disabled", "Chat staff désactivé. Messages en chat public.");
        fr.put("staff.online", "Staff en ligne (%d) :");
        fr.put("staff.none_online", "Aucun staff en ligne.");
        fr.put("action.jail", "Emprisonner");
        fr.put("action.unjail", "Libérer");
        fr.put("jail.hint", "Cliquez pour emprisonner 30 minutes");
        fr.put("jail.remaining", "Temps restant :");
        fr.put("jail.reason.label", "Raison :");
        fr.put("jail.permanent", "Permanent");
        fr.put("jail.location.set", "Position de la prison définie à votre position !");
        fr.put("jail.no_location", "Aucune prison définie ! Utilisez /arcadia_adminpanel setjail d'abord.");
        fr.put("jail.success", "%player% emprisonné pour %time%.");
        fr.put("jail.unjail.success", "%player% libéré de prison.");
        fr.put("jail.not_jailed", "%player% n'est pas en prison.");
        fr.put("jail.notify", "Vous avez été emprisonné ! Durée : %time% — Raison : %reason%");
        fr.put("jail.notify.permanent", "Vous avez été emprisonné définitivement ! Raison : %reason%");
        fr.put("jail.released", "Vous avez été libéré de prison !");
        fr.put("jail.login.reminder", "Vous êtes toujours en prison. Restant : %time% — Raison : %reason%");
        fr.put("jail.blocked.command", "Vous ne pouvez pas utiliser de commandes en prison.");
        fr.put("jail.list.empty", "Aucun joueur en prison.");
        fr.put("jail.list.header", "Joueurs en prison (%count%) :");
        fr.put("nav.previous", "Précédent");
        fr.put("nav.next", "Suivant");
        fr.put("reload.start", "Rechargement des données admin...");
        fr.put("reload.done", "Rechargement terminé !");
        fr.put("error.open_panel", "Erreur d'ouverture du panneau admin : %s");
        fr.put("warn.reason_prefix", "Raison :");
        fr.put("warn.list_console", "Avertissements pour %s : %d");
        fr.put("misc.admin_action", "Action Admin");
        fr.put("misc.unknown", "Inconnu");
        fr.put("misc.dim", "Dim :");
        fr.put("misc.pos", "Pos :");
        fr.put("misc.server", "Serveur :");
        fr.put("misc.warns_label", "Avertissements :");
        fr.put("misc.warn_count", "%d avertissement(s)");
        fr.put("tp.success", "Téléporté à %.0f, %.0f, %.0f");
        fr.put("search.placeholder", "Rechercher...");
        fr.put("info.last_login", "Dernière connexion :");
        fr.put("info.last_logout", "Dernière déconnexion :");
        fr.put("info.first_seen", "Première fois vu :");
        fr.put("team.browse", "Parcourir les Teams");
        fr.put("team.browse.hint", "Voir toutes les FTB Teams (parties + teams serveur)");
        fr.put("team.list.title", "FTB Teams");
        fr.put("team.list.empty", "Aucune team trouvée.");
        fr.put("team.detail.title", "Détails de la Team");
        fr.put("team.not_found", "Team introuvable (données obsolètes, rechargez).");
        fr.put("team.unavailable", "FTB Teams non détecté");
        fr.put("team.unavailable.hint", "Le dossier ftbteams est introuvable sur ce serveur.");
        fr.put("team.view", "Voir la Team");
        fr.put("team.name", "Nom :");
        fr.put("team.type", "Type :");
        fr.put("team.members", "Membres :");
        fr.put("team.rank", "Rang :");
        fr.put("team.click.view", "Cliquez pour voir les membres");
        fr.put("team.click.tp", "Clic droit : TP à la dernière position");
        fr.put("team.claims", "Claims :");
        fr.put("team.force_loaded", "Force-loaded :");
        fr.put("warn.join.header", "Vous avez %count% avertissement(s) actif(s) :");
        fr.put("warn.join.expires", "expire dans");
        fr.put("warn.join.more", "de plus — tapez /arcadia_adminpanel checkwarn pour tout voir.");
        fr.put("warn.join.cmd_hint", "Pour voir vos avertissements, tapez :");
        fr.put("jail.blocked.teleport", "Vous ne pouvez pas utiliser d'objets ou de blocs de téléportation en prison.");
        fr.put("warn.join.cmd_hover", "Cliquez pour exécuter la commande.");
        fr.put("baton.tooltip.line1", "Clic droit sur un joueur pour l'emprisonner 30 minutes.");
        fr.put("baton.tooltip.line2", "Staff uniquement — nécessite arcadia.adminpanel.jail.");
        fr.put("baton.reason", "Frappé avec la Matraque de Prison");
        fr.put("baton.no_perm", "Vous n'avez pas la permission d'utiliser la Matraque.");
        fr.put("baton.no_self", "Vous ne pouvez pas vous matraquer vous-même.");
        fr.put("baton.no_staff", "Vous ne pouvez pas matraquer un autre membre du staff.");
        fr.put("baton.given", "Matraque de Prison ajoutée à votre inventaire.");
        translations.put("fr", fr);
    }

    public static String getText(String key, ServerPlayer player) {
        String locale = "en";
        try {
            if (player != null && player.clientInformation() != null) {
                String lang = player.clientInformation().language();
                if (lang != null && lang.toLowerCase().startsWith("fr")) {
                    locale = "fr";
                }
            }
        } catch (Exception ignored) {}
        return getText(key, locale);
    }

    public static String getText(String key, String locale) {
        Map<String, String> map = translations.getOrDefault(locale, translations.get("en"));
        return map.getOrDefault(key, key);
    }

    public static Component getComponent(String key, ServerPlayer player) {
        return Component.literal(getText(key, player));
    }

    private LanguageHelper() {}
}
