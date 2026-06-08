# Error Log

Self-improvement log. Check this before starting work and apply the prevention rules.

---

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
