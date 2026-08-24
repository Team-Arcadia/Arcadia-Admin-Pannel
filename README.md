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
- **Name Tags — Custom pseudo, grade, hide-behind-walls + Colours & Effects** — Server-authoritative floating-name control that also drives the TAB list. **Custom pseudo**: `nametag name <player> <pseudo…>` overrides the displayed name on both the floating head-tag and the TAB list (the real username is kept for chat, `/msg`, bans and teleports). **Grade aware**: the player's grade (the scoreboard-team prefix/suffix set by LuckPerms & co.) is preserved around the styled name and can be shown/hidden per player with `nametag grade <player> <on|off>`. **Hide behind walls** (ON by default): a player's name is suppressed client-side when a solid block sits between the observer's camera and that player (a line-of-sight raytrace), so you can't read who is hiding behind a wall; transparent blocks (glass/leaves) don't occlude unless configured, and any player can be made permanently visible (`exempt`). **Styling**: named colours, true RGB, static multi-stop gradients, ten animated effects (**solid, gradient, rainbow, breathing, chase, wave, blink, fade, typewriter, random**), the five text decorations (bold/italic/underline/strikethrough/obfuscated) and an animation speed. State persists to `nametags.json` and syncs to clients on join; floating-tag effects animate off a client tick (the TAB list shows colours statically). Two nodes: `arcadia.adminpanel.nametag` and `arcadia.adminpanel.nametag.hide`.
- **Granular Permissions** — One LuckPerms node per action (`arcadia.adminpanel.warn.view`, `.warn.edit`, `.kick`, `.ban`, `.mute`, `.jail`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.teams`, `.reload`, `.setjail`, `.loginqueue`, `.announce`, `.nextspawn`, `.gamemode`, `.heal`, `.nametag`, `.nametag.hide`, `.open`). Buttons the viewer can't use are hidden from the GUI entirely. **To grant panel access, the simplest single node is `arcadia.hub.adminpanel`** — it is the node Arcadia Lib's dashboard uses to show the card, and as of 1.2.5 it also opens the panel (so one grant both reveals and opens it). OP level ≥ 2 short-circuits everything; legacy `arcadia.staff.mod` still grants full access for backwards compatibility.
- **Staff Audit Log (1.3.0)** — Every staff action recorded with author, target, timestamp, server and reason. Browsable, filterable to one player or one staff member, synced across servers, with a configurable retention window. The unified **sanction history** merges warns, mutes, jails, kicks and bans into one timeline per player, and **private staff notes** hold the observations that are not warns.
- **Vanish, Freeze and Spectate (1.3.0)** — Vanish is genuinely server-side: the entity and TAB entry are removed from other clients rather than hidden by a client that could ignore the instruction, and un-vanishing rebuilds the spawn packets instead of forcing a chunk reload. **Freeze** holds a suspect for a screenshare (no movement, no interaction, no damage, chat still open) with a dimmed client overlay; since 1.3.1 the hold is applied on the client itself and **survives a disconnect and a server restart**, so logging off is not a way out of a screenshare. **Spectate** jumps to a player and puts you back exactly where you were, even if the session ends involuntarily.
- **Temporary Bans, Ban List and Sanction Templates (1.3.0)** — Bans take a duration and a typed reason, the ban list is a screen with expiry countdowns and one-click unban, and bans replicate across servers and are enforced at negotiation time. **Templates** hold pre-written offences with an escalation ladder that picks its own severity from what the player already collected.
- **Death Snapshots and the Inventory Editor (1.3.0)** — The full inventory is captured on every death (last five kept per player, with cause and position) and can be handed back in one click, online or offline. Since 1.3.1 a capture merges with what is already on disk, so the history no longer resets on the first death after a reboot, and the snapshot view is laid out like an inventory screen. The **inventory editor** views and modifies any player's inventory including a disconnected one, with an explicit save and a refusal to write if they reconnected meanwhile.
- **Performance Panel and Chunk Browser (1.3.0)** — TPS, tick time, memory, per-dimension entity and chunk counts, the hottest chunks with a teleport, and which players carry the most nearby entities. The chunk browser ranks FTB Chunks footprints by force-loaded count and lists vanilla forced chunks. Both compute only when opened and cache the result, so an idle server does no work.
- **Watchlist, Shared-Connection Detection and Client Mods (1.3.0)** — Flag a player and get pinged when they connect. Accounts are grouped by a **salted hash** of their address, never the address itself, so the panel can say two accounts share an origin and can never say where. Clients running the mod declare their mod list, matched against a configurable blacklist and labelled throughout as self-declared.
- **World Control, Chat Control and Scheduling (1.3.0)** — Time, weather, difficulty and fourteen game rules as buttons. Chat lock and clear chat for incidents. **Scheduled restarts** with warning marks and a countdown, **rotating auto-broadcasts**, and an **automatic post-reboot login queue** that arms and disarms itself.
- **Offline Mail, Playtime, AFK list, Radar, Bulk Actions and Return Teleport (1.3.0)** — Leave a message for a disconnected player. Rank playtime and sessions. See who is idle and where. See who is nearby. Build a selection from the grid and message, gather, heal, warn or kick it. Walk back out of every panel teleport.
- **Disguise, extended (1.3.0)** — A picker menu listing every living entity with its spawn egg, plus baby form, a render scale (visual only: the hitbox stays the player's), an optional mob name above the disguise, `random`, server-wide `--all` / `--random` / `--clear` / `--list`, and an optional clear-on-death.
- **Filters and Sorting (1.3.0)** — The player grid filters by online, offline, jailed, muted, banned, warned, watched, frozen, vanished, AFK or selected, sorts by name, last seen, playtime or warn count, and marks each head with its current state.
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
| `stafftoggle` | Staff HELPER+ | Toggle staff chat mode (every chat line goes to the staff channel, never to public chat or a Discord bridge) |
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
| `nametag name <player> <pseudo…>` | `arcadia.adminpanel.nametag` | Custom display pseudo on the tag + TAB list (`reset` clears it) |
| `nametag grade <player> <on\|off>` | `arcadia.adminpanel.nametag` | Show/hide the grade (team prefix/suffix) next to the name |
| `nametag style <player> <flag> <on\|off>` | `arcadia.adminpanel.nametag` | Toggle bold/italic/underline/strikethrough/obfuscated |
| `nametag speed <player> <1-10>` | `arcadia.adminpanel.nametag` | Animation speed |
| `nametag reset <player>` | `arcadia.adminpanel.nametag` | Clear all name styling |
| `nametag show <player>` | `arcadia.adminpanel.nametag` | Print a player's current styling |
| `nametag exempt <player>` | `arcadia.adminpanel.nametag.hide` | Toggle a player's exemption from hiding (always visible) |
| `nametag hide [on\|off]` | `arcadia.adminpanel.nametag.hide` | Show/toggle the global hide-names-behind-walls switch |
| `nametag hideall [on\|off]` | `arcadia.adminpanel.nametag.hide` | Event blackout: hide **every** player's name at once (hide-and-seek) |
| `nametag forcehide <player>` | `arcadia.adminpanel.nametag.hide` | Toggle hiding one player's name permanently for everyone |
| `disguise <player> <entity>` | `arcadia.adminpanel.disguise` | Disguise a player as any living mob (e.g. `minecraft:pig`); `reset` clears it |
| `tools` | `arcadia.adminpanel.open` | Open the Staff Tools screen (server-wide half of the panel) |
| `vanish` | `arcadia.adminpanel.vanish` | Toggle your own invisibility |
| `freeze <player> [reason]` | `arcadia.adminpanel.freeze` | Freeze a player for a screenshare |
| `unfreeze <player>` | `arcadia.adminpanel.freeze` | Release a frozen player |
| `spectate <player>` / `unspectate` | `arcadia.adminpanel.spectate` | Spectate a player, then return where you were |
| `history <player>` | `arcadia.adminpanel.history` | Open the unified sanction history |
| `audit [player]` | `arcadia.adminpanel.audit` | Open the staff audit log |
| `note <player> <text>` | `arcadia.adminpanel.notes` | Add a private staff note (`!` prefix pins it) |
| `notes <player>` | `arcadia.adminpanel.notes` | Open a player's notes |
| `watch <player> [reason]` / `unwatch <player>` | `arcadia.adminpanel.watchlist` | Flag or unflag a player |
| `watchlist` | `arcadia.adminpanel.watchlist` | Open the watchlist |
| `tempban <player> <minutes> [reason]` | `arcadia.adminpanel.ban` | Ban with a duration (`0` = permanent) |
| `banlist` | `arcadia.adminpanel.ban` | Open the ban list |
| `templates <player>` | `arcadia.adminpanel.templates` | Apply a sanction template with escalation |
| `invedit <player>` | `arcadia.adminpanel.invedit` | Edit an inventory, online or offline |
| `deaths <player>` | `arcadia.adminpanel.deathrestore` | Browse and restore death snapshots |
| `giveitem <player> <item> [count]` | `arcadia.adminpanel.giveitem` | Give an item by id |
| `mail <player> <message>` | `arcadia.adminpanel.mail` | Leave a message for an offline player |
| `sessions` | `arcadia.adminpanel.sessions` | Playtime and session ranking |
| `afklist` | `arcadia.adminpanel.afk` | Who is idle and for how long |
| `alts` | `arcadia.adminpanel.alts` | Accounts sharing a connection fingerprint |
| `clientmods` | `arcadia.adminpanel.clientmods` | Mod lists declared by connected clients |
| `lag` | `arcadia.adminpanel.performance` | One-line performance summary in chat |
| `lagpanel` | `arcadia.adminpanel.performance` | Open the performance panel |
| `chunks` | `arcadia.adminpanel.chunks` | Open the chunk browser |
| `world` | `arcadia.adminpanel.world` | Open world control |
| `radar` | `arcadia.adminpanel.radar` | Who is nearby |
| `chatlock` | `arcadia.adminpanel.chatcontrol` | Lock or unlock public chat |
| `clearchat` | `arcadia.adminpanel.chatcontrol` | Scroll everyone's chat away |
| `restart <minutes> [reason]` / `restart cancel` | `arcadia.adminpanel.restart` | Schedule or cancel a restart |
| `broadcast` / `broadcast toggle` | `arcadia.adminpanel.broadcast` | Send the next auto-broadcast, or toggle the rotation |
| `cmdspy` / `socialspy` | `arcadia.adminpanel.spy` | Toggle the command and private-message feeds |
| `silent` | `arcadia.adminpanel.silent` | Toggle silent mode (no public announcement) |
| `select <player>` / `selectclear` | `arcadia.adminpanel.bulk` | Build or clear the bulk selection |
| `back` | `arcadia.adminpanel.back` | Return to your position before the last panel teleport |
| `disguise <player> baby <on\|off>` | `arcadia.adminpanel.disguise` | Baby form of the disguise |
| `disguise <player> scale <0.25-4.0>` | `arcadia.adminpanel.disguise` | Render scale (visual only) |
| `disguise <player> name <on\|off>` | `arcadia.adminpanel.disguise` | Show the mob name above the disguise |
| `disguise <player> random` | `arcadia.adminpanel.disguise` | Random living mob |
| `disguise <player> show` | `arcadia.adminpanel.disguise` | Print the current disguise and its options |
| `disguise --all <entity>` | `arcadia.adminpanel.disguise` | Disguise every online player |
| `disguise --random` | `arcadia.adminpanel.disguise` | Give every online player a random disguise |
| `disguise --clear` | `arcadia.adminpanel.disguise` | Remove every disguise |
| `disguise --list` | `arcadia.adminpanel.disguise` | List the disguised players |

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
| `arcadia.adminpanel.disguise` | Disguise a player as a mob (`disguise …`) |
| `arcadia.adminpanel.vanish.see` | See other vanished staff. Separate from `.vanish` so a trainee can be hidden from. |

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
2. Place `arcadia-admin-panel-1.3.1.jar` in your `mods/` folder
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
| `disguiseClearOnDeath` | `false` | Drop a player's disguise when they die. |
| `broadcastSanctions` | `false` | Announce sanctions server-wide. Staff always see them regardless. |
| `auditRetentionDays` | `180` | Drop audit rows older than N days. `0` disables the sweep. Sanctions are never dropped. |
| `discordWebhookUrl` | `""` | Incoming-webhook URL for the sanction mirror. **This is a credential: keep it out of git.** Empty disables every outbound call. |
| `discordWebhookName` | `Arcadia Moderation` | Display name the webhook posts under. |
| `discordSkipSilent` | `true` | Skip actions performed in silent mode. |
| `vanishHideFromTab` | `true` | Remove a vanished staff member from the TAB list. |
| `vanishFakeJoinLeave` | `true` | Print a fake leave/join line when toggling vanish. |
| `vanishNoPickup` | `true` | A vanished staff member walks over items without picking them up. |
| `vanishNoMobTarget` | `true` | Mobs forget a vanished staff member. |
| `vanishPersist` | `false` | Restore vanish on the next login. |
| `freezeDamageImmunity` | `true` | A frozen player takes no damage. |
| `freezeReminderSeconds` | `10` | Seconds between reminder lines for a frozen player. `0` disables. |
| `defaultTempbanMinutes` | `1440` | Duration used by the GUI temp-ban button. |
| `banSyncEnabled` | `true` | Replicate bans to the other servers sharing the database. |
| `escalationEnabled` | `true` | Apply the escalation ladder when a template is used. |
| `chatLockAllowStaff` | `true` | Staff can still talk while the chat is locked. |
| `clearChatLines` | `100` | Blank lines pushed by the clear-chat action. |
| `deathSnapshotsEnabled` | `true` | Capture the inventory on death. |
| `deathSnapshotsPerPlayer` | `5` | How many deaths to keep per player. |
| `inventoryEditEnabled` | `true` | Allow editing a connected player's inventory. |
| `offlineInventoryEditEnabled` | `true` | Allow rewriting a disconnected player's stored inventory. |
| `mailMaxPerPlayer` | `20` | Cap on undelivered messages per player. |
| `afkEnabled` | `true` | Track idle players. |
| `afkMinutes` | `5` | Minutes without movement, chat or interaction before a player counts as AFK. |
| `afkCheckIntervalTicks` | `100` | Ticks between AFK sweeps (100 = every 5 s). |
| `altDetectionEnabled` | `true` | Group accounts by a salted hash of their address. |
| `altAlertStaff` | `true` | Ping staff when an account matches a banned one. |
| `storePlainIp` | `false` | Keep writing the plain address in `logins.json`. Off since 1.3.0: the hash covers every panel feature. |
| `clientModsEnabled` | `true` | Collect the mod list reported by clients running this mod. |
| `clientModBlacklist` | `[]` | Mod ids that raise a staff alert, matched case-insensitively. |
| `clientModAlertStaff` | `true` | Ping staff on a blacklist hit. |
| `lagSampleCacheSeconds` | `10` | Seconds a computed performance sample is reused. |
| `lagTopChunks` | `10` | How many hot chunks the panel lists. |
| `lagEntityRadius` | `64` | Radius (blocks) used when attributing nearby entities to a player. |
| `restartScheduleTimes` | `[]` | Daily restart times in 24 h `HH:mm` local time. Empty disables the schedule. |
| `restartWarnMinutes` | `[15,10,5,3,1]` | Minute marks at which a warning is broadcast. |
| `restartCountdownSeconds` | `10` | Seconds of per-second countdown at the end. |
| `restartReason` | `Scheduled restart` | Kick message shown when the restart fires. |
| `autoBroadcastEnabled` | `false` | Master switch for the rotating announcements. |
| `autoBroadcastIntervalMinutes` | `15` | Minutes between two messages. |
| `autoBroadcastMessages` | `[]` | Messages, broadcast in order then looped. Supports § colour codes. |
| `loginQueueAutoAfterBoot` | `false` | Arm the login queue by itself after boot. |
| `loginQueueAutoMinutes` | `10` | Minutes the automatic queue stays on. |
| `watchlistAlertOnJoin` | `true` | Ping staff when a watched player connects. |
| `radarRadius` | `128` | Radius (blocks) scanned by the proximity radar. |

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
- **Pseudos — Pseudo personnalisé, grade, masquage derrière les murs + Couleurs & Effets** — Contrôle du pseudo flottant, autoritaire côté serveur, qui pilote aussi la liste TAB. **Pseudo personnalisé** : `nametag name <joueur> <pseudo…>` remplace le pseudo affiché à la fois sur le pseudo flottant et dans la liste TAB (le vrai pseudo est conservé pour le chat, `/msg`, bans et téléportations). **Conscient du grade** : le grade du joueur (préfixe/suffixe de team scoreboard posé par LuckPerms & co.) est préservé autour du pseudo stylisé et peut être affiché/masqué par joueur avec `nametag grade <joueur> <on|off>`. **Masquage derrière les murs** (activé par défaut) : le pseudo d'un joueur est supprimé côté client quand un bloc plein se trouve entre la caméra de l'observateur et ce joueur (raytrace de ligne de vue) — impossible de lire qui se cache derrière un mur ; les blocs transparents (verre/feuilles) ne masquent pas sauf configuration, et tout joueur peut être rendu toujours visible (`exempt`). **Stylisation** : couleurs nommées, vraie RGB, dégradés multi-couleurs figés, dix effets animés (**solid, gradient, rainbow, breathing, chase, wave, blink, fade, typewriter, random**), les cinq décorations de texte (gras/italique/souligné/barré/obfusqué) et une vitesse d'animation. L'état persiste dans `nametags.json` et est synchronisé aux clients à la connexion ; les effets du pseudo flottant s'animent via un tick client (la liste TAB affiche les couleurs de façon statique). Deux nodes : `arcadia.adminpanel.nametag` et `arcadia.adminpanel.nametag.hide`.
- **Permissions Granulaires** — Un node LuckPerms par action (`arcadia.adminpanel.warn.view`, `.warn.edit`, `.kick`, `.ban`, `.mute`, `.jail`, `.teleport`, `.invsee`, `.clearinv`, `.resetprogress`, `.teams`, `.reload`, `.setjail`, `.loginqueue`, `.announce`, `.nextspawn`, `.gamemode`, `.heal`, `.nametag`, `.nametag.hide`, `.open`). Les boutons que le viewer ne peut pas utiliser sont entièrement cachés du GUI. **Pour accorder l'accès au panel, le node unique le plus simple est `arcadia.hub.adminpanel`** — c'est le node qu'utilise le dashboard d'Arcadia Lib pour afficher la carte, et depuis la 1.2.5 il ouvre aussi le panel (un seul grant révèle ET ouvre). OP level ≥ 2 court-circuite tout ; le legacy `arcadia.staff.mod` accorde toujours l'accès complet pour rétrocompatibilité.
- **Journal d'Audit Staff (1.3.0)** — Chaque action du staff enregistrée avec son auteur, sa cible, la date, le serveur et la raison. Consultable, filtrable sur un joueur ou un membre du staff, synchronisée entre serveurs, avec une rétention configurable. L'**historique unifié des sanctions** regroupe warns, mutes, prisons, expulsions et bans en une seule frise par joueur, et les **notes privées du staff** accueillent les observations qui ne sont pas des avertissements.
- **Invisibilité, Gel et Observation (1.3.0)** — L'invisibilité est réellement côté serveur : l'entité et l'entrée TAB sont retirées des autres clients plutôt que masquées par un client qui pourrait ignorer la consigne, et la réapparition reconstruit les paquets d'apparition au lieu de forcer un rechargement de chunks. Le **gel** immobilise un suspect pour un screenshare (ni déplacement, ni interaction, ni dégât, chat toujours ouvert) avec un voile explicatif côté client ; depuis la 1.3.1 le blocage s'applique sur le client lui-même et **survit à une déconnexion comme à un redémarrage**, se déconnecter n'est donc plus une sortie de screenshare. L'**observation** vous emmène sur un joueur et vous remet exactement où vous étiez, même si la session se termine involontairement.
- **Bans Temporaires, Liste des Bans et Modèles de Sanction (1.3.0)** — Les bans acceptent une durée et une raison saisie, la liste est un écran avec compte à rebours d'expiration et débannissement en un clic, et les bans se répliquent entre serveurs en étant appliqués dès la négociation. Les **modèles** contiennent des infractions prérédigées avec une échelle d'escalade qui choisit sa sévérité selon ce que le joueur a déjà accumulé.
- **Instantanés de Mort et Éditeur d'Inventaire (1.3.0)** — L'inventaire complet est capturé à chaque mort (les cinq derniers par joueur, avec cause et position) et peut être rendu en un clic, en ligne comme hors ligne. Depuis la 1.3.1 une capture fusionne avec ce qui est déjà sur disque, l'historique ne repart donc plus de zéro à la première mort après un reboot, et la vue d'un instantané est disposée comme un écran d'inventaire. L'**éditeur d'inventaire** consulte et modifie l'inventaire de n'importe quel joueur, y compris déconnecté, avec une sauvegarde explicite et un refus d'écrire s'il s'est reconnecté entre-temps.
- **Panneau de Performances et Navigateur de Chunks (1.3.0)** — TPS, temps de tick, mémoire, entités et chunks par dimension, les chunks les plus chargés avec téléportation, et les joueurs qui portent le plus d'entités à proximité. Le navigateur de chunks classe les empreintes FTB Chunks par nombre de chunks force-loaded et liste les chunks forcés vanilla. Les deux ne calculent qu'à l'ouverture et mettent le résultat en cache : un serveur au repos ne fait aucun travail.
- **Surveillance, Détection de Connexions Partagées et Mods Client (1.3.0)** — Signalez un joueur et recevez un ping à sa connexion. Les comptes sont regroupés par un **hachage salé** de leur adresse, jamais par l'adresse elle-même : le panel peut dire que deux comptes partagent une origine et ne peut jamais dire laquelle. Les clients équipés du mod déclarent leur liste de mods, comparée à une liste noire configurable et présentée partout comme une auto-déclaration.
- **Contrôle du Monde, Contrôle du Chat et Programmation (1.3.0)** — Heure, météo, difficulté et quatorze gamerules sous forme de boutons. Verrou et vidage du chat pour les incidents. **Redémarrages programmés** avec paliers d'avertissement et décompte, **annonces automatiques en rotation**, et une **file d'attente automatique après reboot** qui s'active et se désactive toute seule.
- **Courrier Hors Ligne, Temps de Jeu, Liste AFK, Radar, Actions Groupées et Retour (1.3.0)** — Laissez un message à un joueur déconnecté. Classez le temps de jeu et les sessions. Voyez qui est inactif et où. Voyez qui est à proximité. Constituez une sélection depuis la grille pour message, rassemblement, soin, avertissement ou expulsion. Remontez chaque téléportation du panel.
- **Déguisement, étendu (1.3.0)** — Un menu de sélection listant toutes les entités vivantes avec leur œuf d'apparition, plus la forme bébé, une échelle de rendu (purement visuelle : la hitbox reste celle du joueur), un nom de mob optionnel au-dessus du déguisement, `random`, les commandes serveur `--all` / `--random` / `--clear` / `--list`, et un retrait optionnel à la mort.
- **Filtres et Tris (1.3.0)** — La grille des joueurs filtre par en ligne, hors ligne, prison, mute, bannis, avertis, surveillés, gelés, invisibles, AFK ou sélectionnés, trie par nom, dernière connexion, temps de jeu ou nombre d'avertissements, et marque chaque tête avec son état actuel.
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
| `stafftoggle` | Staff HELPER+ | Basculer le mode chat staff (chaque ligne de chat part vers le canal staff, jamais vers le chat public ni un lien Discord) |
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
| `nametag name <joueur> <pseudo…>` | `arcadia.adminpanel.nametag` | Pseudo personnalisé sur le tag + liste TAB (`reset` l'efface) |
| `nametag grade <joueur> <on\|off>` | `arcadia.adminpanel.nametag` | Afficher/masquer le grade (préfixe/suffixe de team) à côté du pseudo |
| `nametag style <joueur> <option> <on\|off>` | `arcadia.adminpanel.nametag` | Basculer gras/italique/souligné/barré/obfusqué |
| `nametag speed <joueur> <1-10>` | `arcadia.adminpanel.nametag` | Vitesse d'animation |
| `nametag reset <joueur>` | `arcadia.adminpanel.nametag` | Réinitialiser tout le style du pseudo |
| `nametag show <joueur>` | `arcadia.adminpanel.nametag` | Afficher le style actuel d'un joueur |
| `nametag exempt <joueur>` | `arcadia.adminpanel.nametag.hide` | Basculer l'exemption de masquage d'un joueur (toujours visible) |
| `nametag hide [on\|off]` | `arcadia.adminpanel.nametag.hide` | Afficher/basculer le masquage global des pseudos derrière les murs |
| `nametag hideall [on\|off]` | `arcadia.adminpanel.nametag.hide` | Mode event : masquer **tous** les pseudos d'un coup (cache-cache) |
| `nametag forcehide <joueur>` | `arcadia.adminpanel.nametag.hide` | Basculer le masquage permanent du pseudo d'un joueur pour tous |
| `disguise <joueur> <entité>` | `arcadia.adminpanel.disguise` | Déguiser un joueur en n'importe quel mob vivant (ex. `minecraft:pig`) ; `reset` l'enlève |
| `tools` | `arcadia.adminpanel.open` | Ouvrir l'écran Outils Staff (moitié serveur du panel) |
| `vanish` | `arcadia.adminpanel.vanish` | Basculer sa propre invisibilité |
| `freeze <joueur> [raison]` | `arcadia.adminpanel.freeze` | Geler un joueur pour un screenshare |
| `unfreeze <joueur>` | `arcadia.adminpanel.freeze` | Libérer un joueur gelé |
| `spectate <joueur>` / `unspectate` | `arcadia.adminpanel.spectate` | Observer un joueur, puis revenir à sa position |
| `history <joueur>` | `arcadia.adminpanel.history` | Ouvrir l'historique unifié des sanctions |
| `audit [joueur]` | `arcadia.adminpanel.audit` | Ouvrir le journal d'audit staff |
| `note <joueur> <texte>` | `arcadia.adminpanel.notes` | Ajouter une note privée (préfixe `!` pour l'épingler) |
| `notes <joueur>` | `arcadia.adminpanel.notes` | Ouvrir les notes d'un joueur |
| `watch <joueur> [raison]` / `unwatch <joueur>` | `arcadia.adminpanel.watchlist` | Mettre ou retirer de la surveillance |
| `watchlist` | `arcadia.adminpanel.watchlist` | Ouvrir la liste de surveillance |
| `tempban <joueur> <minutes> [raison]` | `arcadia.adminpanel.ban` | Bannir avec une durée (`0` = définitif) |
| `banlist` | `arcadia.adminpanel.ban` | Ouvrir la liste des bans |
| `templates <joueur>` | `arcadia.adminpanel.templates` | Appliquer un modèle de sanction avec escalade |
| `invedit <joueur>` | `arcadia.adminpanel.invedit` | Éditer un inventaire, en ligne ou hors ligne |
| `deaths <joueur>` | `arcadia.adminpanel.deathrestore` | Parcourir et restaurer les instantanés de mort |
| `giveitem <joueur> <objet> [quantité]` | `arcadia.adminpanel.giveitem` | Donner un objet par son id |
| `mail <joueur> <message>` | `arcadia.adminpanel.mail` | Laisser un message à un joueur hors ligne |
| `sessions` | `arcadia.adminpanel.sessions` | Classement du temps de jeu et des sessions |
| `afklist` | `arcadia.adminpanel.afk` | Qui est inactif et depuis combien de temps |
| `alts` | `arcadia.adminpanel.alts` | Comptes partageant une empreinte de connexion |
| `clientmods` | `arcadia.adminpanel.clientmods` | Listes de mods déclarées par les clients connectés |
| `lag` | `arcadia.adminpanel.performance` | Résumé de performances en une ligne dans le chat |
| `lagpanel` | `arcadia.adminpanel.performance` | Ouvrir le panneau de performances |
| `chunks` | `arcadia.adminpanel.chunks` | Ouvrir le navigateur de chunks |
| `world` | `arcadia.adminpanel.world` | Ouvrir le contrôle du monde |
| `radar` | `arcadia.adminpanel.radar` | Qui est à proximité |
| `chatlock` | `arcadia.adminpanel.chatcontrol` | Verrouiller ou déverrouiller le chat public |
| `clearchat` | `arcadia.adminpanel.chatcontrol` | Faire défiler le chat de tout le monde |
| `restart <minutes> [raison]` / `restart cancel` | `arcadia.adminpanel.restart` | Programmer ou annuler un redémarrage |
| `broadcast` / `broadcast toggle` | `arcadia.adminpanel.broadcast` | Envoyer la prochaine annonce, ou basculer la rotation |
| `cmdspy` / `socialspy` | `arcadia.adminpanel.spy` | Basculer les flux commandes et messages privés |
| `silent` | `arcadia.adminpanel.silent` | Basculer le mode silencieux (aucune annonce publique) |
| `select <joueur>` / `selectclear` | `arcadia.adminpanel.bulk` | Constituer ou vider la sélection groupée |
| `back` | `arcadia.adminpanel.back` | Revenir à sa position d'avant la dernière téléportation du panel |
| `disguise <joueur> baby <on\|off>` | `arcadia.adminpanel.disguise` | Forme bébé du déguisement |
| `disguise <joueur> scale <0.25-4.0>` | `arcadia.adminpanel.disguise` | Échelle de rendu (purement visuelle) |
| `disguise <joueur> name <on\|off>` | `arcadia.adminpanel.disguise` | Afficher le nom du mob au-dessus du déguisement |
| `disguise <joueur> random` | `arcadia.adminpanel.disguise` | Mob vivant aléatoire |
| `disguise <joueur> show` | `arcadia.adminpanel.disguise` | Afficher le déguisement actuel et ses options |
| `disguise --all <entité>` | `arcadia.adminpanel.disguise` | Déguiser tous les joueurs connectés |
| `disguise --random` | `arcadia.adminpanel.disguise` | Donner un déguisement aléatoire à chaque joueur |
| `disguise --clear` | `arcadia.adminpanel.disguise` | Retirer tous les déguisements |
| `disguise --list` | `arcadia.adminpanel.disguise` | Lister les joueurs déguisés |

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
| `arcadia.adminpanel.disguise` | Déguiser un joueur en mob (`disguise …`) |
| `arcadia.adminpanel.vanish.see` | Voir les autres membres du staff invisibles. Séparé de `.vanish` pour pouvoir se cacher d'un stagiaire. |

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
2. Placez `arcadia-admin-panel-1.3.1.jar` dans votre dossier `mods/`
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
