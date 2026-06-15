# Changelog

All notable changes to Arcadia Admin Panel are documented here.

---

## [1.2.8] - 2026-06-15 (latest)

### Fixed

- **"Couldn't place player in world" after reloading a single-player world** — `LoginTracker` is a static singleton whose coalescing IO executor was created once in its constructor and terminated by `shutdown()` on `ServerStopping`. On an integrated (single-player) server the singleton outlives the world, so loading a second world in the same client session reused the dead executor: the first `recordLogin` called `io.schedule(...)` on a terminated pool, threw `RejectedExecutionException`, and aborted the player-placement step — surfacing as the vanilla *"Couldn't place player in world"* disconnect. The IO executor is now (re)created on every `init()` (`ServerStarted`) and the reference is dropped after `shutdown()`, so each world session gets a live pool. `markDirty()` additionally falls back to a synchronous write if the pool is ever absent or shutting down, so a login can never throw or lose its write.

### Correctifs

- **« Couldn't place player in world » après le rechargement d'un monde solo** — `LoginTracker` est un singleton statique dont l'exécuteur d'écriture coalescé était créé une seule fois dans le constructeur puis arrêté par `shutdown()` au `ServerStopping`. Sur un serveur intégré (solo), le singleton survit au monde : recharger un second monde dans la même session client réutilisait l'exécuteur mort — le premier `recordLogin` appelait `io.schedule(...)` sur un pool terminé, levait `RejectedExecutionException` et interrompait le placement du joueur, ce qui se manifestait par la déconnexion vanilla *« Couldn't place player in world »*. L'exécuteur d'écriture est désormais (re)créé à chaque `init()` (`ServerStarted`) et la référence est libérée après `shutdown()`, de sorte que chaque session de monde dispose d'un pool actif. `markDirty()` bascule en plus sur une écriture synchrone si le pool est absent ou en cours d'arrêt, afin qu'une connexion ne puisse jamais lever d'exception ni perdre son écriture.

---

## [1.2.7] - 2026-06-14

### Added

- **Custom display name (pseudo) per player** — `/arcadia_adminpanel nametag name <player> <pseudo…>` overrides the text shown for a player on their floating head-tag **and** in the TAB player list. The pseudo accepts spaces (greedy argument), is capped at 32 characters, and inherits the same colour/effect styling as the real name. `nametag name <player> reset` (or `clear`) removes the override and restores the real name. The chat and command-targeting names are intentionally left untouched, so `/msg`, bans, and teleports keep working on the real username.
- **Grade visibility toggle** — `/arcadia_adminpanel nametag grade <player> <on|off>` shows or hides the player's grade (the scoreboard-team prefix/suffix that LuckPerms & similar plugins set) next to their name, on both the floating tag and the TAB list. Defaults to ON.

### Fixed

- **Grade disappeared when a name-tag style was applied** — Styling a player's name rebuilt the floating tag from the bare player name with `event.setContent`, which discarded the team prefix/suffix and made the player's grade vanish next to their pseudo. The renderer now re-attaches the scoreboard-team prefix and suffix around the styled name (reading them straight off the player's team, exactly as vanilla composes the display name), so the grade stays put unless explicitly hidden with `nametag grade off`.

### Changed

- **Name-tag styling now reflects in the TAB list** — Previously colours/effects and the (new) custom pseudo only showed on the floating head-tag. A server-side `TabListNameFormat` handler now composes the same `grade + styled name` for the TAB player list, refreshed live after every `nametag` mutation via `ServerPlayer.refreshTabListName()` (no relog needed). The TAB list is a static snapshot, so animated effects appear with their colours but do not animate there.

### Ajouts

- **Pseudo personnalisé par joueur** — `/arcadia_adminpanel nametag name <joueur> <pseudo…>` remplace le texte affiché pour un joueur sur son pseudo flottant **et** dans la liste TAB. Le pseudo accepte les espaces (argument glouton), est limité à 32 caractères et hérite de la même stylisation couleur/effet que le vrai pseudo. `nametag name <joueur> reset` (ou `clear`) retire le remplacement et restaure le vrai pseudo. Les noms du chat et du ciblage de commandes sont volontairement laissés intacts, donc `/msg`, bans et téléportations continuent de fonctionner sur le vrai pseudo.
- **Bascule d'affichage du grade** — `/arcadia_adminpanel nametag grade <joueur> <on|off>` affiche ou masque le grade du joueur (le préfixe/suffixe de team scoreboard que posent LuckPerms et plugins similaires) à côté de son pseudo, à la fois sur le pseudo flottant et dans la liste TAB. Activé par défaut.

### Correctifs

- **Le grade disparaissait quand un style de pseudo était appliqué** — Styliser le pseudo d'un joueur reconstruisait le pseudo flottant à partir du pseudo brut avec `event.setContent`, ce qui supprimait le préfixe/suffixe de team et faisait disparaître le grade à côté du pseudo. Le moteur de rendu ré-attache désormais le préfixe et le suffixe de team scoreboard autour du pseudo stylisé (lus directement sur la team du joueur, exactement comme vanilla compose le nom d'affichage), de sorte que le grade reste en place sauf s'il est explicitement masqué avec `nametag grade off`.

