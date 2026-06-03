# Error Log

Self-improvement log. Check this before starting work and apply the prevention rules.

---

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
