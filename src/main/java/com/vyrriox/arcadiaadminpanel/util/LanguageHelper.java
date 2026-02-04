package com.vyrriox.arcadiaadminpanel.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple localization helper for Admin Panel
 * Supports EN and FR
 * 
 * @author vyrriox
 */
public class LanguageHelper {

    private static final Map<String, Map<String, String>> translations = new HashMap<>();

    static {
        // Initialize English
        Map<String, String> en = new HashMap<>();
        en.put("menu.title", "Admin Panel");
        en.put("menu.home", "Home");
        en.put("menu.filter.all", "Filter: ALL");
        en.put("menu.filter.online", "Filter: ONLINE");
        en.put("player.online", "Online");
        en.put("player.offline", "Offline");
        en.put("detail.title", "Player: %s");
        en.put("detail.homes", "Homes");
        en.put("detail.tp_history", "Teleport History");
        en.put("action.tp", "Teleport to Player");
        en.put("action.tp_here", "Teleport Player Here");
        en.put("action.tp_last", "TP Last Known Location");
        en.put("action.kick", "Kick Player");
        en.put("action.ban", "Ban Player");
        en.put("action.unban", "Unban Player");
        en.put("action.back", "Back to List");
        en.put("action.warn", "Warn Player"); // New
        en.put("action.warn_list", "View Warns"); // New
        en.put("info.full", "Full Information");
        en.put("info.banned", "Banned");
        en.put("info.whitelisted", "Whitelisted");
        en.put("info.last_seen", "Last Seen");
        en.put("misc.yes", "YES");
        en.put("misc.no", "NO");
        en.put("misc.click_tp", "Click to Teleport");
        en.put("misc.confirm", "Click again to confirm!"); // New
        en.put("action.invsee", "View Inventory"); // New
        en.put("action.resetprog", "Reset Progress"); // New
        en.put("action.clearinv", "Clear Inventory"); // New
        en.put("msg.inv_cleared", "Inventory cleared for %s"); // New
        en.put("warn.prompt", "Please type the reason in chat:"); // New
        en.put("warn.success", "Player warned successfully."); // New
        en.put("warn.list.title", "Warns: %s"); // New
        en.put("warn.list.empty", "No warnings found."); // New
        en.put("warn.item.title", "Warn #%d"); // New
        en.put("warn.item.by", "By: %s"); // New
        en.put("warn.item.reason", "Reason: %s"); // New
        en.put("warn.item.date", "Date: %s"); // New
        en.put("warn.title", "WARNING");
        en.put("warn.subtitle", "You have been warned!");
        en.put("warn.notification", "You have been warned by %s");
        en.put("warn.deleted", "Warning #%d deleted for player %s.");
        en.put("error.invalid_index", "Invalid warning index.");
        en.put("error.no_warns", "This player has no warnings.");
        en.put("error.invalid_target", "Invalid target.");
        translations.put("en", en);

        // Initialize French
        Map<String, String> fr = new HashMap<>();
        fr.put("menu.title", "Panneau Admin");
        fr.put("menu.home", "Accueil");
        fr.put("menu.filter.all", "Filtre: TOUS");
        fr.put("menu.filter.online", "Filtre: EN LIGNE");
        fr.put("player.online", "En Ligne");
        fr.put("player.offline", "Hors Ligne");
        fr.put("detail.title", "Joueur: %s");
        fr.put("detail.homes", "Homes");
        fr.put("detail.tp_history", "Historique TP");
        fr.put("action.tp", "Se TP au Joueur");
        fr.put("action.tp_here", "TP le Joueur Ici");
        fr.put("action.tp_last", "TP Dernière Position");
        fr.put("action.kick", "Expulser Joueur");
        fr.put("action.ban", "Bannir Joueur");
        fr.put("action.unban", "Débannir Joueur");
        fr.put("action.back", "Retour Liste");
        fr.put("action.warn", "Avertir Joueur"); // New
        fr.put("action.warn_list", "Voir Avertissements"); // New
        fr.put("info.full", "Informations Complètes");
        fr.put("info.banned", "Banni");
        fr.put("info.whitelisted", "Whitelisté");
        fr.put("info.last_seen", "Dernière Vue");
        fr.put("misc.yes", "OUI");
        fr.put("misc.no", "NON");
        fr.put("misc.click_tp", "Clic pour TP");
        fr.put("misc.confirm", "Cliquez encore pour confirmer !"); // New
        fr.put("action.invsee", "Voir Inventaire"); // New
        fr.put("action.resetprog", "Reset Progression"); // New
        fr.put("action.clearinv", "Vider Inventaire"); // New
        fr.put("msg.inv_cleared", "Inventaire vidé pour %s"); // New
        fr.put("warn.prompt", "Veuillez écrire la raison dans le chat :"); // New
        fr.put("warn.success", "Joueur averti avec succès."); // New
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
        fr.put("error.invalid_index", "Numéro d'avertissement invalide.");
        fr.put("error.no_warns", "Ce joueur n'a aucun avertissement.");
        fr.put("error.invalid_target", "Cible invalide.");
        translations.put("fr", fr);
    }

    public static String getText(String key, ServerPlayer player) {
        String locale = "en";
        // Attempt to get client language
        // ServerPlayer.clientInformation() (NeoForge 1.21.1 method might vary slightly,
        // checking standard access)
        try {
            // clientInformation() is available in recent versions, if not we fallback
            if (player != null && player.clientInformation() != null) {
                String lang = player.clientInformation().language();
                if (lang != null && lang.toLowerCase().startsWith("fr")) {
                    locale = "fr";
                }
            }
        } catch (Exception e) {
            // Fallback
        }
        return getText(key, locale);
    }

    public static String getText(String key, String locale) {
        Map<String, String> map = translations.getOrDefault(locale, translations.get("en"));
        return map.getOrDefault(key, key);
    }

    public static Component getComponent(String key, ServerPlayer player) {
        return Component.literal(getText(key, player));
    }
}