### Modifications

- **La stylisation des pseudos se reflète maintenant dans la liste TAB** — Auparavant, les couleurs/effets et le (nouveau) pseudo personnalisé n'apparaissaient que sur le pseudo flottant. Un gestionnaire `TabListNameFormat` côté serveur compose désormais le même `grade + pseudo stylisé` pour la liste TAB, rafraîchi en direct après chaque modification `nametag` via `ServerPlayer.refreshTabListName()` (aucune reconnexion nécessaire). La liste TAB est un instantané statique : les effets animés y apparaissent avec leurs couleurs mais ne s'y animent pas.

---

## [1.2.6] - 2026-06-08

### Security

- **GUI privilege escalation via forged slot-click packets (TELEPORT)** — The admin panel's two-layer permission model (hide the button if the viewer lacks the node, AND re-check the node in the click handler so a crafted packet can't trigger an unrendered action) had holes on every teleport path that didn't go through a numbered slot case. A staff member granted `arcadia.adminpanel.open` but **not** `arcadia.adminpanel.teleport` could craft a click packet and teleport anyway via: the player-detail **homes** grid (slots 9–35) and **teleport-history** row (slots 36–44), and the **team-detail** member right-click (TP to last-seen). None re-checked `TELEPORT` before executing. All now re-check `arcadia.adminpanel.teleport` in the handler, and the home/history buttons are also hidden at build time when the viewer lacks it.
- **Team browser reachable without `arcadia.adminpanel.teams`** — The Teams button on the main menu and the team-list/team-detail menus gated only on FTB Teams being installed, never on the `TEAMS` node, so any panel user could browse every team's roster and members. The whole Teams chain (main button → team list → team detail) now requires `arcadia.adminpanel.teams` at both the visibility and action layers.
- **Warn deletion only required `warn.view`** — The delete-on-click handler in the warn list gated on "is staff" (panel access) rather than `arcadia.adminpanel.warn.edit`, so a moderator with view-only warn permission could delete warns through the GUI. Deletion now re-checks `WARN_EDIT`, and the delete hint is hidden from view-only viewers.
- **Player info sheet had no permission node** — The info book (ban/whitelist status, login history, last-seen) rendered and opened for anyone who could open the panel, with no node of its own. New node `arcadia.adminpanel.info` now gates both its visibility and its action.

### Fixed

- **Jail auto-release could fire instantly on extreme durations** — `jailInternal` cast `durationMs / 50` straight to `int`; a duration above ~107 billion ms overflowed to a negative tick delay and released the player immediately. Now clamped with `Math.max(1, …)` and bounded to `Integer.MAX_VALUE`, matching the startup and unjail schedulers.
- **Command failures were swallowed to `System.err`** — Eight command handlers caught exceptions with `printStackTrace()`. They now log through the mod's SLF4J logger so failures land in the server log with context instead of raw stderr spam.

### Added

