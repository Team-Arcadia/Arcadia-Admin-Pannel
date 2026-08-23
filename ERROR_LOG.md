# Error Log

Self-improvement log. Check this before starting work and apply the prevention rules.

---

## [2026-08-23 19:20] — Permission bitmask silently wraps past 32 nodes

**Context:** Adding 28 permission nodes for the 1.3.0 tooling, taking `AdminPermissions` from 23 constants to 51.
**Error:** No exception, no warning. `AdminPermissions.check()` cached results as `int flags` with `1 << ordinal()`, so from the 33rd constant onward the shift wraps: node 32 reads node 0's answer, node 33 reads node 1's. A moderator holding `arcadia.adminpanel.open` would silently have been granted `arcadia.adminpanel.vanish`.
**Root cause:** A bitmask keyed on an enum ordinal has a hard ceiling that nothing enforces. Java defines `1 << 32` as `1 << 0` rather than 0, so the failure is a wrong answer instead of a crash, and every wrong answer is a privilege escalation.
**Fix:** Replaced the mask with a `boolean[]` sized from `values().length`, filled in one pass on a cache miss. One small allocation per player per 2-second window; no ceiling.
**Prevention:** Never index a fixed-width bitmask by an enum ordinal in a set that is expected to grow, and never in a permission check. If a mask is genuinely needed for performance, assert `values().length <= 64` at class-init time so the build fails instead of the gate.

---

## [2026-08-23 17:05] — Un-vanishing by re-adding the entity would reload every chunk

**Context:** Implementing server-side vanish. Hiding is easy (`ClientboundRemoveEntitiesPacket`); making the player reappear is the hard half, because the server-side tracker still believes the observer can see them and therefore never re-sends a spawn packet.
**Error:** No exception. The obvious fix, `ServerChunkCache.removeEntity(player)` followed by `addEntity(player)`, does work, but `ChunkMap.updatePlayerStatus(player, false)` applies an empty chunk-tracking view first: the vanished player's own client is told to forget every chunk it holds, then re-sent all of them. On a heavy modpack at view distance 12 that is roughly 600 chunks, a multi-second freeze and a large bandwidth spike, every time somebody toggles vanish.
**Root cause:** Reaching for the coarsest API that produces the right visual result, without reading what else it touches. The tracker and the chunk view share `updatePlayerStatus`, so re-tracking an entity cannot be done without also re-sending its owner's world.
**Fix:** Build the pairing packets directly instead. A throwaway `ServerEntity(level, player, 0, false, p -> {})` and its public `sendPairingData(observer, acceptor)` produce exactly the spawn, metadata, equipment and rotation packets the tracker sent the first time. The server-side `seenBy` set was never modified, so movement and animation updates resume on their own.
**Prevention:** Before using a server API that "makes the client see this again", read what it does to chunk tracking. On a modded server anything that touches `updatePlayerStatus` is a multi-second operation for the affected player.

---

## [2026-08-23 18:40] — Per-player entity counts were players times entities on the tick thread

**Context:** The performance panel attributes nearby entities to each player so staff can see whose build is loading the server.
**Error:** No exception. The first implementation ran a radius query per player over `level.getEntities().getAll()`. With 40 players and 20k entities that is 800k distance checks in one synchronous call, on the tick thread, which is precisely the lag the panel exists to diagnose.
**Root cause:** Writing the query the way it reads in the requirement ("entities within N blocks of the player") instead of reusing the pass that had just been done. The same loop had already bucketed every entity by chunk two lines above.
**Fix:** Sum the existing chunk buckets over the chunks within the radius. Cost drops to players times chunks-in-radius, a couple of hundred map lookups each, and chunk granularity is still precise enough to point at the right base.
**Prevention:** When a diagnostic walks a large collection, walk it once and answer every question from that pass. A profiler that costs a visible tick is worse than no profiler.

---

## [2026-08-23 15:30] — StoredUserEntry.getUser() is not visible outside its package

**Context:** Building the ban-list screen from `PlayerList.getBans().getEntries()`.
**Error:** `The method getUser() from the type StoredUserEntry<GameProfile> is not visible`.
**Root cause:** `StoredUserEntry#getUser` is package-private in `net.minecraft.server.players`. The profile behind a ban entry is simply not reachable from outside that package without an access transformer.
**Fix:** Read the name from the public `BanListEntry#getDisplayName()`, and resolve the UUID from the panel's own in-memory offline cache. Deliberately not `GameProfileCache#get(String)`: on a cache miss that falls through to a blocking Mojang lookup, on the server thread, once per banned player. Unban acts on the entry itself through the public `StoredUserList#remove(StoredUserEntry)`, so a row whose UUID could not be resolved is still actionable.
**Prevention:** When a vanilla getter is not visible, check whether the surrounding class already exposes what you need another way before reaching for an access transformer. And never call a profile-cache lookup by name on the tick thread.

---

## [2026-08-23 16:10] — ItemArgument needs a CommandBuildContext a grafted subtree does not have

