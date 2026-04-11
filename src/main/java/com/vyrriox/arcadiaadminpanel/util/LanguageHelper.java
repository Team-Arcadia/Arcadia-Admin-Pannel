package com.vyrriox.arcadiaadminpanel.util;

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
        en.put("nav.previous", "Previous");
        en.put("nav.next", "Next");
        en.put("reload.start", "Reloading admin panel data...");
        en.put("reload.done", "Reload complete!");
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
        fr.put("nav.previous", "Précédent");
        fr.put("nav.next", "Suivant");
        fr.put("reload.start", "Rechargement des données admin...");
        fr.put("reload.done", "Rechargement terminé !");
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