- **Name-tag system — hide-behind-walls + colours & effects** — A full server-authoritative name-tag suite under `/arcadia_adminpanel nametag …`. **Hide behind walls** (ON by default): a player's floating name is suppressed client-side whenever a solid block sits on the line of sight between the observer's camera and that player, so you can't read who's hiding behind a wall (transparent blocks like glass/leaves don't occlude unless `nameTagOccludeTransparent` is enabled; per-player `exempt` keeps a name always visible). **Name styling**: `color <player> <named>` (16 vanilla colours), `rgb <player> <#hex>` (true 24-bit colour), `gradient <player> <#hex> <#hex> [#hex] [#hex]` (static multi-stop gradient), `effect <player> <effect>` with ten effects — **solid, gradient, rainbow, breathing, chase, wave, blink, fade, typewriter, random** — `style <player> <flag> <on|off>` (bold/italic/underline/strikethrough/obfuscated), `speed <player> <1-10>`, plus `reset`/`show`. Two new permission nodes: `arcadia.adminpanel.nametag` (edit) and `arcadia.adminpanel.nametag.hide` (global switch + exemptions). State persists to `config/arcadia/arcadiaadminpanel/nametags.json` and is synced to clients on join via new S2C packets; animated effects render smoothly off a client tick clock. All mutation is permission-checked server-side — there is no client→server name-tag packet, so a crafted packet can't restyle anyone.
- **`/arcadia_adminpanel loginqueue [on|off]`** — The login-throttle queue was config-file-only and its reserved permission node `arcadia.adminpanel.loginqueue` was never enforced. A runtime toggle command now shows or flips `loginQueueEnabled` (persisted to `config.json`), gated on that node — the node is no longer dead.
- **Granular mute node on the command path** — `/arcadia_adminpanel mute` / `unmute` now require `arcadia.adminpanel.mute` in addition to the `MOD` staff grade, matching the GUI's dual gate so one role config governs both. `checkwarn` is now gated on `arcadia.adminpanel.open` for auditability (it remains a read-only self-view).

### Changed

- **Arcadia Lib 1.2.9 → 1.2.14** — Picks up the lib's fail-closed permission backend (`PermissionBackend.DENY` is the dedicated-server default, so a missing/uninitialized LuckPerms no longer implicitly grants every node), the UUID-based debug-mode hardening, and the safe-command allow-list. 1.2.14 adds the **lazy LuckPerms backend binding** fix (a late provider registration no longer pins the whole session to fail-closed `DENY`, which had locked every `arcadia.*` node — the panel's permission checks included — until restart), a synchronous user-cache fallback so a permission probe on the join tick resolves correctly, and `DashboardScreen` / `ArcadiaHubScreen` per-frame allocation cuts. No API changes affect the admin panel.

### Performance

- **Player detail menu reads FTB data at most once per session** — `readPlayerData` (a file read + NBT/JSON parse on the server thread) was re-run on every home/history/TP click and the info sheet after already being read on menu build. A per-instance cache, reset on each rebuild, collapses these to a single read.
- **Jail proximity sweep no longer allocates a dimension string per tick** — The per-second anti-escape sweep called `…dimension().location().toString()` for every jailed player. The jail dimension is now parsed to a `ResourceLocation` once and compared by object, removing the per-iteration string allocation.
- **Client search no longer recompiles its regex per frame** — The player-list search stripped colour codes with an inline `replaceAll` pattern on up to 45 heads every render frame; the pattern is now compiled once as a static field.

### Sécurité

- **Élévation de privilèges via paquets de clic forgés (TELEPORT)** — Le modèle de permission à deux couches du panneau (cacher le bouton si le joueur n'a pas le nœud, ET re-vérifier le nœud dans le gestionnaire de clic pour qu'un paquet forgé ne puisse pas déclencher une action non rendue) avait des trous sur toutes les téléportations passant par une grille de slots plutôt qu'un case numéroté. Un staff ayant `arcadia.adminpanel.open` mais **pas** `arcadia.adminpanel.teleport` pouvait quand même téléporter via : la grille **homes** (slots 9–35), l'historique de **téléportation** (slots 36–44) et le clic droit sur un **membre d'équipe** (TP dernière position). Aucun ne re-vérifiait `TELEPORT` avant d'agir. Tous re-vérifient désormais `arcadia.adminpanel.teleport`, et les boutons homes/historique sont aussi masqués à la construction.
- **Navigateur d'équipes accessible sans `arcadia.adminpanel.teams`** — Le bouton Équipes et les menus liste/détail d'équipe ne dépendaient que de la présence de FTB Teams, jamais du nœud `TEAMS` ; n'importe quel utilisateur du panneau pouvait parcourir les rosters. Toute la chaîne Équipes exige maintenant `arcadia.adminpanel.teams` aux deux couches (visibilité + action).
- **La suppression d'avertissement n'exigeait que `warn.view`** — Le gestionnaire de suppression au clic se basait sur « est staff » (accès panneau) plutôt que sur `arcadia.adminpanel.warn.edit` ; un modérateur en lecture seule pouvait supprimer des avertissements via le GUI. La suppression re-vérifie désormais `WARN_EDIT`, et l'astuce de suppression est masquée aux lecteurs sans ce nœud.
- **La fiche d'info joueur n'avait aucun nœud de permission** — Le livre d'info (statut ban/whitelist, historique de connexion, dernière position) s'affichait et s'ouvrait pour quiconque pouvait ouvrir le panneau, sans nœud propre. Le nouveau nœud `arcadia.adminpanel.info` gère désormais sa visibilité et son action.

### Correctifs

- **La libération automatique de prison pouvait se déclencher immédiatement sur des durées extrêmes** — `jailInternal` convertissait `durationMs / 50` directement en `int` ; une durée au-delà de ~107 milliards de ms débordait en délai de ticks négatif et libérait le joueur aussitôt. Désormais borné avec `Math.max(1, …)` et plafonné à `Integer.MAX_VALUE`, comme les planificateurs de démarrage et de libération.
- **Les échecs de commande étaient avalés vers `System.err`** — Huit gestionnaires de commande capturaient les exceptions avec `printStackTrace()`. Ils passent maintenant par le logger SLF4J du mod pour que les échecs arrivent dans le log serveur avec contexte au lieu du stderr brut.

