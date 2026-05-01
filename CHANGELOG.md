# Changelog

All notable changes to Arcadia Admin Panel are documented here.

---

## [1.2.3] - 2026-05-01 (latest)

### Fixed

- **Admin Panel reachable by any player when LuckPerms wasn't bound (carousel + L-key hub)** — Two paths exposed the admin GUI to non-staff players on servers where LuckPerms was missing or hadn't initialized. (1) The dashboard carousel arrows in any sub-menu (cosmetics, pets, daily, AH) routed players through `executeServerAction("adminpanel:open")`, which gated on `PermissionService.hasPermission("arcadia.staff.mod")` — but with no real perm backend that call returns `true` for everyone via the NOOP fallback. (2) The L-key Arcadia Hub screen always rendered the Admin Panel card because client-side filtering had no synced view of the server's permission state. Two-front fix (paired with arcadia-lib 1.2.9): the registered `adminpanel:open` action now requires either vanilla OP level &gt;= 2 OR `arcadia.staff.mod` via the new strict perm check (which fails closed when LuckPerms isn't bound), and the hub card is hidden client-side via `S2CHubPermissions` synced at login. Slash command `/arcadia_adminpanel panel` was already correctly gated on `hasPermission(2)` and is unaffected. Bumps lib dependency 1.2.6 → 1.2.9.

### Correctifs

- **Admin Panel accessible à tous quand LuckPerms n'était pas branché (carousel + hub touche L)** — Deux chemins exposaient le GUI admin aux joueurs non staff sur les serveurs où LuckPerms manquait ou n'était pas initialisé. (1) Les flèches du carousel dans n'importe quel sous-menu (cosmétiques, pets, daily, HDV) routaient les joueurs via `executeServerAction("adminpanel:open")`, qui filtrait sur `PermissionService.hasPermission("arcadia.staff.mod")` — mais sans vrai backend de perms cet appel renvoie `true` pour tout le monde via le fallback NOOP. (2) L'écran Arcadia Hub (touche L) affichait toujours la carte Admin Panel car le filtrage côté client n'avait aucune vue synchronisée de l'état des permissions du serveur. Correction sur deux fronts (couplée à arcadia-lib 1.2.9) : l'action `adminpanel:open` exige maintenant soit OP level &gt;= 2 vanilla SOIT `arcadia.staff.mod` via le nouveau check strict (qui échoue en fermé quand LuckPerms n'est pas branché), et la carte du hub est cachée côté client via `S2CHubPermissions` synchronisé à la connexion. La commande slash `/arcadia_adminpanel panel` était déjà correctement filtrée sur `hasPermission(2)` et n'est pas affectée. Bump dépendance lib 1.2.6 → 1.2.9.

---

## [1.2.2] - 2026-04-28

### Fixed

- **`adminpanel:open` server action now re-checks `arcadia.staff.mod`** — Defense-in-depth fix paired with arcadia-prestige 1.2.4 / arcadia-lib 1.2.6. Even if a caller (e.g. carousel navigation arrows in an older Prestige, or a third-party mod) reaches the registered server action without first checking the card permission, the action itself now re-validates `arcadia.staff.mod` before opening `AdminPanelMenu`. Closes the bypass where unauthorized players could reach the admin GUI by spamming the dashboard's prev/next arrows.

### Correctifs

