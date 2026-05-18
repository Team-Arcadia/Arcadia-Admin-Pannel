# Arcadia Admin Panel

[Consult the full CurseForge description](./CURSEFORGE_PAGE.md)

Arcadia Admin Panel is a NeoForge Minecraft mod that gives server staff a complete, themed GUI for moderation. View every player (online + offline), warn / jail / mute / ban from a single chest interface, browse FTB Teams parties with member rosters and live FTB Chunks claim counts, throttle post-restart login bursts, and gate every action behind granular permission nodes. Designed for the **Arcadia: Echoes of Power** server but works on any heavy modpack that needs serious moderation tools.

## Features

- **Player Panel** — Paginated grid of every player on the server, online and offline (resolved from FTB Essentials player data). Real-time client-side search bar filters by name. Click a head to open the player detail menu.
- **Player Detail Menu** — One screen for everything: jail/unjail, mute/unmute, kick, ban/unban, clear inventory, invsee, reset progress, teleport to/here, view homes, view teleport history, view warns. Skull lore surfaces last login / last logout / first seen timestamps.
- **Warning System** — Add, list, delete, and bulk-clear warns. Configurable auto-expiry (default 180 days). Multi-server sync via shared MySQL (Arcadia Lib `DatabaseManager`); falls back to local JSON when the database is disabled. Players see their active warns on join with time-until-expiry per warn and a clickable `/checkwarn` link.
- **Offline Warning** — `/arcadia_adminpanel warnoffline <name> <reason>` works whether the target is connected or not. Offline targets get notified at their next login.
- **Jail System** — Per-server jail location with multi-server sync. Players are bounced back to the jail by a 3-layer anti-glitch system: `EntityTeleportEvent` cancel for ender pearls and chorus fruit, right-click intercept for waystone/warp/teleport-named items + blocks, and a periodic proximity sweep that re-teleports anyone who drifted outside the configurable radius. On release, players are teleported back to their pre-jail position.
- **Jail Baton** — Custom 32×32 textured staff tool. Right-click a player to jail them for 30 minutes; right-click an already-jailed player to release them. Staff-gated, immune to self-target and other staff.
- **FTB Teams Browser** — List every party + server team with member count + claim count + force-loaded chunk count. Click a member to open their detail panel or right-click to teleport to their last-seen position. Parses `<world>/ftbteams/*.snbt` directly — no runtime dependency on the FTB Teams mod.
- **FTB Chunks Integration** — Per-team total claims and force-loaded chunks surfaced in the GUI, parsed from `<world>/ftbchunks/<team-uuid>.snbt`.
- **Login Queue** — Optional connection throttle (off by default). Token-bucket rolling window holds excess players in the negotiation phase — no slot, no chunk loads — until their turn. Saves heavy modpacks from post-reboot TPS death.
- **Granular Permissions** — One LuckPerms node per action (`arcadia.adminpanel.warn.view`, `.warn.edit`, `.kick`, `.ban`, `.mute`, `.jail`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.teams`, `.reload`, `.setjail`, `.loginqueue`, `.open`). Buttons the viewer can't use are hidden from the GUI entirely. OP level ≥ 2 short-circuits everything; legacy `arcadia.staff.mod` still grants full access for backwards compatibility.
- **Bilingual UI** — English and French lang files, automatic locale detection.

## Commands

All commands use the prefix `/arcadia_adminpanel`.

| Command | Permission node | Description |
|---|---|---|
| `panel [filter]` | `arcadia.adminpanel.open` | Open the admin panel (optional name filter) |
| `reload` | `arcadia.adminpanel.reload` | Reload caches, config, FTB data, warns |
| `warn <targets> <reason>` | `arcadia.adminpanel.warn.edit` | Warn online players (entity selector) |
| `warnoffline <name> <reason>` | `arcadia.adminpanel.warn.edit` | Warn an online or offline player by name |
| `warnlist <player>` | `arcadia.adminpanel.warn.view` | Open the warn list GUI |
| `delwarn <player> <index>` | `arcadia.adminpanel.warn.edit` | Delete a specific warn |
| `clearwarns <player>` | `arcadia.adminpanel.warn.edit` | Clear every warn for a player |
| `checkwarn` | none | View your own warns |
| `mute <player> <minutes> [reason]` | `arcadia.adminpanel.mute` + Staff MOD+ | Mute |
| `unmute <player>` | `arcadia.adminpanel.mute` + Staff MOD+ | Unmute |
| `staffchat <message>` | Staff HELPER+ | Send message to the staff channel |
| `stafftoggle` | Staff HELPER+ | Toggle staff chat mode |
| `stafflist` | Staff HELPER+ | List online staff |
| `setjail` | `arcadia.adminpanel.setjail` | Set the jail location to your position |
| `jail <player> <minutes> [reason]` | `arcadia.adminpanel.jail` | Jail a player (`0` = permanent) |
| `unjail <player>` | `arcadia.adminpanel.jail` | Release a jailed player |
| `jaillist` | `arcadia.adminpanel.jail` | List currently-jailed players |
| `givebaton` | `arcadia.adminpanel.jail` | Add a Jail Baton to your inventory |

## Requirements

| Dependency | Version |
|------------|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219+ |
| Java | 21 |
| Arcadia Lib | ≥ 1.2.9 |
| FTB Essentials | optional (unlocks homes / last-seen) |
| FTB Teams | optional (unlocks the team browser) |
| FTB Chunks | optional (unlocks claim counters) |
| LuckPerms | optional (granular permission backend) |

## Installation

1. Install [Arcadia Lib](https://github.com/Team-Arcadia/Arcadia-Lib) ≥ 1.2.9 in your `mods/` folder
2. Place `arcadia-admin-panel-1.2.4.jar` in your `mods/` folder
3. (Optional) Install FTB Essentials, FTB Teams, FTB Chunks for the full feature set
4. (Optional) Install LuckPerms and grant the `arcadia.adminpanel.*` nodes to the groups you want
5. Start the server

### Client Installation (Optional)
Installing on the client enables the steampunk-themed ArcadiaTheme rendering and the live search bar. The mod works without client installation (vanilla chest UI fallback).

## Configuration

All tunables live in `config/arcadia/arcadiaadminpanel/config.json` (auto-created on first run):

| Key | Default | Description |
|---|---|---|
| `warnExpiryDays` | `180` | Auto-delete warns older than N days. `0` disables. |
| `warnNotifyOnJoin` | `true` | Send active-warn summary in chat when a player joins. |
| `loginQueueEnabled` | `false` | Master switch for the post-reboot connection throttle. |
| `loginQueueMaxPerWindow` | `4` | Concurrent logins allowed per window. |
| `loginQueueWindowSeconds` | `10` | Window size in seconds. |
| `loginQueueMaxWaitMs` | `60000` | Hard cap on per-player queue wait. |
| `jailEnforceProximity` | `true` | Run the periodic proximity sweep for jailed players. |
| `jailProximityRadius` | `32` | Radius (blocks) from jail point before re-teleport. |
| `jailEnforceTickInterval` | `20` | Sweep interval in ticks (20 = 1 s). |

### Multi-Server Sync
Configure MySQL in `config/arcadia/lib/database.toml` and set `enabled = true`. All servers sharing the same database will sync warns + jails automatically. Each server identifies itself via the JVM property `-Darcadia.server_id=server1`.

## Documentation

- [CHANGELOG.md](CHANGELOG.md) — Version history with per-version test procedures
- [RULES.md](RULES.md) — Project conventions, architecture, and AI assistant guidelines
- [CONTRIBUTING.md](.github/CONTRIBUTING.md) — Contribution guide
- [SECURITY.md](.github/SECURITY.md) — Security policy

## Credits

Author: vyrriox
Organization: Team Arcadia
License: All Rights Reserved — see [LICENSE](LICENSE). The source is published for transparency and audit; redistribution, commercial use, and derivative works require explicit written permission from the author.
Discord: [discord.gg/xjF8Rtzyd4](https://discord.gg/xjF8Rtzyd4)
Website: [arcadia-echoes-of-power.fr](https://arcadia-echoes-of-power.fr/)

---

# Arcadia Admin Panel (Version Française)

[Consulter la description CurseForge complète](./CURSEFORGE_PAGE.md)

Arcadia Admin Panel est un mod NeoForge pour Minecraft qui offre au staff serveur un GUI complet et thématisé pour la modération. Affichez tous les joueurs (en ligne + hors ligne), warn / jail / mute / ban depuis une seule interface coffre, parcourez les parties FTB Teams avec rosters et compteurs FTB Chunks en direct, throttlez les bursts de connexion post-reboot, et gatez chaque action derrière des nodes de permission granulaires. Conçu pour le serveur **Arcadia: Echoes of Power** mais fonctionne sur n'importe quel modpack lourd qui a besoin de vrais outils de modération.

## Caractéristiques

- **Panneau Joueur** — Grille paginée de tous les joueurs du serveur, en ligne et hors ligne (résolus via les données FTB Essentials). Barre de recherche client-side temps réel pour filtrer par nom. Cliquez sur une tête pour ouvrir le menu de détail.
- **Menu Détail Joueur** — Un seul écran pour tout : jail/unjail, mute/unmute, kick, ban/unban, vider inventaire, invsee, reset progression, téléporter vers/ici, voir homes, voir historique TP, voir warns. Le lore du crâne affiche dernière connexion / dernière déconnexion / première fois vu.
- **Système d'Avertissement** — Ajouter, lister, supprimer, et vider en masse les warns. Expiration auto configurable (défaut 180 jours). Sync multi-serveur via MySQL partagée (Arcadia Lib `DatabaseManager`) ; fallback JSON local si la base est désactivée. Les joueurs voient leurs warns actifs à la connexion avec le temps avant expiration de chaque warn et un lien cliquable `/checkwarn`.
- **Warn Hors Ligne** — `/arcadia_adminpanel warnoffline <nom> <raison>` fonctionne que la cible soit connectée ou non. Les cibles offline sont notifiées à leur prochaine connexion.
- **Système de Prison** — Position de prison par serveur avec sync multi-serveur. Les joueurs sont renvoyés en prison par un système anti-glitch 3 couches : annulation `EntityTeleportEvent` pour perles + chorus, intercept du clic droit pour items + blocs nommés waystone/warp/teleport, et balayage périodique de proximité qui re-téléporte quiconque a dérivé hors du rayon configurable. À la libération, les joueurs sont téléportés à leur position d'avant-jail.
- **Matraque de Prison** — Outil staff custom avec texture 32×32. Clic droit sur un joueur pour le jail 30 min ; clic droit sur un joueur déjà en prison pour le libérer. Staff uniquement, immunité auto-target et autres staff.
- **Navigateur FTB Teams** — Liste toutes les parties + teams serveur avec compteur de membres + compteur de claims + chunks force-loaded. Clic sur un membre pour ouvrir son panneau détail ou clic droit pour téléporter à sa dernière position. Parse `<world>/ftbteams/*.snbt` directement — aucune dépendance d'exécution sur le mod FTB Teams.
- **Intégration FTB Chunks** — Total des claims et chunks force-loaded par team affiché dans le GUI, parsé depuis `<world>/ftbchunks/<team-uuid>.snbt`.
- **File d'Attente Connexion** — Throttle de connexion optionnel (désactivé par défaut). Fenêtre glissante token-bucket maintient l'excès de joueurs en phase de négociation — pas de slot, pas de chargement de chunks — jusqu'à leur tour. Sauve les modpacks lourds de la mort TPS post-reboot.
- **Permissions Granulaires** — Un node LuckPerms par action (`arcadia.adminpanel.warn.view`, `.warn.edit`, `.kick`, `.ban`, `.mute`, `.jail`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.teams`, `.reload`, `.setjail`, `.loginqueue`, `.open`). Les boutons que le viewer ne peut pas utiliser sont entièrement cachés du GUI. OP level ≥ 2 court-circuite tout ; le legacy `arcadia.staff.mod` accorde toujours l'accès complet pour rétrocompatibilité.
- **Interface Bilingue** — Fichiers de langue anglais et français, détection automatique de la locale.

## Commandes

Toutes les commandes utilisent le préfixe `/arcadia_adminpanel`.

| Commande | Permission | Description |
|---|---|---|
| `panel [filtre]` | `arcadia.adminpanel.open` | Ouvrir le panneau admin (filtre nom optionnel) |
| `reload` | `arcadia.adminpanel.reload` | Recharger caches, config, données FTB, warns |
| `warn <cibles> <raison>` | `arcadia.adminpanel.warn.edit` | Warn des joueurs en ligne (sélecteur d'entité) |
| `warnoffline <nom> <raison>` | `arcadia.adminpanel.warn.edit` | Warn un joueur en ligne ou hors ligne par nom |
| `warnlist <joueur>` | `arcadia.adminpanel.warn.view` | Ouvrir le GUI de liste des warns |
| `delwarn <joueur> <index>` | `arcadia.adminpanel.warn.edit` | Supprimer un warn spécifique |
| `clearwarns <joueur>` | `arcadia.adminpanel.warn.edit` | Vider tous les warns d'un joueur |
| `checkwarn` | aucune | Voir ses propres warns |
| `mute <joueur> <minutes> [raison]` | `arcadia.adminpanel.mute` + Staff MOD+ | Mute |
| `unmute <joueur>` | `arcadia.adminpanel.mute` + Staff MOD+ | Unmute |
| `staffchat <message>` | Staff HELPER+ | Envoyer un message au canal staff |
| `stafftoggle` | Staff HELPER+ | Basculer le mode chat staff |
| `stafflist` | Staff HELPER+ | Lister le staff en ligne |
| `setjail` | `arcadia.adminpanel.setjail` | Définir la position de la prison sur votre position |
| `jail <joueur> <minutes> [raison]` | `arcadia.adminpanel.jail` | Emprisonner (`0` = permanent) |
| `unjail <joueur>` | `arcadia.adminpanel.jail` | Libérer un joueur emprisonné |
| `jaillist` | `arcadia.adminpanel.jail` | Lister les joueurs en prison |
| `givebaton` | `arcadia.adminpanel.jail` | Ajouter une Matraque de Prison à votre inventaire |

## Prérequis

| Dépendance | Version |
|------------|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219+ |
| Java | 21 |
| Arcadia Lib | ≥ 1.2.9 |
| FTB Essentials | optionnel (débloque homes / dernière position) |
| FTB Teams | optionnel (débloque le navigateur de teams) |
| FTB Chunks | optionnel (débloque les compteurs de claims) |
| LuckPerms | optionnel (backend de permissions granulaire) |

## Installation

1. Installez [Arcadia Lib](https://github.com/Team-Arcadia/Arcadia-Lib) ≥ 1.2.9 dans votre dossier `mods/`
2. Placez `arcadia-admin-panel-1.2.4.jar` dans votre dossier `mods/`
3. (Optionnel) Installez FTB Essentials, FTB Teams, FTB Chunks pour toutes les fonctionnalités
4. (Optionnel) Installez LuckPerms et accordez les nodes `arcadia.adminpanel.*` aux groupes voulus
5. Démarrez le serveur

### Installation Client (Optionnel)
Installer côté client active le rendu ArcadiaTheme steampunk et la barre de recherche en direct. Le mod fonctionne sans installation client (UI coffre vanilla en repli).

## Documentation

- [CHANGELOG.md](CHANGELOG.md) — Historique des versions avec procédures de test
- [RULES.md](RULES.md) — Conventions du projet, architecture, et règles pour les assistants IA
- [CONTRIBUTING.md](.github/CONTRIBUTING.md) — Guide de contribution
- [SECURITY.md](.github/SECURITY.md) — Politique de sécurité

## Credits

Auteur : vyrriox
Organisation : Team Arcadia
Licence : Tous Droits Réservés — voir [LICENSE](LICENSE). Le code source est publié pour la transparence et l'audit ; la redistribution, l'usage commercial et les travaux dérivés requièrent une autorisation écrite explicite de l'auteur.
Discord : [discord.gg/xjF8Rtzyd4](https://discord.gg/xjF8Rtzyd4)
Site web : [arcadia-echoes-of-power.fr](https://arcadia-echoes-of-power.fr/)