### Ajouts

- **Système de pseudos — masquage derrière les murs + couleurs & effets** — Une suite complète de gestion des pseudos, autoritaire côté serveur, sous `/arcadia_adminpanel nametag …`. **Masquage derrière les murs** (activé par défaut) : le pseudo flottant d'un joueur est supprimé côté client dès qu'un bloc plein se trouve sur la ligne de vue entre la caméra de l'observateur et ce joueur — impossible de lire qui se cache derrière un mur (les blocs transparents comme le verre/les feuilles ne masquent pas, sauf si `nameTagOccludeTransparent` est activé ; `exempt` rend le pseudo d'un joueur toujours visible). **Stylisation** : `color <joueur> <nom>` (16 couleurs vanilla), `rgb <joueur> <#hex>` (vraie couleur 24 bits), `gradient <joueur> <#hex> <#hex> [#hex] [#hex]` (dégradé multi-couleurs figé), `effect <joueur> <effet>` avec dix effets — **solid, gradient, rainbow, breathing, chase, wave, blink, fade, typewriter, random** — `style <joueur> <option> <on|off>` (gras/italique/souligné/barré/obfusqué), `speed <joueur> <1-10>`, plus `reset`/`show`. Deux nouveaux nœuds de permission : `arcadia.adminpanel.nametag` (édition) et `arcadia.adminpanel.nametag.hide` (interrupteur global + exemptions). L'état persiste dans `config/arcadia/arcadiaadminpanel/nametags.json` et est synchronisé aux clients à la connexion via de nouveaux paquets S2C ; les effets animés sont rendus de façon fluide via une horloge de tick client. Toute modification est vérifiée par permission côté serveur — il n'existe aucun paquet client→serveur de pseudo, donc un paquet forgé ne peut restyler personne.
- **`/arcadia_adminpanel loginqueue [on|off]`** — La file d'attente de connexion n'était configurable que par fichier et son nœud réservé `arcadia.adminpanel.loginqueue` n'était jamais appliqué. Une commande de bascule runtime affiche ou inverse `loginQueueEnabled` (persisté dans `config.json`), gérée par ce nœud — le nœud n'est plus mort.
- **Nœud de mute granulaire côté commande** — `/arcadia_adminpanel mute` / `unmute` exigent désormais `arcadia.adminpanel.mute` en plus du grade staff `MOD`, comme le double verrou du GUI, pour qu'une seule config de rôle régisse les deux. `checkwarn` est désormais gérée par `arcadia.adminpanel.open` pour l'auditabilité (elle reste une auto-consultation en lecture seule).

### Modifications

- **Arcadia Lib 1.2.9 → 1.2.14** — Récupère le backend de permission fail-closed de la lib (`PermissionBackend.DENY` est le défaut sur serveur dédié, donc un LuckPerms manquant/non initialisé n'accorde plus implicitement tous les nœuds), le durcissement du mode debug basé UUID et la liste blanche de commandes sûres. 1.2.14 ajoute le correctif de **liaison paresseuse du backend LuckPerms** (un enregistrement tardif du provider ne fige plus toute la session en `DENY` fail-closed, ce qui verrouillait tous les nœuds `arcadia.*` — y compris les vérifications de permission du panneau — jusqu'au redémarrage), un repli synchrone sur le cache utilisateur pour qu'une sonde de permission au tick de connexion se résolve correctement, et des réductions d'allocations par frame dans `DashboardScreen` / `ArcadiaHubScreen`. Aucun changement d'API n'affecte le panneau.

### Performance

- **Le menu détail joueur lit les données FTB une seule fois par session** — `readPlayerData` (lecture fichier + parsing NBT/JSON sur le thread serveur) était relancé à chaque clic home/historique/TP et sur la fiche d'info, après l'avoir déjà lu à la construction. Un cache par instance, réinitialisé à chaque reconstruction, réduit cela à une seule lecture.
- **La surveillance de proximité de prison n'alloue plus de chaîne de dimension par tick** — La sweep anti-évasion (chaque seconde) appelait `…dimension().location().toString()` pour chaque joueur emprisonné. La dimension de la prison est désormais parsée en `ResourceLocation` une fois et comparée par objet, supprimant l'allocation de chaîne par itération.
- **La recherche client ne recompile plus sa regex à chaque frame** — La recherche de la liste de joueurs retirait les codes couleur avec un motif `replaceAll` inline sur jusqu'à 45 têtes à chaque frame de rendu ; le motif est désormais compilé une fois en champ statique.