**Context:** Adding `giveitem <player> <item> [count]` to the 1.3.0 command subtree, which is built in its own class and grafted onto the existing root.
**Error:** `ItemArgument.item(...)` requires a `CommandBuildContext`, which is only handed to `RegisterCommandsEvent` and was not threaded into the subtree builder.
**Root cause:** Vanilla argument types that resolve registry entries are context-dependent by design; a builder that does not receive the context cannot use them.
**Fix:** Used a plain string argument with a suggestion provider over `BuiltInRegistries.ITEM.keySet()`, and validated the id against the registry at execution. Tab completion still covers every modded item, and an unknown id produces a translated error instead of a parse failure.
**Prevention:** Either thread `CommandBuildContext` through every command builder from the start, or use string arguments with registry suggestions for anything registry-shaped. Do not discover the dependency halfway through a large tree.

---

## [2026-08-23 12:15] — Quoted heredocs lose their quoting through the Bash tool

**Context:** Writing large Java and Markdown files from the shell with `cat > file <<'EOF'`.
**Error:** `unexpected EOF while looking for matching '`, with the file never created. Reproducible whenever the content contains backticks or apostrophes; identical commands with plain prose succeed.
**Root cause:** The quoting on the heredoc delimiter is not preserved by the time the command reaches bash, so the body is re-parsed: backticks open command substitution and apostrophes open quotes, and the parse runs off the end of the input.
**Fix:** Write file content with a dedicated write tool, and for splicing into an existing file, write a small Python script to disk and run it with arguments rather than piping a heredoc into the interpreter.
**Prevention:** Never put source code, Markdown with inline code, or French prose through a shell heredoc. Content with backticks or apostrophes goes through a file, always.

---

## [2026-08-11 10:20] — Cancelling ServerChatEvent does not keep a message off a Discord bridge (issue #245)

**Context:** Staff chat typed with `/arcadia_adminpanel stafftoggle` on was showing up on Discord, while the exact same text sent through `/arcadia_adminpanel staffchat <message>` was not.
**Error:** No exception. `ChatListener.onChat()` cancelled the `ServerChatEvent` at `EventPriority.HIGHEST` and the message never reached public in-game chat, yet the Discord bridge relayed it in full.
**Root cause:** `event.setCanceled(true)` is a request the rest of the chat pipeline is free to ignore. A bridge that registers with `receiveCanceled = true`, registers at the same `HIGHEST` priority but earlier in mod load order, mixins ahead of the event (`handleChat` / `chat`), or simply tails the chat log, all see the message regardless. There is no event priority that wins this reliably, because the leak is not an ordering problem — the message is a real chat message and anything hooked to the pipeline can read it.
**Fix:** Apply the toggle before the message leaves the client. `StaffChatClientHandler` cancels `ClientChatEvent` while staff-chat mode is on and re-sends the line as `/arcadia_adminpanel staffchat <message>` (the command path already proven not to be intercepted); the toggle state is synced with the new `S2CStaffChatState` payload. The server-side cancel is kept only as a safety net for the window before the state packet lands.
**Prevention:** To keep content out of a pipeline, do not let it enter the pipeline — a cancel flag only stops consumers that check it, and third-party mods often do not. When rerouting a chat path onto a command path, re-check every guard the chat path enforced: the `staffchat` command had no mute check, so the reroute would have silently turned staff chat into a mute bypass.

## [2026-08-07 11:05] — Vanilla TagParser cannot read FTB Library SNBT (issue #219)

**Context:** #208 was closed in 1.2.10 by removing a spurious permission gate on the homes grid, but homes were still invisible in 1.2.11 testing. Investigated the data path instead of the render path.
**Error:** `FTBDataReader.readPlayerData()` returned `null` for every player. Only visible as `LOGGER.debug`, so nothing showed in the server log: `Expected '}' at position 19: ...: false<--[HERE]`.
**Root cause:** FTB Essentials / Teams / Chunks all serialise through FTB Library's SNBT writer, which separates compound entries and list elements with **line breaks, not commas**. Vanilla `TagParser.readStruct()` breaks out of its entry loop as soon as `hasElementSeparator()` finds no `,`, then throws on `expect('}')` — so `TagParser.parseTag()` fails on *every* FTB file, not just malformed ones. Three readers were affected; `FTBTeamsReader` masked it behind a regex fallback (which is why only homes and claim stats visibly broke), and its `sanitizeSnbt()` only stripped comments and trailing commas, never the actual quirk.
**Fix:** New `SnbtCompat.parse()` — tries the strict vanilla parse first, then re-parses a normalised copy that inserts the implicit separators at line breaks (string-aware, so braces/commas inside quoted values are untouched), strips `#` and `//` line comments, and drops trailing commas. All three readers now go through it.
**Prevention:** Never assume a third-party `.snbt` is vanilla-parseable — FTB's format is a superset. When a reader can silently return "no data", log the parse failure at `warn`, not `debug`: a `debug`-level swallow turned a total parse failure into an invisible empty UI for several releases. Verify a parser against a real on-disk file from the target mod version before shipping.