- **Le server action `adminpanel:open` revérifie désormais `arcadia.staff.mod`** — Correction defense-in-depth couplée à arcadia-prestige 1.2.4 / arcadia-lib 1.2.6. Même si un appelant (ex. flèches de navigation du carousel d'un Prestige plus ancien, ou un mod tiers) atteint le server action enregistré sans vérifier d'abord la permission de la carte, l'action elle-même revalide maintenant `arcadia.staff.mod` avant d'ouvrir `AdminPanelMenu`. Ferme le bypass qui permettait aux joueurs non autorisés d'atteindre le GUI admin en spammant les flèches prev/next du dashboard.

---

## [1.2.1] - 2026-04-23

### Added

- **Jail returns players to their pre-jail location on release** — Jailing a player now captures their dimension, x/y/z and yaw/pitch before the teleport to the jail point. On manual unjail, on expiry of a timed jail, or on auto-expiry at server restart, the player is teleported back to where they were when the admin jailed them (falls back to overworld if the original dimension is gone). Stored per-entry in JSON and MySQL (`prev_dimension`, `prev_x`, `prev_y`, `prev_z`, `prev_yaw`, `prev_pitch`). Schema auto-migrates — the new columns are added to existing `arcadia_admin_jail` tables on first start of 1.2.1, entries created before 1.2.1 simply have no recorded location and release silently as before.

### Ajouts

- **Le jail ramène les joueurs à leur position d'avant-jail à la libération** — Jail un joueur capture désormais sa dimension, x/y/z et yaw/pitch avant le téléport vers le point de jail. Au unjail manuel, à l'expiration d'un jail temporaire, ou à l'auto-expiration au redémarrage du serveur, le joueur est téléporté à l'endroit où il était au moment du jail (repli sur l'overworld si la dimension originale a disparu). Stocké par entrée en JSON et MySQL (`prev_dimension`, `prev_x`, `prev_y`, `prev_z`, `prev_yaw`, `prev_pitch`). Migration auto du schéma — les nouvelles colonnes sont ajoutées aux tables `arcadia_admin_jail` existantes au premier démarrage de 1.2.1, les entrées créées avant 1.2.1 n'ont simplement aucune position enregistrée et libèrent silencieusement comme avant.

---

## [1.2.0] - 2026-04-11

### Added
- **Arcadia Lib Integration** — Mod is now powered by Arcadia Lib. Uses ArcadiaTheme (steampunk copper design), ItemBuilder, ArcadiaMessages, SoundHelper, MessageHelper, and ArcadiaModRegistry.
- **Both-Sided Mod** — Now runs on both client and server (was server-only). Client gets themed GUI screens with ArcadiaTheme rendering.
- **Player Search Bar** — Client-side search bar in the admin panel to filter players by name in real-time. Also supports chat-based search and `/arcadia_adminpanel panel <filter>` command.
- **Multi-Server Warn Sync** — Warnings now synchronize across servers via shared MySQL database (Arcadia Lib DatabaseManager). Falls back to local JSON when database is disabled.
- **Custom Menu Types** — Registered custom NeoForge MenuTypes for AdminPanel, PlayerDetail, and WarnList screens.
- **New Command** — `/arcadia_adminpanel clearwarns <player>` to clear all warnings for a player at once.
- **Warn Server Origin** — Each warning now stores the server ID it was issued from (visible in warn list).
- **Cancel Sessions** — Warn and search chat sessions can be cancelled by typing 'cancel'.

### Changed
- **Commands Renamed** — All commands moved from `/arcadiaadmin` to `/arcadia_adminpanel` for consistency with Arcadia ecosystem.
- **Pre-Filled Suggestions** — All player argument commands now suggest both online and offline players automatically.
- **Mod ID Fixed** — Changed from `arcadiaadminpannel` (typo) to `arcadiaadminpanel`.
- **NeoForge Updated** — Upgraded from NeoForge 21.1.1 to 21.1.219 with Parchment mappings.
- **pack_format Updated** — Updated from legacy Forge format 9 to NeoForge format 15.

### Performance
- **Async Database Operations** — All database writes (insert, delete, clear warns) run on dedicated thread pool via DatabaseManager.
- **Optimized Menus** — Menus use ItemBuilder (fluent API) instead of manual DataComponents manipulation.

### Removed
- **PlayerDataCache** — Removed unused class (was dead code).
- **FTBHelper** — Removed legacy reflection-based FTB integration (FTBDataReader handles all data reading).