---

## [1.2.5] - 2026-06-03

### Fixed

- **FTB Teams showed empty / wouldn't appear** — FTB Teams stores a team's owner ONLY in the top-level `owner` string (parties) or implicitly as the team id (player teams); it is never written into the `ranks` map. The parser read `owner` but never added it as a member, so any solo team counted 0 members and `getEffectiveTeamFor()` couldn't match the owner — the team browser looked broken/empty. The owner is now re-injected as an `OWNER`-ranked member (verified against FTB Teams 2101.x `AbstractTeam.serializeNBT` + `PartyTeam`/`PlayerTeam.getRankForPlayer`). Added the missing `ENEMY` rank.
- **Offline players showed a UUID instead of their name** — Names were resolved only from the in-memory profile cache (`usercache.json`, capped at ~1000 MRU entries), so anyone past the cap fell back to a permanent `Unknown-xxxx` placeholder. Resolution is now multi-source — profile cache → FTB Teams cached `player_name` (covers every player with a personal team) → `usercache.json` parsed directly — and placeholders are upgraded on re-scan and repaired authoritatively the moment a player logs in.
- **Player heads rendered as the default Steve skin** — GUI heads shipped a name+UUID profile with an empty texture map, so the client never resolved the real skin. The mod now resolves the fully-textured `GameProfile` server-side (async, via `SkullBlockEntity.fetchGameProfile`), caches it, and ships the textures inline so the real skin renders. The player list re-sends head slots in place once textures arrive. (Online-mode only; offline-mode UUIDs have no Mojang record.)
- **FTB Essentials homes / last-seen / teleport history were silently empty** — FTB Essentials writes pretty-printed, multi-line SNBT, but the reader parsed it line-by-line and assumed every value fit on one line, so homes, last-seen, and `/back` history all parsed to nothing. Replaced with whole-file NBT parsing (`TagParser` on the full document), matching the FTB Chunks reader.
- **Staff with the panel permission couldn't open it** — Arcadia Lib's dashboard decides card visibility with `arcadia.hub.<cardId>` (i.e. `arcadia.hub.adminpanel`), while opening required `arcadia.adminpanel.open` / `arcadia.staff.mod`. Granting one but not the other left staff either unable to see the card or able to see it but not open it. `arcadia.hub.adminpanel` (checked strictly, so it still fails closed without a perm backend) now also opens the panel — one node both reveals and opens it.
- **Expired jails could re-jail a player after restart** — `isJailed()`'s expired-entry cleanup was guarded by a condition that was always false (the key is already gone once `computeIfPresent` returns null), so the stale DB/JSON row was never deleted and reloaded as an active jail. Eviction is now captured inside the remapping function and the row is flushed.
- **Warn deletion reported the wrong number** — The success message showed the inverse of the number printed on the clicked warn item (the deletion itself was always correct). The message now matches the item label.
- **Login queue admitted whole bursts at once** — The throttle reserved its window slot only when a queued login fired, so a simultaneous reconnect storm computed the same delay and all completed together. Slots are now reserved synchronously at schedule time, so admissions are paced correctly at `loginQueueMaxPerWindow` per window.
- **`delwarn` could delete the wrong DB row** — The delete matched only `(uuid, timestamp, warned_by)`; two warns at the same millisecond with different reasons could collide. The predicate now includes `reason` and `server_id`.
- **`getShortDimension` could throw on malformed dimension strings** — Guarded the empty path-segment case (e.g. `a::b`).

### Added

- **Next-login spawn override (debug teleport)** — Pin a one-shot spawn point to a player; on their next connection they are teleported there instead of their normal position, then the override is consumed. Set it from the player detail menu (new button — left-click pins your current position, right-click clears), or via `/arcadia_adminpanel setnextspawn <player>`, `clearnextspawn <player>`, `nextspawnlist`. Works for offline targets. Jail always takes priority. New node `arcadia.adminpanel.nextspawn`. Persisted across restarts.
- **Player-teams toggle in the Teams browser** — The team browser now lists server teams + parties and, via a toggle, per-player (personal) teams too — so you can see every team on FTB-Chunks-style servers where players claim land with their personal team.
- **Game-mode switch & Heal/Feed in the player menu** — New buttons for online players: cycle game mode (`arcadia.adminpanel.gamemode`), and heal/feed — left-click heals + extinguishes fire, right-click feeds (`arcadia.adminpanel.heal`). Applied programmatically (no command-string injection).
- **Live jail-zone command** — `/arcadia_adminpanel jailradius [blocks]` shows or sets the maximum jail-zone radius at runtime (persisted to `config.json`) and (re-)enables the anti-escape proximity sweep that teleports a jailed player back inside the zone if they get out. Gated on `arcadia.adminpanel.setjail`.

