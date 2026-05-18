# Changelog

All notable changes to Arcadia Admin Panel are documented here.

---

## [1.2.4] - 2026-05-18 (latest)

### Added (second pass)

- **Granular per-action permissions** — Reworked the permission model. Each button has its own LuckPerms node (`arcadia.adminpanel.open`, `.warn.view`, `.warn.edit`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.kick`, `.ban`, `.mute`, `.jail`, `.setjail`, `.reload`, `.teams`, `.loginqueue`). Buttons the viewer doesn't have are hidden from the GUI entirely. Slash commands check the same nodes via a shared `require()` predicate. OP level >= 2 short-circuits everything (vanilla admins keep full access). Legacy `arcadia.staff.mod` is still honoured as a fallback so existing groups don't lose access on upgrade. Results cached for 2 s per-player so a single menu rebuild only hits the perm backend once.
- **FTB Chunks claim & force-load count** — When FTB Chunks is installed, the team detail menu header, the team list, and the per-player "View Team" button surface the team's total claim count and force-loaded chunk count (with the team's max if set). Parser reads `<world>/ftbchunks/<team-uuid>.snbt` directly — no runtime dependency on the FTB Chunks mod. 30 s cache.
- **Configurable warn TTL with on-join notification** — New `config/arcadia/arcadiaadminpanel/config.json` with `warnExpiryDays` (default 180 = ~6 months, `0` disables). On startup and on `/reload`, warns older than the TTL are physically deleted from the active backend. On player join, the player receives a chat summary of their currently-active warns (top 5) with the time-until-expiry for each. Configurable: `warnNotifyOnJoin = true/false`.
- **Login queue (connection throttle)** — Off by default. When enabled, throttles concurrent logins via `PlayerNegotiationEvent` so a post-restart connection burst doesn't melt heavy modpacks. Token-bucket rolling window: `loginQueueMaxPerWindow` logins per `loginQueueWindowSeconds`. Queued players stay in the "Connecting…" state without holding a player slot or triggering chunk loads. Cap on queue wait (`loginQueueMaxWaitMs`, default 60 s) — beyond that we admit anyway rather than time them out.
- **Jail anti-glitch** — Three independent enforcement layers protect jailed players from escape. (1) `EntityTeleportEvent` cancel for ender pearls, chorus fruit, /tp, and any mod that goes through the standard teleport pipeline — destinations outside the jail proximity radius are denied. (2) Right-click intercept for items/blocks whose registry ID contains "waystone"/"warp"/"teleport"/"portal"/"return_stone" — catches mod teleport gear that bypasses (1). (3) Periodic proximity sweep (default every 1 s) that re-teleports any jailed player whose position drifted outside the configurable radius (default 32 blocks) or whose dimension changed. Catches everything (1) and (2) miss. All three are tunable in `config.json`.

### Fixed (second pass)

- **Cross-server jail sync on reconnect** — Player jailed on server A, disconnects, reconnects on server B: B was loading its jail cache only at startup and didn't know about the new row. `PlayerLoggedInEvent` now does an async per-player DB lookup before teleporting, so jails created on other servers propagate immediately. Conversely, if the row was deleted on another server (unjail) between disconnect and reconnect, B clears its stale cache entry. JSON-mode (single-server) is unaffected.
- **Admin couldn't unjail themselves** — When a staff member jailed themselves (or got jailed by another admin as a prank), the command-block filter rejected `/arcadia_adminpanel unjail` and there was no way out short of a DB edit or restart. Staff (anyone passing the `canOpenAdminPanel` check) now bypass the jail command filter.
- **Search bar was unreadable when filtering** — The previous filter painted a 0xCC overlay on top of every non-matching player head, leaving the names + textures partially visible underneath and the tooltip floating over the dimmed slot. The whole row of darkened heads was illegible. The new filter completely skips rendering for non-matching slots (`renderSlot` returns early, painting a flat dark fill), suppresses the tooltip for those slots, and ignores clicks. Only matching heads remain visible.

### Ajouts (second pass)

- **Permissions granulaires par action** — Refonte du modèle de permissions. Chaque bouton a son propre node LuckPerms (`arcadia.adminpanel.open`, `.warn.view`, `.warn.edit`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.kick`, `.ban`, `.mute`, `.jail`, `.setjail`, `.reload`, `.teams`, `.loginqueue`). Les boutons que le viewer ne possède pas sont entièrement masqués du GUI. Les commandes slash vérifient les mêmes nodes via un prédicat `require()` partagé. OP level >= 2 court-circuite tout (les admins vanilla gardent l'accès complet). Le legacy `arcadia.staff.mod` reste honoré en fallback pour ne pas casser les groupes existants. Résultats cachés 2 s par joueur pour qu'une seule construction de menu ne tape qu'une fois sur le backend de perms.
- **Compteur de claims FTB Chunks et chunks force-loaded** — Quand FTB Chunks est installé, l'en-tête du menu de détail team, la liste des teams, et le bouton "Voir la Team" par joueur affichent le total de claims de la team et le nombre de chunks force-loaded (avec le max de la team si défini). Le parser lit `<world>/ftbchunks/<team-uuid>.snbt` directement — pas de dépendance d'exécution sur le mod FTB Chunks. Cache 30 s.
- **TTL des warns configurable avec notification à la connexion** — Nouveau `config/arcadia/arcadiaadminpanel/config.json` avec `warnExpiryDays` (défaut 180 ≈ 6 mois, `0` désactive). Au démarrage et sur `/reload`, les warns plus vieux que le TTL sont supprimés physiquement du backend actif. À la connexion d'un joueur, il reçoit un résumé chat de ses warns actuellement actifs (top 5) avec le temps avant expiration. Configurable : `warnNotifyOnJoin = true/false`.
- **File d'attente de connexion (throttle)** — Désactivée par défaut. Quand activée, throttle les connexions concurrentes via `PlayerNegotiationEvent` pour qu'un burst de reconnexions post-reboot ne fasse pas fondre les modpacks lourds. Token-bucket fenêtre glissante : `loginQueueMaxPerWindow` connexions par `loginQueueWindowSeconds`. Les joueurs en file restent en état "Connexion en cours…" sans occuper de slot joueur ni déclencher de chargement de chunks. Cap sur l'attente (`loginQueueMaxWaitMs`, défaut 60 s) — au-delà on les admet quand même plutôt que de les timeout.
- **Anti-glitch prison** — Trois couches d'application indépendantes protègent les joueurs en prison contre l'évasion. (1) Annulation d'`EntityTeleportEvent` pour les perles de l'ender, les chorus, /tp, et tout mod passant par le pipeline standard de téléportation — les destinations hors du rayon de proximité de la prison sont refusées. (2) Intercept du clic droit pour les items/blocs dont l'ID registry contient "waystone"/"warp"/"teleport"/"portal"/"return_stone" — attrape les items de téléport des mods qui contournent (1). (3) Balayage de proximité périodique (défaut toutes les 1 s) qui re-téléporte tout joueur emprisonné dont la position a dérivé hors du rayon configurable (défaut 32 blocs) ou dont la dimension a changé. Attrape tout ce que (1) et (2) loupent. Les trois sont configurables dans `config.json`.

### Correctifs (second pass)

- **Synchronisation cross-serveur du jail à la reconnexion** — Joueur emprisonné sur serveur A, se déconnecte, se reconnecte sur serveur B : B ne chargeait son cache jail qu'au démarrage et ne connaissait pas la nouvelle ligne. `PlayerLoggedInEvent` fait maintenant un lookup async DB par joueur avant le téléport, donc les jails créés sur d'autres serveurs se propagent immédiatement. Inversement, si la ligne a été supprimée sur un autre serveur (unjail) entre la déconnexion et la reconnexion, B nettoie son entrée de cache obsolète. Le mode JSON (mono-serveur) n'est pas affecté.
- **Un admin ne pouvait pas se unjail lui-même** — Quand un membre du staff se jailait (ou se faisait jail par un autre admin pour rigoler), le filtre de commande rejetait `/arcadia_adminpanel unjail` et il n'y avait aucune issue à part une édition DB ou un restart. Le staff (toute personne passant le check `canOpenAdminPanel`) bypass maintenant le filtre de commande du jail.
- **La barre de recherche était illisible quand on filtrait** — Le filtre précédent peignait une overlay 0xCC sur chaque tête de joueur non-correspondante, laissant les noms + textures partiellement visibles dessous et le tooltip flottant au-dessus du slot assombri. Toute la rangée de têtes assombries était illisible. Le nouveau filtre saute complètement le rendu des slots non-correspondants (`renderSlot` retourne tôt en peignant un fill sombre plat), supprime le tooltip pour ces slots, et ignore les clics. Seules les têtes correspondantes restent visibles.

### Added

- **Last login / last logout / first seen tracking** — New `LoginTracker` records the epoch timestamp of every connect and disconnect to `config/arcadia/arcadiaadminpanel/logins.json`. Surfaced in the player detail GUI (skull lore) and the chat info dump. First-seen timestamps are backfilled from the `.snbt` file creation time during the offline-player scan so existing playerbases get reasonable values without waiting for everyone to reconnect. FTB Essentials' `last_seen.time` is kept as a separate "last position" indicator — it tracks teleport events, not login events, so it cannot answer "when did this player last connect?".
- **FTB Teams browser** — New `Browse Teams` button on the main admin panel (visible only if `<world>/ftbteams` exists). Opens a paginated list of all parties + server teams; click a team to see its members (sorted owner → officer → member → ally), their rank, and their last-seen position. Left-click a member opens the standard player detail panel; right-click teleports the admin to that member's last-seen position. Parses FTB Teams' SNBT files directly so the feature works without a runtime dependency on the FTB Teams mod. Also surfaces a per-player "View Team" button on the player detail panel for players that belong to a team.
- **Programmatic kick/ban path** — Kick and ban now use the vanilla `PlayerList` / `UserBanList` APIs directly instead of building a `kick <name> <reason>` / `ban <name> <reason>` command string. Same outcome, but no command-string concatenation involving a player-controlled name.

### Fixed

- **FTB Essentials data directory not found on certain server layouts** — `OfflinePlayerManager.findFTBDataDirectory` used `Path.endsWith("ftbessentials/playerdata")` which on Windows compares against the OS path separator and silently never matches, so the recursive-walk fallback was a no-op. Players reporting `[ArcadiaAdmin] Could not find FTB Essentials data directory!` on otherwise-valid FTB installs hit this. The new lookup (1) asks the running server for its world path via `server.getWorldPath(LevelResource.ROOT)` and resolves `ftbessentials/playerdata` underneath it (authoritative), (2) falls back to a candidate list including `./`, `./world`, `./Arcadia_World`, and whatever `level-name` is set to in `server.properties`, then (3) does a bounded depth-4 walk that compares path *segments* (correct on every OS).
- **Permission re-check on every menu click** — Defense in depth (1.2.2/1.2.3 hardened the open paths). Every `clicked()` handler in `AdminPanelMenu`, `PlayerDetailMenu`, `TeamListMenu`, `TeamDetailMenu`, and `WarnListMenu` now re-validates `arcadia.staff.mod` (strict) or OP level >= 2 before processing the slot. Closes the theoretical packet-injection path where a crafted `Container Click` packet could trigger sensitive actions (ban/kick/clear/tp/jail) on an already-open container after the permission state had changed.
- **Command-injection hardening in player detail menu** — All `performPrefixedCommand` calls that previously concatenated `targetName` (invsee, clear, advancement revoke) now gate on a strict identifier check (`[a-zA-Z0-9_]{1,16}`). TP-self/TP-here and kick/ban no longer route through the command dispatcher at all — they call `ServerPlayer.teleportTo`, `Connection.disconnect`, and `UserBanList.add` directly. Mitigates argument injection if an offline-mode server has a player named e.g. `"alice everyone"`.
- **`SimpleDateFormat` race condition in warn list** — `WarnListMenu` shared a single `SimpleDateFormat` static field across threads. `SimpleDateFormat` is not thread-safe; concurrent admins opening warn lists could see garbled dates or trip an `ArrayIndexOutOfBoundsException` inside the formatter. Replaced with a `DateTimeFormatter` (thread-safe by contract) using `Instant.ofEpochMilli`.
- **Jail expiry race (double-release / double-teleport-back)** — `JailManager.isJailed` did a non-atomic get-then-remove on the `ConcurrentHashMap`, and the scheduled expiry lambda re-read the same key. Concurrent invocations could both observe an expired entry, both fire the DB delete, the "released" chat message, and the teleport-back. `isJailed` now uses `computeIfPresent` for atomic expiry eviction; the scheduled lambda uses `remove(key, value)` so side effects only fire for the exact entry it scheduled (also fixes the case where a player is re-jailed before the original timer fires). `getJailEntry` no longer re-enters `isJailed`, removing a separate TOCTOU.
- **`WarnManager.removeWarn` non-atomic remove** — The sort + index-lookup + `warns.remove(toRemove)` sequence was partially synchronized; a concurrent `addWarn` between the snapshot and the remove could shift indices and delete the wrong entry. Whole block is now inside one `synchronized (warns)` region.
- **`WarnListMenu` non-staff bypass** — The delete-warn handler gated only on `!sp.getUUID().equals(targetUUID)`, meaning any non-target player who could reach this menu was implicitly allowed to delete warns. Now requires the strict staff check; the self-view path (`/arcadia_adminpanel checkwarn`) remains read-only as intended.
- **`.snbt` filename filter compared full path on Windows** — Both `OfflinePlayerManager.scanDirectory` and `FTBTeamsReader.getTeams` used `Path.toString().endsWith(".snbt")`, which matches if any parent directory in the absolute path contains the suffix. Switched to `getFileName().toString().endsWith(".snbt")`.

### Performance

- **Daemon thread for offline scan** — `Arcadia-OfflineScan` is now a daemon thread; in pathological scan cases it can no longer hold up server shutdown.
- **30-second cached FTB Teams reads** — `FTBTeamsReader` mirrors `FTBDataReader`'s caching strategy so the new Teams GUI doesn't hit disk on every menu redraw or pagination click.

### Ajouts

- **Suivi dernière connexion / dernière déconnexion / première fois vu** — Nouveau `LoginTracker` enregistre l'horodatage epoch de chaque connexion et déconnexion dans `config/arcadia/arcadiaadminpanel/logins.json`. Affiché dans le GUI de détail joueur (lore du crâne) et dans le dump info chat. Les horodatages "première fois vu" sont récupérés depuis la date de création du fichier `.snbt` pendant le scan offline, donc les bases joueurs existantes obtiennent des valeurs raisonnables sans attendre que tout le monde se reconnecte. Le `last_seen.time` de FTB Essentials est gardé comme indicateur séparé "dernière position" — il suit les téléportations, pas les connexions, donc il ne peut pas répondre à "quand ce joueur s'est-il connecté la dernière fois ?".
- **Navigateur FTB Teams** — Nouveau bouton `Parcourir les Teams` sur le panneau admin principal (visible uniquement si `<world>/ftbteams` existe). Ouvre une liste paginée de toutes les parties + teams serveur ; cliquez sur une team pour voir ses membres (triés owner → officer → member → ally), leur rang, et leur dernière position connue. Clic gauche sur un membre ouvre le panneau de détail joueur standard ; clic droit téléporte l'admin à la dernière position connue du membre. Parse les fichiers SNBT de FTB Teams directement, donc la fonctionnalité fonctionne sans dépendance d'exécution sur le mod FTB Teams. Affiche également un bouton "Voir la Team" par joueur sur le panneau de détail joueur pour les joueurs qui appartiennent à une team.
- **Chemin kick/ban programmatique** — Kick et ban utilisent maintenant directement les APIs vanilla `PlayerList` / `UserBanList` au lieu de construire une chaîne de commande `kick <nom> <raison>` / `ban <nom> <raison>`. Même résultat, mais pas de concaténation de chaîne de commande impliquant un nom contrôlé par le joueur.

### Correctifs

- **Dossier de données FTB Essentials non trouvé sur certains layouts serveur** — `OfflinePlayerManager.findFTBDataDirectory` utilisait `Path.endsWith("ftbessentials/playerdata")` qui sur Windows compare au séparateur de chemin OS et silencieusement ne matche jamais, donc le fallback en walk récursif était inopérant. Les joueurs signalant `[ArcadiaAdmin] Could not find FTB Essentials data directory!` sur des installs FTB par ailleurs valides touchaient ce bug. La nouvelle recherche (1) demande au serveur en cours son chemin de monde via `server.getWorldPath(LevelResource.ROOT)` et résout `ftbessentials/playerdata` dessous (autoritatif), (2) retombe sur une liste de candidats incluant `./`, `./world`, `./Arcadia_World`, et la valeur `level-name` de `server.properties`, puis (3) fait un walk borné en profondeur 4 qui compare les *segments* du chemin (correct sur tous les OS).
- **Revérification des permissions à chaque clic du menu** — Defense in depth (1.2.2/1.2.3 ont durci les chemins d'ouverture). Chaque handler `clicked()` dans `AdminPanelMenu`, `PlayerDetailMenu`, `TeamListMenu`, `TeamDetailMenu`, et `WarnListMenu` revalide maintenant `arcadia.staff.mod` (strict) ou OP level >= 2 avant de traiter le slot. Ferme le chemin théorique d'injection de paquet où un `Container Click` packet forgé pouvait déclencher des actions sensibles (ban/kick/clear/tp/jail) sur un container déjà ouvert après changement d'état de permission.
- **Durcissement contre l'injection de commande dans le menu détail joueur** — Tous les appels `performPrefixedCommand` qui concaténaient précédemment `targetName` (invsee, clear, advancement revoke) sont maintenant gated par un check strict d'identifiant (`[a-zA-Z0-9_]{1,16}`). TP-self/TP-here et kick/ban ne passent plus par le command dispatcher du tout — ils appellent directement `ServerPlayer.teleportTo`, `Connection.disconnect`, et `UserBanList.add`. Atténue l'injection d'arguments si un serveur offline-mode a un joueur nommé par exemple `"alice everyone"`.
- **Race condition `SimpleDateFormat` dans la liste de warns** — `WarnListMenu` partageait un seul champ statique `SimpleDateFormat` entre les threads. `SimpleDateFormat` n'est pas thread-safe ; des admins concurrents ouvrant des listes de warns pouvaient voir des dates corrompues ou déclencher un `ArrayIndexOutOfBoundsException` dans le formatter. Remplacé par un `DateTimeFormatter` (thread-safe par contrat) avec `Instant.ofEpochMilli`.
- **Race d'expiration de jail (double-release / double-téléport-retour)** — `JailManager.isJailed` faisait un get-then-remove non atomique sur la `ConcurrentHashMap`, et le lambda d'expiration planifié relisait la même clé. Des invocations concurrentes pouvaient toutes deux observer une entrée expirée, déclencher chacune le delete DB, le message chat "released", et le téléport retour. `isJailed` utilise maintenant `computeIfPresent` pour l'éviction atomique d'expiration ; le lambda planifié utilise `remove(key, value)` pour que les effets de bord ne se déclenchent que pour l'entrée exacte qu'il a planifiée (corrige aussi le cas où un joueur est ré-jail avant le déclenchement du timer original). `getJailEntry` ne réentre plus dans `isJailed`, supprimant un TOCTOU séparé.
- **`WarnManager.removeWarn` remove non atomique** — La séquence sort + index-lookup + `warns.remove(toRemove)` était partiellement synchronisée ; un `addWarn` concurrent entre le snapshot et le remove pouvait décaler les indices et supprimer la mauvaise entrée. Tout le bloc est maintenant dans une seule région `synchronized (warns)`.
- **Bypass non-staff dans `WarnListMenu`** — Le handler de suppression de warn ne gardait que sur `!sp.getUUID().equals(targetUUID)`, signifiant qu'aucun joueur non-cible qui pouvait atteindre ce menu n'était implicitement autorisé à supprimer des warns. Nécessite maintenant le check staff strict ; le chemin self-view (`/arcadia_adminpanel checkwarn`) reste lecture seule comme prévu.
- **Filtre `.snbt` comparé au chemin complet sur Windows** — `OfflinePlayerManager.scanDirectory` et `FTBTeamsReader.getTeams` utilisaient `Path.toString().endsWith(".snbt")`, qui matche si n'importe quel dossier parent du chemin absolu contient le suffixe. Basculé sur `getFileName().toString().endsWith(".snbt")`.

### Performance

- **Thread daemon pour le scan offline** — `Arcadia-OfflineScan` est maintenant un thread daemon ; dans les cas pathologiques de scan il ne peut plus retenir l'arrêt du serveur.
- **Lectures FTB Teams cachées 30 secondes** — `FTBTeamsReader` reflète la stratégie de cache de `FTBDataReader` pour que le nouveau GUI Teams ne touche pas le disque à chaque redraw de menu ou clic de pagination.

---

## [1.2.3] - 2026-05-01

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
