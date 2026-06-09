# Arcadia Admin Panel

[Consult the full CurseForge description](./CURSEFORGE_PAGE.md)

Arcadia Admin Panel is a NeoForge Minecraft mod that gives server staff a complete, themed GUI for moderation. View every player (online + offline), warn / jail / mute / ban from a single chest interface, browse FTB Teams parties with member rosters and live FTB Chunks claim counts, throttle post-restart login bursts, and gate every action behind granular permission nodes. Designed for the **Arcadia: Echoes of Power** server but works on any heavy modpack that needs serious moderation tools.

## Features

- **Player Panel** — Paginated grid of every player on the server, online and offline. Offline names are resolved from multiple sources (usercache + FTB Teams cached names) so heads show real pseudos, never raw UUIDs, and render the players' **real skins** (resolved server-side, online-mode). Real-time client-side search bar filters by name. Click a head to open the player detail menu.
- **Player Detail Menu** — One screen for everything: jail/unjail, mute/unmute, kick, ban/unban, clear inventory, invsee, reset progress, teleport to/here, switch **game mode**, **heal/feed**, pin a one-shot **next-login spawn** (debug teleport), view homes, view teleport history, view warns. Skull lore surfaces last login / last logout / first seen timestamps.
- **Next-Login Spawn** — Pin a debug spawn point to any player (online or offline); on their next connection they spawn there instead of their normal position, then the override is consumed. Set it from the GUI (stand where you want them, click) or via command. Jail always takes priority. Persists across restarts.
- **Warning System** — Add, list, delete, and bulk-clear warns. Configurable auto-expiry (default 180 days). Multi-server sync via shared MySQL (Arcadia Lib `DatabaseManager`); falls back to local JSON when the database is disabled. Players see their active warns on join with time-until-expiry per warn and a clickable `/checkwarn` link.
- **Offline Warning** — `/arcadia_adminpanel warnoffline <name> <reason>` works whether the target is connected or not. Offline targets get notified at their next login.
- **Jail System** — Per-server jail location with multi-server sync. Players are bounced back to the jail by a 3-layer anti-glitch system: `EntityTeleportEvent` cancel for ender pearls and chorus fruit, right-click intercept for waystone/warp/teleport-named items + blocks, and a periodic proximity sweep that re-teleports anyone who drifted outside the configurable radius. On release, players are teleported back to their pre-jail position.
- **Jail Baton** — Custom 32×32 textured staff tool. Right-click a player to jail them for 30 minutes; right-click an already-jailed player to release them. Staff-gated, immune to self-target and other staff.
- **FTB Teams Browser** — List every party + server team (and, via a toggle, per-player personal teams) with member count + claim count + force-loaded chunk count. Member counts include the team owner. Click a member to open their detail panel or right-click to teleport to their last-seen position. Parses `<world>/ftbteams/*.snbt` directly — no runtime dependency on the FTB Teams mod, and discovery is independent of FTB Essentials.
- **FTB Chunks Integration** — Per-team total claims and force-loaded chunks surfaced in the GUI, parsed from `<world>/ftbchunks/<team-uuid>.snbt`.
- **Login Queue** — Optional connection throttle (off by default). Token-bucket rolling window holds excess players in the negotiation phase — no slot, no chunk loads — until their turn. Saves heavy modpacks from post-reboot TPS death.
- **Name Tags — Hide-behind-walls + Colours & Effects** — Server-authoritative floating-name control. **Hide behind walls** (ON by default): a player's name is suppressed client-side when a solid block sits between the observer's camera and that player (a line-of-sight raytrace), so you can't read who is hiding behind a wall; transparent blocks (glass/leaves) don't occlude unless configured, and any player can be made permanently visible (`exempt`). **Styling**: named colours, true RGB, static multi-stop gradients, ten animated effects (**solid, gradient, rainbow, breathing, chase, wave, blink, fade, typewriter, random**), the five text decorations (bold/italic/underline/strikethrough/obfuscated) and an animation speed. State persists to `nametags.json` and syncs to clients on join; effects animate off a client tick. Two nodes: `arcadia.adminpanel.nametag` and `arcadia.adminpanel.nametag.hide`.
- **Granular Permissions** — One LuckPerms node per action (`arcadia.adminpanel.warn.view`, `.warn.edit`, `.kick`, `.ban`, `.mute`, `.jail`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.teams`, `.reload`, `.setjail`, `.loginqueue`, `.announce`, `.nextspawn`, `.gamemode`, `.heal`, `.nametag`, `.nametag.hide`, `.open`). Buttons the viewer can't use are hidden from the GUI entirely. **To grant panel access, the simplest single node is `arcadia.hub.adminpanel`** — it is the node Arcadia Lib's dashboard uses to show the card, and as of 1.2.5 it also opens the panel (so one grant both reveals and opens it). OP level ≥ 2 short-circuits everything; legacy `arcadia.staff.mod` still grants full access for backwards compatibility.
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
| `checkwarn` | `arcadia.adminpanel.open` | View your own warns (read-only) |
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
| `announce <title>[\| <subtitle>]` | `arcadia.adminpanel.announce` | Server-wide title + subtitle + chime |
| `setnextspawn <player>` | `arcadia.adminpanel.nextspawn` | Pin a one-shot next-login spawn to your position |
| `clearnextspawn <player>` | `arcadia.adminpanel.nextspawn` | Clear a pending next-login spawn |
| `nextspawnlist` | `arcadia.adminpanel.nextspawn` | List pending next-login spawns |
| `jailradius [blocks]` | `arcadia.adminpanel.setjail` | Show/set the jail-zone radius + enable anti-escape |
| `loginqueue [on\|off]` | `arcadia.adminpanel.loginqueue` | Show/toggle the post-reboot login throttle at runtime |
| `nametag color <player> <named>` | `arcadia.adminpanel.nametag` | Solid named colour on a player's name |
| `nametag rgb <player> <#hex>` | `arcadia.adminpanel.nametag` | True 24-bit RGB colour |
| `nametag gradient <player> <#hex> <#hex> [#hex] [#hex]` | `arcadia.adminpanel.nametag` | Static multi-stop gradient |
| `nametag effect <player> <effect>` | `arcadia.adminpanel.nametag` | Animated effect: solid/gradient/rainbow/breathing/chase/wave/blink/fade/typewriter/random |
| `nametag style <player> <flag> <on\|off>` | `arcadia.adminpanel.nametag` | Toggle bold/italic/underline/strikethrough/obfuscated |
| `nametag speed <player> <1-10>` | `arcadia.adminpanel.nametag` | Animation speed |
| `nametag reset <player>` | `arcadia.adminpanel.nametag` | Clear all name styling |
| `nametag show <player>` | `arcadia.adminpanel.nametag` | Print a player's current styling |
| `nametag exempt <player>` | `arcadia.adminpanel.nametag.hide` | Toggle a player's exemption from hiding (always visible) |
| `nametag hide [on\|off]` | `arcadia.adminpanel.nametag.hide` | Show/toggle the global hide-names-behind-walls switch |

### GUI-only permission nodes

These actions are reachable only from the in-game panel (no slash command). Every button is gated
twice — hidden when the viewer lacks the node, and re-checked in the click handler so a forged packet
cannot trigger it.

| Node | Unlocks |
|---|---|
| `arcadia.adminpanel.teams` | FTB Teams browser (team list → team detail → member TP) |
| `arcadia.adminpanel.teleport` | Teleport to/here, homes, teleport history, team-member last-seen |
| `arcadia.adminpanel.invsee` | View a player's inventory |
| `arcadia.adminpanel.clearinv` | Clear a player's inventory |
| `arcadia.adminpanel.resetprogress` | Revoke all advancements |
| `arcadia.adminpanel.gamemode` | Cycle a player's game mode |
| `arcadia.adminpanel.heal` | Heal / feed a player |
| `arcadia.adminpanel.kick` | Kick a player |
| `arcadia.adminpanel.ban` | Ban / unban a player |
| `arcadia.adminpanel.info` | Open a player's info sheet (ban/whitelist/login history) |

### LuckPerms quick-start

OP level ≥ 2 implicitly grants every node, so vanilla admins need no config. For granular staff roles,
grant these node sets (or `arcadia.adminpanel.*` for full access). On a dedicated server the permission
backend **fails closed** — without LuckPerms (or another backend) bound, only OPs pass.

```yaml
# Moderator — day-to-day moderation
arcadia.adminpanel.open
arcadia.adminpanel.info
arcadia.adminpanel.warn.view
arcadia.adminpanel.warn.edit
arcadia.adminpanel.kick
arcadia.adminpanel.mute          # also needs the MOD staff grade
arcadia.adminpanel.jail
arcadia.adminpanel.teleport

# Admin — everything (or simply grant arcadia.adminpanel.*)
arcadia.adminpanel.*

# Dashboard card visibility (lib-side; lets the panel card appear and open)
arcadia.hub.adminpanel
```

> `arcadia.hub.adminpanel` is what Arcadia Lib's dashboard uses to show the panel card; it also opens
> the panel. The legacy `arcadia.staff.mod` node is still accepted for backward compatibility.

## Requirements

| Dependency | Version |
|------------|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219+ |
| Java | 21 |
| Arcadia Lib | ≥ 1.2.14 |
| FTB Essentials | optional (unlocks homes / last-seen) |
| FTB Teams | optional (unlocks the team browser) |
| FTB Chunks | optional (unlocks claim counters) |
| LuckPerms | optional (granular permission backend) |

## Installation

1. Install [Arcadia Lib](https://github.com/Team-Arcadia/Arcadia-Lib) ≥ 1.2.14 in your `mods/` folder
2. Place `arcadia-admin-panel-1.2.6.jar` in your `mods/` folder
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
License: LGPL-3.0-or-later — see [LICENSE](LICENSE). Forks and derivative works are welcome under the same license, provided you credit "vyrriox / Team Arcadia" and link back to the upstream repository.
Discord: [discord.gg/xjF8Rtzyd4](https://discord.gg/xjF8Rtzyd4)
Website: [arcadia-echoes-of-power.fr](https://arcadia-echoes-of-power.fr/)

---

# Arcadia Admin Panel (Version Française)

[Consulter la description CurseForge complète](./CURSEFORGE_PAGE.md)

Arcadia Admin Panel est un mod NeoForge pour Minecraft qui offre au staff serveur un GUI complet et thématisé pour la modération. Affichez tous les joueurs (en ligne + hors ligne), warn / jail / mute / ban depuis une seule interface coffre, parcourez les parties FTB Teams avec rosters et compteurs FTB Chunks en direct, throttlez les bursts de connexion post-reboot, et gatez chaque action derrière des nodes de permission granulaires. Conçu pour le serveur **Arcadia: Echoes of Power** mais fonctionne sur n'importe quel modpack lourd qui a besoin de vrais outils de modération.

## Caractéristiques

- **Panneau Joueur** — Grille paginée de tous les joueurs du serveur, en ligne et hors ligne. Les noms hors ligne sont résolus depuis plusieurs sources (usercache + noms mis en cache par FTB Teams) pour afficher les vrais pseudos, jamais des UUID bruts, et rendent les **vraies têtes (skins)** des joueurs (résolues côté serveur, mode online). Barre de recherche client-side temps réel pour filtrer par nom. Cliquez sur une tête pour ouvrir le menu de détail.
- **Menu Détail Joueur** — Un seul écran pour tout : jail/unjail, mute/unmute, kick, ban/unban, vider inventaire, invsee, reset progression, téléporter vers/ici, changer de **mode de jeu**, **soigner/nourrir**, épingler un **spawn de prochaine connexion** à usage unique (téléport de debug), voir homes, voir historique TP, voir warns. Le lore du crâne affiche dernière connexion / dernière déconnexion / première fois vu.
- **Spawn de Prochaine Connexion** — Épingle un point de spawn de debug sur n'importe quel joueur (en ligne ou hors ligne) ; à sa prochaine connexion il apparaît là au lieu de sa position normale, puis l'override est consommé. À définir depuis le GUI (placez-vous où vous voulez, cliquez) ou via commande. La prison est toujours prioritaire. Persiste après redémarrage.
- **Système d'Avertissement** — Ajouter, lister, supprimer, et vider en masse les warns. Expiration auto configurable (défaut 180 jours). Sync multi-serveur via MySQL partagée (Arcadia Lib `DatabaseManager`) ; fallback JSON local si la base est désactivée. Les joueurs voient leurs warns actifs à la connexion avec le temps avant expiration de chaque warn et un lien cliquable `/checkwarn`.
- **Warn Hors Ligne** — `/arcadia_adminpanel warnoffline <nom> <raison>` fonctionne que la cible soit connectée ou non. Les cibles offline sont notifiées à leur prochaine connexion.
- **Système de Prison** — Position de prison par serveur avec sync multi-serveur. Les joueurs sont renvoyés en prison par un système anti-glitch 3 couches : annulation `EntityTeleportEvent` pour perles + chorus, intercept du clic droit pour items + blocs nommés waystone/warp/teleport, et balayage périodique de proximité qui re-téléporte quiconque a dérivé hors du rayon configurable. À la libération, les joueurs sont téléportés à leur position d'avant-jail.
- **Matraque de Prison** — Outil staff custom avec texture 32×32. Clic droit sur un joueur pour le jail 30 min ; clic droit sur un joueur déjà en prison pour le libérer. Staff uniquement, immunité auto-target et autres staff.
- **Navigateur FTB Teams** — Liste toutes les parties + teams serveur (et, via une bascule, les teams personnelles par joueur) avec compteur de membres + compteur de claims + chunks force-loaded. Le compteur de membres inclut le propriétaire de la team. Clic sur un membre pour ouvrir son panneau détail ou clic droit pour téléporter à sa dernière position. Parse `<world>/ftbteams/*.snbt` directement — aucune dépendance d'exécution sur le mod FTB Teams, et la découverte est indépendante de FTB Essentials.
- **Intégration FTB Chunks** — Total des claims et chunks force-loaded par team affiché dans le GUI, parsé depuis `<world>/ftbchunks/<team-uuid>.snbt`.
- **File d'Attente Connexion** — Throttle de connexion optionnel (désactivé par défaut). Fenêtre glissante token-bucket maintient l'excès de joueurs en phase de négociation — pas de slot, pas de chargement de chunks — jusqu'à leur tour. Sauve les modpacks lourds de la mort TPS post-reboot.
- **Pseudos — Masquage derrière les murs + Couleurs & Effets** — Contrôle du pseudo flottant, autoritaire côté serveur. **Masquage derrière les murs** (activé par défaut) : le pseudo d'un joueur est supprimé côté client quand un bloc plein se trouve entre la caméra de l'observateur et ce joueur (raytrace de ligne de vue) — impossible de lire qui se cache derrière un mur ; les blocs transparents (verre/feuilles) ne masquent pas sauf configuration, et tout joueur peut être rendu toujours visible (`exempt`). **Stylisation** : couleurs nommées, vraie RGB, dégradés multi-couleurs figés, dix effets animés (**solid, gradient, rainbow, breathing, chase, wave, blink, fade, typewriter, random**), les cinq décorations de texte (gras/italique/souligné/barré/obfusqué) et une vitesse d'animation. L'état persiste dans `nametags.json` et est synchronisé aux clients à la connexion ; les effets s'animent via un tick client. Deux nodes : `arcadia.adminpanel.nametag` et `arcadia.adminpanel.nametag.hide`.
- **Permissions Granulaires** — Un node LuckPerms par action (`arcadia.adminpanel.warn.view`, `.warn.edit`, `.kick`, `.ban`, `.mute`, `.jail`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.teams`, `.reload`, `.setjail`, `.loginqueue`, `.announce`, `.nextspawn`, `.gamemode`, `.heal`, `.nametag`, `.nametag.hide`, `.open`). Les boutons que le viewer ne peut pas utiliser sont entièrement cachés du GUI. **Pour accorder l'accès au panel, le node unique le plus simple est `arcadia.hub.adminpanel`** — c'est le node qu'utilise le dashboard d'Arcadia Lib pour afficher la carte, et depuis la 1.2.5 il ouvre aussi le panel (un seul grant révèle ET ouvre). OP level ≥ 2 court-circuite tout ; le legacy `arcadia.staff.mod` accorde toujours l'accès complet pour rétrocompatibilité.
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
| `checkwarn` | `arcadia.adminpanel.open` | Voir ses propres warns (lecture seule) |
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
| `announce <titre>[\| <sous-titre>]` | `arcadia.adminpanel.announce` | Title + sous-titre + son pour tout le serveur |
| `setnextspawn <joueur>` | `arcadia.adminpanel.nextspawn` | Épingler un spawn de prochaine connexion à votre position |
| `clearnextspawn <joueur>` | `arcadia.adminpanel.nextspawn` | Annuler un spawn de prochaine connexion en attente |
| `nextspawnlist` | `arcadia.adminpanel.nextspawn` | Lister les spawns de prochaine connexion en attente |
| `jailradius [blocs]` | `arcadia.adminpanel.setjail` | Afficher/régler le rayon de la zone de prison + activer l'anti-évasion |
| `loginqueue [on\|off]` | `arcadia.adminpanel.loginqueue` | Afficher/basculer la file d'attente de connexion à chaud |
| `nametag color <joueur> <nom>` | `arcadia.adminpanel.nametag` | Couleur nommée unie sur le pseudo |
| `nametag rgb <joueur> <#hex>` | `arcadia.adminpanel.nametag` | Vraie couleur RGB 24 bits |
| `nametag gradient <joueur> <#hex> <#hex> [#hex] [#hex]` | `arcadia.adminpanel.nametag` | Dégradé multi-couleurs figé |
| `nametag effect <joueur> <effet>` | `arcadia.adminpanel.nametag` | Effet animé : solid/gradient/rainbow/breathing/chase/wave/blink/fade/typewriter/random |
| `nametag style <joueur> <option> <on\|off>` | `arcadia.adminpanel.nametag` | Basculer gras/italique/souligné/barré/obfusqué |
| `nametag speed <joueur> <1-10>` | `arcadia.adminpanel.nametag` | Vitesse d'animation |
| `nametag reset <joueur>` | `arcadia.adminpanel.nametag` | Réinitialiser tout le style du pseudo |
| `nametag show <joueur>` | `arcadia.adminpanel.nametag` | Afficher le style actuel d'un joueur |
| `nametag exempt <joueur>` | `arcadia.adminpanel.nametag.hide` | Basculer l'exemption de masquage d'un joueur (toujours visible) |
| `nametag hide [on\|off]` | `arcadia.adminpanel.nametag.hide` | Afficher/basculer le masquage global des pseudos derrière les murs |

### Nodes de permission réservés au GUI

Ces actions ne sont accessibles que depuis le panneau en jeu (pas de commande). Chaque bouton est
gardé deux fois — masqué si le joueur n'a pas le node, et re-vérifié dans le gestionnaire de clic pour
qu'un paquet forgé ne puisse pas le déclencher.

| Node | Débloque |
|---|---|
| `arcadia.adminpanel.teams` | Navigateur FTB Teams (liste → détail → TP membre) |
| `arcadia.adminpanel.teleport` | TP vers/ici, homes, historique de téléportation, dernière position d'un membre |
| `arcadia.adminpanel.invsee` | Voir l'inventaire d'un joueur |
| `arcadia.adminpanel.clearinv` | Vider l'inventaire d'un joueur |
| `arcadia.adminpanel.resetprogress` | Révoquer tous les advancements |
| `arcadia.adminpanel.gamemode` | Changer le mode de jeu d'un joueur |
| `arcadia.adminpanel.heal` | Soigner / nourrir un joueur |
| `arcadia.adminpanel.kick` | Expulser un joueur |
| `arcadia.adminpanel.ban` | Bannir / débannir un joueur |
| `arcadia.adminpanel.info` | Ouvrir la fiche d'info (ban/whitelist/historique de connexion) |

### Démarrage rapide LuckPerms

Un niveau OP ≥ 2 accorde implicitement tous les nodes — les admins vanilla n'ont rien à configurer.
Pour des rôles staff granulaires, accordez ces ensembles (ou `arcadia.adminpanel.*` pour tout). Sur un
serveur dédié, le backend de permission **échoue fermé** : sans LuckPerms (ou autre backend) lié, seuls
les OP passent.

```yaml
# Modérateur — modération courante
arcadia.adminpanel.open
arcadia.adminpanel.info
arcadia.adminpanel.warn.view
arcadia.adminpanel.warn.edit
arcadia.adminpanel.kick
arcadia.adminpanel.mute          # nécessite aussi le grade staff MOD
arcadia.adminpanel.jail
arcadia.adminpanel.teleport

# Admin — tout (ou simplement arcadia.adminpanel.*)
arcadia.adminpanel.*

# Visibilité de la carte dashboard (côté lib ; affiche et ouvre le panneau)
arcadia.hub.adminpanel
```

> `arcadia.hub.adminpanel` est le node utilisé par le dashboard d'Arcadia Lib pour afficher la carte du
> panneau ; il l'ouvre aussi. L'ancien node `arcadia.staff.mod` reste accepté pour la compatibilité.

## Prérequis

| Dépendance | Version |
|------------|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219+ |
| Java | 21 |
| Arcadia Lib | ≥ 1.2.14 |
| FTB Essentials | optionnel (débloque homes / dernière position) |
| FTB Teams | optionnel (débloque le navigateur de teams) |
| FTB Chunks | optionnel (débloque les compteurs de claims) |
| LuckPerms | optionnel (backend de permissions granulaire) |

## Installation

1. Installez [Arcadia Lib](https://github.com/Team-Arcadia/Arcadia-Lib) ≥ 1.2.14 dans votre dossier `mods/`
2. Placez `arcadia-admin-panel-1.2.6.jar` dans votre dossier `mods/`
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
Licence : LGPL-3.0-or-later — voir [LICENSE](LICENSE). Les forks et travaux dérivés sont les bienvenus sous la même licence, à condition de créditer « vyrriox / Team Arcadia » et de pointer vers le dépôt d'origine.
Discord : [discord.gg/xjF8Rtzyd4](https://discord.gg/xjF8Rtzyd4)
Site web : [arcadia-echoes-of-power.fr](https://arcadia-echoes-of-power.fr/)