### Performance

- **Team detail menu no longer reads the disk per member on the tick thread** — Member last-seen data was read from disk for every visible member on every redraw, and member names were resolved inside the sort comparator (`O(n log n)` profile-cache lookups). Names are now precomputed once and last-seen is fetched lazily only on click.
- **FTB player-data cache now negative-caches misses** — A player with no FTB file no longer triggers a `Files.exists()` syscall on every GUI redraw.
- **Login tracking writes are async + coalesced** — `logins.json` was rewritten synchronously on the server thread on every connect/disconnect. Writes now run on a daemon IO thread and coalesce to at most one every few seconds, with a final flush on shutdown.
- **Warn DB load is off-thread** — `/reload` no longer freezes the tick running the warn `SELECT`; it runs async and swaps the cache in on completion.

### Correctifs

- **Les FTB Teams apparaissaient vides / pas du tout** — FTB Teams stocke le propriétaire d'une team UNIQUEMENT dans la chaîne `owner` de premier niveau (parties) ou implicitement via l'id de la team (player teams) ; il n'est jamais écrit dans la map `ranks`. Le parser lisait `owner` mais ne l'ajoutait jamais comme membre, donc toute team solo comptait 0 membre et `getEffectiveTeamFor()` ne trouvait pas le propriétaire — le navigateur de teams semblait cassé/vide. Le propriétaire est désormais ré-injecté comme membre de rang `OWNER` (vérifié sur FTB Teams 2101.x). Ajout du rang `ENEMY` manquant.
- **Les joueurs hors ligne affichaient un UUID au lieu de leur pseudo** — Les noms n'étaient résolus que via le cache profil en mémoire (`usercache.json`, plafonné à ~1000 entrées MRU) ; au-delà du plafond, on retombait sur un placeholder permanent `Unknown-xxxx`. La résolution est maintenant multi-sources — cache profil → `player_name` mis en cache par FTB Teams (couvre chaque joueur ayant une team personnelle) → `usercache.json` lu directement — les placeholders sont améliorés au re-scan et réparés de façon autoritaire dès qu'un joueur se connecte.
- **Les têtes de joueur affichaient le skin Steve par défaut** — Les têtes du GUI envoyaient un profil nom+UUID avec une map de textures vide, donc le client ne résolvait jamais le vrai skin. Le mod résout désormais le `GameProfile` complet (avec textures) côté serveur (async, via `SkullBlockEntity.fetchGameProfile`), le met en cache et embarque les textures pour que le vrai skin s'affiche. La liste des joueurs ré-envoie les têtes en place dès que les textures arrivent. (Mode online uniquement.)
- **Homes / dernière position / historique de téléportation FTB Essentials étaient silencieusement vides** — FTB Essentials écrit du SNBT multi-ligne, mais le lecteur le parsait ligne par ligne en supposant une valeur par ligne ; homes, last-seen et historique `/back` étaient donc tous vides. Remplacé par un parsing NBT pleine page (`TagParser` sur le document complet).
- **Le staff avec la permission du panel ne pouvait pas l'ouvrir** — Le dashboard d'Arcadia Lib décide la visibilité de la carte avec `arcadia.hub.<cardId>` (`arcadia.hub.adminpanel`), alors que l'ouverture exigeait `arcadia.adminpanel.open` / `arcadia.staff.mod`. Donner l'un sans l'autre laissait le staff soit incapable de voir la carte, soit capable de la voir sans pouvoir l'ouvrir. `arcadia.hub.adminpanel` (vérifié en strict) ouvre désormais aussi le panel — un seul nœud révèle ET ouvre.
- **Une prison expirée pouvait ré-emprisonner après redémarrage** — Le nettoyage de l'entrée expirée dans `isJailed()` était gardé par une condition toujours fausse, donc la ligne périmée n'était jamais supprimée et se rechargeait en prison active. L'éviction est maintenant capturée dans la fonction de remappage et la ligne est purgée.
- **La suppression de warn affichait le mauvais numéro** — Le message de succès affichait l'inverse du numéro inscrit sur le warn cliqué (la suppression elle-même était correcte). Le message correspond désormais au libellé.
- **La file de connexion admettait des rafales entières d'un coup** — Le créneau de fenêtre n'était réservé qu'au déclenchement, donc une rafale de reconnexions calculait le même délai et se terminait ensemble. Les créneaux sont désormais réservés de façon synchrone à la planification, cadençant correctement à `loginQueueMaxPerWindow` par fenêtre.
- **`delwarn` pouvait supprimer la mauvaise ligne** — La suppression ne matchait que `(uuid, timestamp, warned_by)`. Le prédicat inclut désormais `reason` et `server_id`.
- **`getShortDimension` pouvait planter sur des dimensions malformées** — Cas du segment vide (`a::b`) sécurisé.