## [2026-07-04 17:40] — Homes hidden by a render gate inconsistent with the action gate (issue #208)

**Context:** 1.2.6 permission hardening added a visibility gate on the homes grid in `PlayerDetailMenu.buildMenu()`: `canUseCommand("tp") && AdminPermissions.TELEPORT.check(admin)`.
**Error:** Player homes disappeared from the admin panel for every admin who drives the panel via `arcadia.adminpanel.*` LuckPerms nodes but is not a vanilla OP level 2. Reported as "les home ne sont plus visible" — worked before 1.2.6.
**Root cause:** Two-fold. (1) `canUseCommand("tp")` checks the vanilla `/tp` command node, which needs OP level 2 — but `executeTeleport()` calls `admin.teleportTo(...)` directly and never runs `/tp`, so that gate was spurious. (2) The render gate did not match the click handler, which re-checks only `AdminPermissions.TELEPORT` (not `/tp`). A render gate stricter than its own action gate hides content the user is actually allowed to act on.
**Fix:** Render homes for any panel viewer (like the teleport-history row already did); keep the teleport action gated on `AdminPermissions.TELEPORT` at the click layer; show the "click to teleport" hint only when the viewer holds the node. `canTeleport` is now `AdminPermissions.TELEPORT.check(admin)` — the spurious `canUseCommand("tp")` dropped.
**Prevention:** A render-time visibility gate must never be stricter than the action gate it fronts, or it hides content the user can use. When gating on `canUseCommand("<vanilla>")`, first confirm the action actually invokes that vanilla command — direct API calls (`teleportTo`, etc.) must be gated on the granular node only.

## [2026-06-08 14:50] — Menu soft-lock from a blanket permission gate before navigation slots

**Context:** Hardening `TeamListMenu.clicked()` for 1.2.6 — added a `TEAMS` node re-check (layer 2) so a forged packet can't drive the team browser without the node.
**Error:** Placed `if (!AdminPermissions.TEAMS.check(sp)) return;` at the TOP of `clicked()`, before the back/pagination/toggle slot dispatch. If the viewer's permission flushed mid-session (2 s cache TTL, `/reload` → `invalidateAll()`, or a LuckPerms rank change), the back button stopped working and the staff member was trapped in the menu until disconnect/death.
**Root cause:** A two-layer permission gate must protect only the *privileged content action*, not menu navigation. Navigation (back, paginate, toggle) must always stay reachable so a permission change can't strand a player in a server-side container.
**Fix:** Moved the `TEAMS` re-check to AFTER the navigation-slot returns, just before the "open team detail" content block — matching how `TeamDetailMenu` already structures it. Caught by an adversarial code-review pass before commit.
**Prevention:** When adding a per-action permission re-check inside a `clicked()` handler, gate the specific content branch, never the whole method. Always verify back/close/pagination slots run before any `return` from a permission check. Review every server-side menu's `clicked()` for "free navigation, gated actions".

## [2026-06-03 10:40] — Non-exhaustive switch after adding enum constant

**Context:** Added `ENEMY` to `FTBTeamsReader.Rank` (to match FTB Teams' real serialized ranks).
**Error:** `TeamDetailMenu.rankColor` — "A Switch expression should cover all possible values".
**Root cause:** A switch *expression* (arrow form, no `default`) over an enum must cover every constant; adding a constant breaks every such switch elsewhere.
**Fix:** Added `case ENEMY -> "§c";`.
**Prevention:** After adding an enum constant, grep for switches over that enum (`switch (.*Rank`) and update each, or add a `default`.

## [2026-06-03 10:55] — Comparator.comparingLong generic inference failure

**Context:** Deterministic `resolveUUID` tie-break sorting `List<UUID>` by last-seen time.
**Error:** `comparingLong((UUID u) -> ...)` then `.reversed()` — "not applicable / cannot convert ToLongFunction<UUID> to ToLongFunction<? super T>".
**Root cause:** Chaining `.reversed()` directly on `Comparator.comparingLong(lambda)` leaves the type variable unbound; javac can't infer `T = UUID`.
**Fix:** Use an explicit type witness: `Comparator.<UUID>comparingLong(u -> ...).reversed()`.
**Prevention:** When chaining onto `Comparator.comparing*`, add the `<Type>` witness if the lambda parameter type is the only inference source.

## [2026-06-03 11:10] — Missing import after using a type added late

**Context:** `AdminPanelCommand.resolveUUID` referenced `LoginTracker.LoginRecord` for the tie-break.
**Error:** `package LoginTracker does not exist` / `cannot find symbol LoginTracker`.
**Root cause:** Used a class without importing it (the IDE flagged it as a warning earlier but it slipped to compile time).
**Fix:** Added `import com.arcadia.adminpanel.util.LoginTracker;`.
**Prevention:** When a new symbol is used in an edit, add its import in the same edit; treat IDE "cannot be resolved" diagnostics as blocking, not advisory.