### Ajouts
- **Intégration Arcadia Lib** — Le mod utilise désormais Arcadia Lib. Thème steampunk cuivré (ArcadiaTheme), ItemBuilder, ArcadiaMessages, SoundHelper, MessageHelper et ArcadiaModRegistry.
- **Mod Both-Sided** — Fonctionne désormais côté client et serveur (était serveur uniquement). Le client obtient des écrans GUI thématisés.
- **Barre de Recherche** — Barre de recherche côté client pour filtrer les joueurs par nom en temps réel. Supporte aussi la recherche via chat et commande `/arcadia_adminpanel panel <filtre>`.
- **Synchronisation Multi-Serveur des Warns** — Les avertissements se synchronisent entre serveurs via MySQL partagée. Fallback JSON local si base désactivée.
- **Types de Menu Personnalisés** — MenuTypes NeoForge personnalisés pour AdminPanel, PlayerDetail et WarnList.
- **Nouvelle Commande** — `/arcadia_adminpanel clearwarns <joueur>` pour supprimer tous les avertissements d'un joueur.
- **Origine Serveur des Warns** — Chaque avertissement stocke l'ID du serveur d'origine.
- **Annulation Sessions** — Les sessions warn et recherche sont annulables en tapant 'cancel'.

### Modifications
- **Commandes Renommées** — Toutes les commandes passent de `/arcadiaadmin` à `/arcadia_adminpanel` pour cohérence Arcadia.
- **Suggestions Pré-Remplies** — Toutes les commandes à argument joueur suggèrent automatiquement les joueurs en ligne et hors ligne.
- **Mod ID Corrigé** — Passage de `arcadiaadminpannel` (typo) à `arcadiaadminpanel`.
- **NeoForge Mis à Jour** — Passage de NeoForge 21.1.1 à 21.1.219 avec mappings Parchment.

### Performance
- **Opérations Base de Données Asynchrones** — Toutes les écritures DB tournent sur un pool de threads dédié.
- **Menus Optimisés** — Les menus utilisent ItemBuilder (API fluent) au lieu de manipulation manuelle des DataComponents.

### Suppressions
- **PlayerDataCache** — Classe inutilisée supprimée.
- **FTBHelper** — Intégration FTB par réflexion legacy supprimée (FTBDataReader gère tout).

---

## [1.1.4] - 2026-02-04
### Fixed
- **InvSee**: Fixed an issue where the inventory menu would close immediately after opening. The Admin Panel now closes *before* opening the target inventory.
- **Repository**: Cleaned up unnecessary files and updated `.gitignore`.

### Corrigé
- **InvSee**: Correction d'un bug où le menu se fermait immédiatement. Le Panel Admin se ferme maintenant *avant* l'ouverture de l'inventaire.
- **Dépôt**: Nettoyage des fichiers inutiles et mise à jour de `.gitignore`.

## [1.1.3] - 2026-02-03
### Added
- **New Admin Actions**: Added buttons in the Admin Panel for quick management.
    - **InvSee** (Chest): Opens the player's inventory (Requires `/invsee` command from another mod/plugin).
    - **Clear Inventory** (Lava Bucket): Clears the player's inventory. Includes a safety confirmation (Click once to arm, twice to confirm). Requires `/clear`.
    - **Reset Progress** (XP Bottle): Revokes all advancements (`/advancement revoke <player> everything`).
- **Translations**: Added English and French translations for the new buttons and messages.