### Ajouts

- **Spawn de prochaine connexion (téléport de debug)** — Épingle un point de spawn à usage unique sur un joueur ; à sa prochaine connexion il y est téléporté au lieu de sa position normale, puis l'override est consommé. À définir depuis le menu détail du joueur (nouveau bouton — clic gauche épingle votre position, clic droit annule) ou via `/arcadia_adminpanel setnextspawn <joueur>`, `clearnextspawn <joueur>`, `nextspawnlist`. Fonctionne sur les cibles hors ligne. La prison est toujours prioritaire. Nouveau nœud `arcadia.adminpanel.nextspawn`. Persistant.
- **Bascule des player teams dans le navigateur de Teams** — Le navigateur liste désormais les teams serveur + parties et, via une bascule, les teams personnelles aussi — pour voir toutes les teams sur les serveurs type FTB Chunks où les joueurs claim avec leur team personnelle.
- **Mode de jeu & Soin/Nourriture dans le menu joueur** — Nouveaux boutons pour les joueurs en ligne : changer de mode de jeu (`arcadia.adminpanel.gamemode`), et soigner/nourrir — clic gauche soigne + éteint le feu, clic droit nourrit (`arcadia.adminpanel.heal`). Appliqués programmatiquement (pas d'injection de commande).
- **Commande de zone de prison en direct** — `/arcadia_adminpanel jailradius [blocs]` affiche ou règle le rayon max de la zone de prison à l'exécution (persisté dans `config.json`) et (ré)active le balayage de proximité anti-évasion qui re-téléporte un détenu dans la zone s'il sort. Gated sur `arcadia.adminpanel.setjail`.

### Performance

- **Le menu détail d'une team ne lit plus le disque par membre sur le thread tick** — La dernière position de chaque membre visible était lue depuis le disque à chaque redraw, et les noms étaient résolus dans le comparateur de tri (`O(n log n)` lookups). Les noms sont désormais précalculés une fois et la dernière position est lue paresseusement seulement au clic.
- **Le cache de données FTB met aussi en cache les absences** — Un joueur sans fichier FTB ne déclenche plus un `Files.exists()` à chaque redraw du GUI.
- **L'écriture du suivi de connexion est async + coalescée** — `logins.json` était réécrit de façon synchrone sur le thread serveur à chaque connexion/déconnexion. Les écritures passent par un thread IO daemon et sont coalescées à au plus une toutes les quelques secondes, avec un flush final à l'arrêt.
- **Le chargement DB des warns est hors thread** — `/reload` ne gèle plus le tick le temps du `SELECT` des warns ; il s'exécute en async et échange le cache à la fin.

---

## [1.2.4] - 2026-05-18

### Added (fourth pass)

- **Quick announcement command** — New `/arcadia_adminpanel announce <title>[| <subtitle>]`. Pushes a vanilla title + optional subtitle to every online player and plays a `BLOCK_NOTE_BLOCK_BELL` chime so people actually notice. Title and subtitle are split on a single `|` so the whole message fits in one greedy argument. Color codes (`&a`, `&c`, `§e`, …) are honoured inline so staff can style the title without typing the section character. Title timings: 10t fade-in / 60t hold / 20t fade-out. New permission node `arcadia.adminpanel.announce` gates it (OP level ≥ 2 still short-circuits).

### Ajouts (fourth pass)

- **Commande d'annonce rapide** — Nouvelle `/arcadia_adminpanel announce <titre>[| <sous-titre>]`. Envoie un title + sous-titre vanilla à chaque joueur en ligne et joue un son `BLOCK_NOTE_BLOCK_BELL` pour que les gens remarquent. Titre et sous-titre sont séparés par un seul `|` pour que tout le message tienne dans un argument greedy. Les codes couleur (`&a`, `&c`, `§e`, …) sont respectés inline donc le staff peut styliser le titre sans taper le caractère section. Durées du titre : 10t fade-in / 60t hold / 20t fade-out. Nouveau node de permission `arcadia.adminpanel.announce` qui gate la commande (OP level ≥ 2 court-circuite toujours).

### Added (third pass)

- **Jail Baton (matraque) item** — Custom 32×32 textured staff tool. Right-click another player to jail them for 30 minutes; right-click an already-jailed player to release them. Wielder must have `arcadia.adminpanel.jail` (same gate as the GUI jail button). Cannot baton yourself, cannot baton other staff members (`canOpenAdminPanel` immunity check). New command `/arcadia_adminpanel givebaton` drops one into the staff member's inventory. Stack size 1, RARE rarity, fire-resistant. Hand model uses the vanilla handheld pose so it looks like a tool when held.
- **Warn offline players** — New `/arcadia_adminpanel warnoffline <name> <reason>` accepts both online AND offline targets (resolves via the offline-player cache). Offline targets get the warn row written immediately; the warn list and the on-join notification cover the rest. The existing `warn @selector <reason>` is preserved unchanged for multi-target online use cases. The GUI's existing warn flow (right-click + chat-input session) already worked for offline players.

### Fixed (third pass)

- **Join warn message: the command was hard to spot** — The "/arcadia_adminpanel checkwarn" hint was sent immediately on join (drowned by other mods' welcome spam) and rendered as plain text in a small grey font. Two improvements: (1) delay the warn-summary delivery by 40 ticks (~2 s) so it lands AFTER other mods' join messages; (2) make the command name a clickable + hoverable component using `SUGGEST_COMMAND` action — clicking it fills the chat box so the player sees what they're about to run before pressing Enter. Hover tooltip explains "click to fill the chat box".

### Performance (third pass)

- **Jail anti-glitch sweep now iterates jailed set, not online player list** — Previous version did `O(online_players)` lookups per tick interval. On a 100-player server with 0 jailed entries that was 100 wasted HashMap lookups per second. New version iterates `JailManager.getAllJailed()` directly — typically 0-3 entries — and looks up the corresponding `ServerPlayer` once per jailed UUID.
- **`WarnPolicy.filterActive` always returns an immutable snapshot** — Defensive change so callers stashing the list across thread boundaries (the new scheduler-delayed join notification) cannot accidentally hold a reference to mutable internal state. Costs one extra `List.copyOf` when expiry is disabled; negligible.

### Ajouts (third pass)

- **Matraque de Prison (Jail Baton)** — Outil staff custom avec texture 32×32. Clic droit sur un joueur pour l'emprisonner 30 minutes ; clic droit sur un joueur déjà en prison pour le libérer. Le porteur doit avoir `arcadia.adminpanel.jail` (même gate que le bouton jail du GUI). Impossible de se matraquer soi-même, impossible de matraquer un autre staff (immunité `canOpenAdminPanel`). Nouvelle commande `/arcadia_adminpanel givebaton` ajoute une matraque dans l'inventaire du staff. Stack 1, rareté RARE, résistante au feu. Le modèle d'inventaire utilise le pose handheld vanilla pour ressembler à un outil quand tenu en main.
- **Warn des joueurs hors ligne** — Nouvelle `/arcadia_adminpanel warnoffline <nom> <raison>` accepte les cibles online ET offline (résolu via le cache offline-player). Les cibles offline reçoivent l'écriture du warn immédiatement ; la liste des warns et la notification à la connexion gèrent le reste. L'existante `warn @selector <raison>` est préservée inchangée pour les usages multi-cible en ligne. Le flow warn du GUI (clic droit + session chat-input) fonctionnait déjà pour les joueurs offline.

### Correctifs (third pass)

- **Message warn à la connexion : la commande était dure à voir** — Le hint "/arcadia_adminpanel checkwarn" était envoyé immédiatement à la connexion (noyé par le spam de bienvenue des autres mods) et rendu en texte gris pâle. Deux améliorations : (1) délai du résumé warn de 40 ticks (~2 s) pour qu'il atterrisse APRÈS les messages de connexion des autres mods ; (2) le nom de la commande devient un component cliquable + hoverable avec l'action `SUGGEST_COMMAND` — cliquer remplit la barre de chat pour que le joueur voie ce qu'il va exécuter avant d'appuyer sur Entrée. Tooltip hover explique "cliquez pour remplir la barre de chat".

### Performance (third pass)

- **Le balayage anti-glitch de la prison itère le set des jailed, pas la liste des joueurs en ligne** — La version précédente faisait `O(joueurs_en_ligne)` lookups par intervalle de tick. Sur un serveur de 100 joueurs avec 0 entrée jailed c'était 100 HashMap lookups gaspillés par seconde. La nouvelle version itère `JailManager.getAllJailed()` directement — typiquement 0-3 entrées — et lookup le `ServerPlayer` correspondant une fois par UUID jailed.
- **`WarnPolicy.filterActive` retourne toujours un snapshot immuable** — Changement défensif pour que les callers stashant la liste à travers les frontières de threads (la nouvelle notification de connexion délayée par scheduler) ne puissent pas accidentellement garder une référence à l'état mutable interne. Coûte un `List.copyOf` supplémentaire quand l'expiration est désactivée ; négligeable.

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
