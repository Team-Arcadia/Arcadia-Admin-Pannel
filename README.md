# Arcadia Admin Panel

Steampunk-themed server administration mod for Minecraft, powered by Arcadia Lib. Provides a complete GUI for player management, moderation, and warning system with multi-server synchronization.

## Features
- **Arcadia Theme**: Steampunk copper-themed GUI powered by Arcadia Lib's design system.
- **Player Search**: Built-in search bar to quickly find players by name.
- **Player GUI**: Manage online and offline players in a paginated interface (InvSee, Clear Inventory, Reset Progress).
- **Offline Data**: View homes and last seen locations (FTB Essentials integration).
- **Warn System**: Warn players with reasons, track history. Supports MySQL multi-server sync or standalone JSON.
- **Moderation**: Teleport, Kick, Ban, Unban directly from the GUI.
- **Permission-Aware**: Action buttons are hidden if you lack required permissions. Compatible with LuckPerms.
- **Bilingual**: Automatic language detection (English/French) based on client settings.
- **Multi-Server**: Warnings sync across servers via shared MySQL database (Arcadia Lib).
- **Optimized**: Thread-safe, tick-friendly, async database operations, atomic file writes.

## Requirements
- Minecraft 1.21.1
- NeoForge 21.1+
- [Arcadia Lib](https://github.com/vyrriox/Arcadia) >= 1.2.0

## Commands
- `/arcadia_adminpanel panel [filter]` *(Op Level 2)*: Opens the admin interface, optionally filtered by name.
- `/arcadia_adminpanel reload` *(Op Level 2)*: Reloads offline player cache, FTB data, and warns.
- `/arcadia_adminpanel warn <player> <reason>` *(Op Level 2)*: Warns a player.
- `/arcadia_adminpanel warnlist <player>` *(Op Level 2)*: Views a player's warning history.
- `/arcadia_adminpanel delwarn <player> <index>` *(Op Level 2)*: Deletes a specific warning.
- `/arcadia_adminpanel clearwarns <player>` *(Op Level 2)*: Clears all warnings for a player.
- `/arcadia_adminpanel checkwarn` *(All)*: Players can check their own warnings.

## Installation
1. Install NeoForge 21.1+ for Minecraft 1.21.1.
2. Place `arcadia-lib-1.2.0.jar` in the `mods/` folder.
3. Place `arcadia-admin-panel-1.2.0.jar` in the `mods/` folder.
4. (Optional) Configure MySQL in `config/arcadia-database.toml` for multi-server warn sync.

## Credits
Author: vyrriox

---

# Arcadia Admin Panel (Version Française)

Mod d'administration serveur au thème steampunk pour Minecraft, propulsé par Arcadia Lib. Fournit une interface complète pour la gestion des joueurs, la modération et un système d'avertissement avec synchronisation multi-serveur.

## Caractéristiques
- **Thème Arcadia**: Interface GUI steampunk cuivrée propulsée par le système de design d'Arcadia Lib.
- **Recherche de Joueurs**: Barre de recherche intégrée pour trouver rapidement les joueurs par nom.
- **GUI Joueurs**: Gérez les joueurs en ligne et hors ligne (InvSee, Vider Inventaire, Reset Progression).
- **Données Hors Ligne**: Consultez les homes et dernière position (intégration FTB Essentials).
- **Système d'Avertissement**: Avertissez les joueurs, suivez l'historique. Support MySQL multi-serveur ou JSON autonome.
- **Modération**: Téléportation, Expulsion, Bannissement, Débannissement depuis l'interface.
- **Permissions Dynamiques**: Les boutons d'action sont cachés sans permission. Compatible LuckPerms.
- **Bilingue**: Détection automatique de la langue (Anglais/Français) selon le client.
- **Multi-Serveur**: Les avertissements se synchronisent entre serveurs via base de données MySQL partagée (Arcadia Lib).
- **Optimisé**: Thread-safe, tick-friendly, opérations async, écriture atomique des fichiers.

## Prérequis
- Minecraft 1.21.1
- NeoForge 21.1+
- [Arcadia Lib](https://github.com/vyrriox/Arcadia) >= 1.2.0

## Commandes
- `/arcadia_adminpanel panel [filtre]` *(Op Niveau 2)*: Ouvre l'interface admin, filtrage optionnel par nom.
- `/arcadia_adminpanel reload` *(Op Niveau 2)*: Recharge le cache joueurs hors ligne, données FTB et avertissements.
- `/arcadia_adminpanel warn <joueur> <raison>` *(Op Niveau 2)*: Avertit un joueur.
- `/arcadia_adminpanel warnlist <joueur>` *(Op Niveau 2)*: Affiche l'historique des avertissements.
- `/arcadia_adminpanel delwarn <joueur> <index>` *(Op Niveau 2)*: Supprime un avertissement spécifique.
- `/arcadia_adminpanel clearwarns <joueur>` *(Op Niveau 2)*: Supprime tous les avertissements d'un joueur.
- `/arcadia_adminpanel checkwarn` *(Tous)*: Les joueurs peuvent voir leurs propres avertissements.

## Installation
1. Installer NeoForge 21.1+ pour Minecraft 1.21.1.
2. Placer `arcadia-lib-1.2.0.jar` dans le dossier `mods/`.
3. Placer `arcadia-admin-panel-1.2.0.jar` dans le dossier `mods/`.
4. (Optionnel) Configurer MySQL dans `config/arcadia-database.toml` pour la synchronisation multi-serveur.

## Credits
Author: vyrriox
