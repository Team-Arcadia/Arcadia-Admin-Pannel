# Project Rules & AI/IDE Instructions

> This file is the single source of truth for any developer, AI assistant, or IDE working on this project.
> Read this ENTIRELY before making any change.

---

## 1. Project Identity

| Field | Value |
|---|---|
| **Project** | Arcadia Admin Panel |
| **Mod ID** | `arcadiaadminpanel` |
| **Package** | `com.arcadia.adminpanel` |
| **Mod Loader** | NeoForge 21.1+ |
| **Minecraft** | 1.21.1 |
| **Java** | 21 |
| **Author** | vyrriox |
| **License** | All Rights Reserved |
| **Dependency** | Arcadia Lib (`com.arcadia.lib`) in `libs/arcadia-lib-*.jar` |

---

## 2. Git Workflow

### Branch Strategy

```
main        <- Production. Tagged releases only. NEVER push directly.
staging     <- QA / testing. Merge develop here when ready to test.
develop     <- Daily work. All features and fixes merge here first.
hotfix      <- Emergency patches. Branch from main, merge back to main + develop.
feat/*      <- Feature branches. Branch from develop.
fix/*       <- Bug fix branches. Branch from develop.
```

### Rules

- **NEVER push directly to `main`.** Always go through `develop` -> `staging` -> `main`.
- **NEVER force push** (`--force`) on any shared branch.
- **Feature branches** must be named `feat/<short-description>` (e.g. `feat/player-search`).
- **Fix branches** must be named `fix/<short-description>` (e.g. `fix/npe-screen-open`).
- **Hotfix** is for critical production bugs only. Branch from `main`, fix, merge to `main` AND `develop`.
- **Delete feature/fix branches** after merge.

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add player search bar
fix: NPE when opening admin panel
refactor: simplify WarnManager storage logic
perf: async database writes for warns
docs: update README commands section
chore: update CI workflow
```

- Present tense, lowercase, no period at end.
- First line max 72 characters.
- Reference issues: `fix: resolve crash on mute (#42)`

### Releasing

```bash
# 1. Merge develop -> staging (test)
# 2. Merge staging -> main (release)
# 3. Tag and push:
git tag v1.2.1
git push origin v1.2.1
# GitHub Actions auto-builds and publishes the release.
```

---

## 3. Code Conventions

### Language Policy

| Context | Language |
|---|---|
| Code (variables, methods, classes) | **English** |
| Comments | **English**, minimal |
| UI text / chat messages | **English + French** via `LanguageHelper` |
| Documentation (README, CHANGELOG) | **English first, then French** |
| Git commits | **English** |

### Naming

| Element | Convention | Example |
|---|---|---|
| Classes | `PascalCase` | `AdminPanelMenu` |
| Methods / fields | `camelCase` | `buildMenu()`, `currentPage` |
| Constants | `UPPER_SNAKE_CASE` | `ITEMS_PER_PAGE` |
| Packages | `lowercase` | `com.arcadia.adminpanel.gui` |

### Architecture Rules

- **Use Arcadia Lib APIs** whenever possible:
  - `ItemBuilder` for all `ItemStack` creation (never manual `DataComponents`)
  - `ArcadiaMessages` for all chat messages (consistent prefix/styling)
  - `SoundHelper` for sound playback
  - `MessageHelper` for titles/action bar
  - `StaffService` / `StaffActions` for moderation checks
  - `TextFormatter` for formatting numbers/durations

- **Thread Safety**:
  - Use `ConcurrentHashMap` for shared maps
  - Use `Collections.synchronizedList()` for shared lists
  - Database operations MUST go through `DatabaseManager.executeAsync()`
  - NEVER block the server thread with I/O

- **Menu System**:
  - All menus extend `ChestMenu` with `MenuType.GENERIC_9x6`
  - NO custom `MenuType` registration (causes mod compatibility issues)
  - Client theming via `ScreenEvent.Opening` interception (title matching)
  - Menu items built server-side in `buildMenu()`, synced to client automatically

- **Localization**:
  - ALL user-facing text MUST go through `LanguageHelper`
  - Every key MUST have both EN and FR translations
  - Language detected automatically via `player.clientInformation().language()`

### What NOT To Do

- Do NOT register custom `MenuType` via `DeferredRegister` (breaks `immersive_melodies`)
- Do NOT modify Arcadia Lib source code without explicit permission
- Do NOT add new dependencies without discussion
- Do NOT use `§` color codes in `Component` — use `ChatFormatting` or `withStyle()`
- Do NOT store sensitive data (passwords, tokens) in code or config committed to git
- Do NOT increment version numbers unless explicitly asked

---

## 4. Project Structure

```
src/main/java/com/vyrriox/arcadiaadminpanel/
  |
  +-- AdminPanelMod.java            # Entry point (@Mod). Event registration.
  |                                  # DO NOT add DeferredRegister here.
  |
  +-- client/                        # CLIENT-SIDE ONLY (Dist.CLIENT)
  |   +-- AdminPanelClient.java      # ScreenEvent.Opening interceptor
  |   +-- screen/
  |       +-- ThemedContainerScreen  # Base class: ArcadiaTheme rendering
  |       +-- AdminPanelScreen       # Main panel + EditBox search bar
  |       +-- PlayerDetailScreen     # Player detail themed view
  |       +-- WarnListScreen         # Warning list themed view
  |
  +-- command/
  |   +-- AdminPanelCommand.java     # ALL commands under /arcadia_adminpanel
  |                                  # Includes: panel, reload, warn, warnlist,
  |                                  # delwarn, clearwarns, checkwarn,
  |                                  # staffchat, stafftoggle, stafflist,
  |                                  # mute, unmute
  |
  +-- data/
  |   +-- WarnTableDefinition.java   # MySQL CREATE TABLE statement
  |
  +-- event/
  |   +-- ChatListener.java          # Chat interception for:
  |                                  # - Mute enforcement (HIGHEST priority)
  |                                  # - Staff chat toggle redirect
  |                                  # - Warn session (chat input)
  |                                  # - Search session (chat input)
  |                                  # - Disconnect cleanup
  |
  +-- gui/
  |   +-- AdminPanelMenu.java        # Player list menu (9x6 chest)
  |   +-- PlayerDetailMenu.java      # Player actions menu (9x6 chest)
  |   +-- WarnListMenu.java          # Warning history menu (9x6 chest)
  |
  +-- util/
      +-- WarnManager.java           # Dual storage: MySQL or JSON
      +-- OfflinePlayerManager.java  # Async FTB player scanner
      +-- FTBDataReader.java         # SNBT file parser (homes, last_seen, tp_history)
      +-- LanguageHelper.java        # EN/FR translation map
      +-- SkullCache.java            # GameProfile cache for player heads

src/main/resources/
  +-- META-INF/neoforge.mods.toml   # Mod metadata + dependencies
  +-- pack.mcmeta                    # Resource pack metadata
```

---

## 5. Adding a New Feature (Step by Step)

```
1. git checkout develop
2. git pull origin develop
3. git checkout -b feat/my-feature
4. Write code following conventions above
5. Add EN + FR translations in LanguageHelper.java
6. Test locally: ./gradlew build && test in-game
7. git add . && git commit -m "feat: description"
8. git push origin feat/my-feature
9. Open PR: feat/my-feature -> develop
10. After review: merge, delete branch
```

---

## 6. Adding a New Command

1. Add the command in `AdminPanelCommand.register()` under the `arcadia_adminpanel` literal
2. Use `StaffService.requireRole()` for staff permission checks
3. Use `source.hasPermission(2)` for op-level checks
4. Add player suggestions with `PLAYER_SUGGESTIONS` provider
5. Use `ArcadiaMessages.success/error/info()` for feedback
6. Add translations in `LanguageHelper` (both EN and FR maps)

---

## 7. Adding a New Menu Button

1. In the appropriate menu class (`PlayerDetailMenu`, etc.), find an empty slot
2. Create the item with `ItemBuilder.of(Items.XXX).name(...).addLore(...).build()`
3. Add the click handler in the `clicked()` method (switch on slotId)
4. Check permissions with `canUseCommand()` or `StaffService.getRole()`
5. Add EN + FR translations for button name and lore

---

## 8. Testing Checklist

Before submitting any PR:

- [ ] `./gradlew build` passes with zero errors
- [ ] Tested in singleplayer
- [ ] Tested on dedicated server (if applicable)
- [ ] No crash with `immersive_melodies` present
- [ ] All new text has EN + FR translations
- [ ] No new `DeferredRegister` added
- [ ] Thread-safe for shared state
- [ ] Commit messages follow conventions

---

## 9. Environment Setup

```bash
# Clone
git clone https://github.com/Team-Arcadia/Arcadia-Admin-Pannel.git
cd Arcadia-Admin-Pannel

# The arcadia-lib JAR is in libs/ (committed to repo)

# Build
./gradlew build

# Run client (dev)
./gradlew runClient

# Run server (dev)
./gradlew runServer
```

### IDE Setup

- **IntelliJ IDEA**: Import as Gradle project. Run `./gradlew genIntellijRuns`.
- **Eclipse**: Run `./gradlew genEclipseRuns`.
- **VS Code**: Install Java Extension Pack. Open folder. Gradle tasks in terminal.

---

## 10. AI Assistant Instructions

If you are an AI (Claude, ChatGPT, Copilot, Cursor, etc.) working on this project:

1. **Read this file first.** Do not guess conventions.
2. **Never modify Arcadia Lib** unless explicitly told to.
3. **Never register DeferredRegister** for MenuType or any registry.
4. **Never push to main directly.** Work on `develop` or feature branches.
5. **Never increment version** unless the user asks for it.
6. **Always add EN + FR translations** for any new user-facing text.
7. **Always use Arcadia Lib APIs** (ItemBuilder, ArcadiaMessages, SoundHelper, etc.).
8. **Ask before making destructive changes** (deleting files, force push, changing architecture).
9. **Test with `./gradlew build`** before committing.
10. **Communicate in French** with the user, code in English.