### Ajouté
- **Nouvelles Actions Admin**: Ajout de boutons dans le Panel Admin pour une gestion rapide.
    - **InvSee** (Coffre): Ouvre l'inventaire du joueur (Requiert la commande `/invsee` d'un autre mod/plugin).
    - **Vider Inventaire** (Seau de Lave): Vide l'inventaire du joueur. Inclut une confirmation de sécurité (Clic pour armer, re-clic pour confirmer). Requiert `/clear`.
    - **Reset Progression** (Fiole d'XP): Révoque tous les progrès (`/advancement revoke <joueur> everything`).
- **Traductions**: Ajout des traductions Anglais/Français pour les nouveaux boutons.

## [1.1.2] - 2026-02-03
### Added
- **Permission-Based GUI**: Action buttons in the Player Detail Menu (Kick, Ban, TP, Warn) are now hidden if the admin lacks the permission to use the corresponding command.
    - Checks `minecraft.command.kick`, `minecraft.command.ban`, `minecraft.command.tp`, and `arcadiaadmin`.
    - Compatible with LuckPerms and vanilla permission levels.

### Français
- **GUI Basé sur Permissions**: Les boutons d'action (Kick, Ban, TP, Warn) sont masqués si l'admin n'a pas la permission requise.
    - Vérifie `minecraft.command.kick`, `minecraft.command.ban`, `minecraft.command.tp`, et `arcadiaadmin`.
    - Compatible avec LuckPerms et les niveaux de permission vanilla.

## [1.1.1] - 2026-02-03

### 🇺🇸 English
#### Added
- **Dynamic World Support**: Now reads `level-name` from `server.properties` to find FTB Essentials data automatically.
- **Improved Warning System**:
    - New command `/arcadiaadmin checkwarn` for players to view their own warnings.
    - New command `/arcadiaadmin delwarn <player> <index>` for admins to delete specific warnings.
    - Added Warning Title ("WARNING") and Subtitle (Reason) displayed to the target player.
    - Added Sound Effect (`ANVIL_LAND`) when a player is warned.
- **Optimization**:
    - **Anti-Corruption**: Atomic file writes for `warns.json` (server crash protection).
    - **Anti-Lag**: Thread-safe collections for warning operations to prevent server thread blocking.
- **Command Structure**: Consolidated all commands under `/arcadiaadmin` (panel, warn, checkwarn, delwarn).

#### Changed
- **Refactor**: Renamed packages from `com.jimmy` to `com.vyrriox`.
- **Build**: Updated group id to `com.arcadia.adminpanel`.

---

### 🇫🇷 Français
#### Ajouté
- **Support Monde Dynamique**: Lit désormais `level-name` dans `server.properties` pour trouver automatiquement les données FTB Essentials.
- **Système d'Avertissement Amélioré**:
    - Nouvelle commande `/arcadiaadmin checkwarn` pour que les joueurs voient leurs propres avertissements.
    - Nouvelle commande `/arcadiaadmin delwarn <joueur> <index>` pour que les admins suppriment des avertissements spécifiques.
    - Ajout d'un Titre ("ATTENTION") et Sous-titre (Raison) affichés au joueur averti.
    - Ajout d'un Effet Sonore (`ANVIL_LAND`) lors d'un avertissement.
- **Optimisation**:
    - **Anti-Corruption**: Écriture atomique pour `warns.json` (protection crash serveur).
    - **Anti-Lag**: Collections thread-safe pour éviter de bloquer le thread serveur.
- **Structure des Commandes**: Regroupement de toutes les commandes sous `/arcadiaadmin`.

#### Changé
- **Refonte**: Refactorisation des noms de package de `com.jimmy` à `com.vyrriox`.
- **Build**: Mise à jour du group id vers `com.arcadia.adminpanel`.

______________________________________________________________________

## [1.1.0] - 2026-01-20

### 🇺🇸 English
#### Added
- **Warning System**:
    - Added `/warn <player> <reason>` command to log warnings.
    - Added `/warnlist <player>` to view player warnings.
    - Warnings are saved in JSON format.
- **Language Support**: Native support for English and French.

#### Changed
- **GUI**: Improved Admin Panel layout.

---

### 🇫🇷 Français
#### Ajouté
- **Système d'Avertissement**:
    - Ajout de la commande `/warn <joueur> <raison>`.
    - Ajout de la commande `/warnlist <joueur>`.
    - Sauvegarde JSON des avertissements.
- **Langues**: Support natif Anglais et Français.

#### Changé
- **GUI**: Amélioration de l'interface Admin Panel.

______________________________________________________________________

## [1.0.0] - 2026-01-15

### 🇺🇸 English
#### Added
- **Initial Release**:
    - Admin Panel GUI (`/adminpanel`).
    - Player Management (Online/Offline players).
    - Teleport History tracking.
    - Fast Teleportation tools.
    - Offline Inventory Viewing.
    - Integration with FTB Essentials player data.

---

### 🇫🇷 Français
#### Ajouté
- **Sortie Initiale**:
    - Interface Admin Panel (`/adminpanel`).
    - Gestion Joueurs (En ligne/Hors ligne).
    - Historique de téléportation.
    - Outils de téléportation rapide.
    - Vue d'inventaire hors ligne.
    - Intégration données FTB Essentials.
