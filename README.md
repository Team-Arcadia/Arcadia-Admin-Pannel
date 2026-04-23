<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-brightgreen?style=for-the-badge&logo=mojangstudios" alt="Minecraft 1.21.1"/>
  <img src="https://img.shields.io/badge/NeoForge-21.1+-orange?style=for-the-badge" alt="NeoForge"/>
  <img src="https://img.shields.io/badge/Java-21-red?style=for-the-badge&logo=openjdk" alt="Java 21"/>
  <img src="https://img.shields.io/github/v/release/laforetbrut/Arcadia-Admin-Pannel?style=for-the-badge&label=Version&color=blue" alt="Version"/>
  <img src="https://img.shields.io/github/actions/workflow/status/laforetbrut/Arcadia-Admin-Pannel/build.yml?style=for-the-badge&label=Build" alt="Build"/>
  <img src="https://img.shields.io/github/license/laforetbrut/Arcadia-Admin-Pannel?style=for-the-badge" alt="License"/>
</p>

<h1 align="center">Arcadia Admin Panel</h1>

<p align="center">
  <b>Steampunk-themed server administration mod for Minecraft</b><br/>
  <i>Powered by <a href="https://github.com/Team-Arcadia">Arcadia Lib</a> | Built for NeoForge 1.21.1</i>
</p>

<p align="center">
  <a href="#features">Features</a> |
  <a href="#commands">Commands</a> |
  <a href="#installation">Installation</a> |
  <a href="#configuration">Configuration</a> |
  <a href="#contributing">Contributing</a> |
  <a href="#version-fran%C3%A7aise">Francais</a>
</p>

---

## Overview

Arcadia Admin Panel is a lightweight, optimized server management tool designed for Minecraft modded servers. It provides a complete GUI for player management, moderation, and a warning system with multi-server synchronization support. All interfaces use the Arcadia steampunk copper theme.

## Features

| Feature | Description |
|---|---|
| **Steampunk GUI** | Copper-themed interface via ArcadiaTheme (dark panels, riveted borders, brass accents) |
| **Player Search** | Real-time client-side search bar to filter players by name |
| **Player Management** | View online/offline players, teleport, view inventory, clear inventory, reset progress |
| **Warning System** | Warn players with reasons, view history, delete warns. Supports MySQL multi-server sync |
| **Staff Moderation** | Mute/unmute, kick, ban/unban players directly from the GUI |
| **Staff Chat** | Private staff communication channel with toggle mode |
| **Bilingual** | Automatic language detection (English/French) based on client settings |
| **Multi-Server** | Warns sync across servers via shared MySQL database (Arcadia Lib) |
| **FTB Integration** | View player homes, last seen location, teleport history (FTB Essentials) |
| **Permission-Aware** | Action buttons hidden based on permissions. Compatible with LuckPerms |
| **Optimized** | Thread-safe collections, async database operations, atomic file writes, tick-friendly |

## Commands

All commands use the prefix `/arcadia_adminpanel`.

### Administration
| Command | Permission | Description |
|---|---|---|
| `/arcadia_adminpanel panel [filter]` | Op Level 2 | Open admin panel (optionally filtered) |
| `/arcadia_adminpanel reload` | Op Level 2 | Reload player cache, FTB data, and warns |

### Warning System
| Command | Permission | Description |
|---|---|---|
| `/arcadia_adminpanel warn <player> <reason>` | Op Level 2 | Warn a player |
| `/arcadia_adminpanel warnlist <player>` | Op Level 2 | View player's warning history |
| `/arcadia_adminpanel delwarn <player> <index>` | Op Level 2 | Delete a specific warning |
| `/arcadia_adminpanel clearwarns <player>` | Op Level 2 | Clear all warnings for a player |
| `/arcadia_adminpanel checkwarn` | All | View your own warnings |

### Staff Moderation
| Command | Permission | Description |
|---|---|---|
| `/arcadia_adminpanel mute <player> <minutes> [reason]` | Staff MOD+ | Mute a player |
| `/arcadia_adminpanel unmute <player>` | Staff MOD+ | Unmute a player |
| `/arcadia_adminpanel staffchat <message>` | Staff HELPER+ | Send message to staff channel |
| `/arcadia_adminpanel stafftoggle` | Staff HELPER+ | Toggle staff chat mode |
| `/arcadia_adminpanel stafflist` | Staff HELPER+ | List online staff members |

## Installation

### Requirements
- Minecraft **1.21.1**
- NeoForge **21.1+**
- [Arcadia Lib](https://github.com/Team-Arcadia) **>= 1.2.0**

### Steps
1. Download the latest release from the [Releases](https://github.com/laforetbrut/Arcadia-Admin-Pannel/releases) page
2. Place `arcadia-lib-1.2.0.jar` in your `mods/` folder
3. Place `arcadia-admin-panel-1.2.1.jar` in your `mods/` folder
4. Start the server

### Client Installation (Optional)
Installing on the client enables the steampunk ArcadiaTheme rendering for all admin menus. The mod works without client installation (vanilla chest rendering).

## Configuration

### Warning Storage
By default, warnings are stored in `config/arcadia/arcadiaadminpanel/warns.json` (local JSON).

### Multi-Server Sync
To enable cross-server warning synchronization:
1. Configure MySQL in `config/arcadia/lib/database.toml`
2. Set `enabled = true`
3. All servers sharing the same database will sync warnings automatically

### Server ID
Each server identifies itself via the JVM property:
```
-Darcadia.server_id=server1
```

## Architecture

```
com.arcadia.adminpanel
  +-- AdminPanelMod.java           Entry point, event registration
  +-- client/
  |   +-- AdminPanelClient.java     Screen interception (ScreenEvent.Opening)
  |   +-- screen/
  |       +-- ThemedContainerScreen  Base ArcadiaTheme renderer
  |       +-- AdminPanelScreen       Main panel + search bar
  |       +-- PlayerDetailScreen     Player detail view
  |       +-- WarnListScreen         Warning history view
  +-- command/
  |   +-- AdminPanelCommand.java    All /arcadia_adminpanel commands
  +-- data/
  |   +-- WarnTableDefinition.java  MySQL table schema
  +-- event/
  |   +-- ChatListener.java         Warn/search/mute/staff chat handler
  +-- gui/
  |   +-- AdminPanelMenu.java       Main player list menu
  |   +-- PlayerDetailMenu.java     Player actions menu
  |   +-- WarnListMenu.java         Warning list menu
  +-- util/
      +-- WarnManager.java          Dual storage (MySQL + JSON)
      +-- OfflinePlayerManager.java Async offline player scanner
      +-- FTBDataReader.java        FTB Essentials SNBT parser
      +-- LanguageHelper.java       EN/FR localization
      +-- SkullCache.java           Player head texture cache
```

## Building from Source

```bash
git clone https://github.com/laforetbrut/Arcadia-Admin-Pannel.git
cd Arcadia-Admin-Pannel
./gradlew build
```

The compiled JAR will be in `build/libs/`.

## Contributing

We welcome contributions! Please read our [Contributing Guide](.github/CONTRIBUTING.md) before submitting a pull request.

## Links

- [Arcadia: Echoes of Power](https://arcadia-echoes-of-power.fr/)
- [Discord](https://discord.gg/xjF8Rtzyd4)
- [Donate](https://buy.stripe.com/3cI3co6X97Vy4IK50QfIs00)

## License

All Rights Reserved. See [LICENSE](LICENSE) for details.

## Credits

**Author:** vyrriox
**Organization:** [Team Arcadia](https://github.com/Team-Arcadia)

---

<h1 align="center">Arcadia Admin Panel (Version Francaise)</h1>

<p align="center">
  <b>Mod d'administration serveur au theme steampunk pour Minecraft</b><br/>
  <i>Propulse par <a href="https://github.com/Team-Arcadia">Arcadia Lib</a> | Construit pour NeoForge 1.21.1</i>
</p>

## Apercu

Arcadia Admin Panel est un outil de gestion serveur leger et optimise concu pour les serveurs Minecraft modes. Il fournit une interface complete pour la gestion des joueurs, la moderation et un systeme d'avertissement avec synchronisation multi-serveur.

## Caracteristiques

| Fonctionnalite | Description |
|---|---|
| **Interface Steampunk** | Theme cuivre via ArcadiaTheme (panneaux sombres, bordures rivetees, accents laiton) |
| **Recherche de Joueurs** | Barre de recherche temps reel pour filtrer les joueurs par nom |
| **Gestion Joueurs** | Voir joueurs en ligne/hors ligne, teleporter, voir inventaire, vider inventaire, reset progression |
| **Systeme d'Avertissement** | Avertir les joueurs, voir historique, supprimer warns. Support MySQL multi-serveur |
| **Moderation Staff** | Mute/unmute, kick, ban/deban directement depuis l'interface |
| **Chat Staff** | Canal de communication prive pour le staff avec mode toggle |
| **Bilingue** | Detection automatique de la langue (Anglais/Francais) selon le client |
| **Multi-Serveur** | Avertissements synchronises entre serveurs via MySQL partagee (Arcadia Lib) |
| **Integration FTB** | Voir les homes, derniere position, historique de teleportation (FTB Essentials) |
| **Permissions** | Boutons d'action caches selon les permissions. Compatible LuckPerms |
| **Optimise** | Collections thread-safe, operations DB async, ecriture atomique, tick-friendly |

## Commandes

Toutes les commandes utilisent le prefixe `/arcadia_adminpanel`.

### Administration
| Commande | Permission | Description |
|---|---|---|
| `/arcadia_adminpanel panel [filtre]` | Op Niveau 2 | Ouvrir le panneau admin (filtrage optionnel) |
| `/arcadia_adminpanel reload` | Op Niveau 2 | Recharger cache joueurs, donnees FTB et warns |

### Systeme d'Avertissement
| Commande | Permission | Description |
|---|---|---|
| `/arcadia_adminpanel warn <joueur> <raison>` | Op Niveau 2 | Avertir un joueur |
| `/arcadia_adminpanel warnlist <joueur>` | Op Niveau 2 | Voir l'historique des avertissements |
| `/arcadia_adminpanel delwarn <joueur> <index>` | Op Niveau 2 | Supprimer un avertissement specifique |
| `/arcadia_adminpanel clearwarns <joueur>` | Op Niveau 2 | Supprimer tous les avertissements |
| `/arcadia_adminpanel checkwarn` | Tous | Voir ses propres avertissements |

### Moderation Staff
| Commande | Permission | Description |
|---|---|---|
| `/arcadia_adminpanel mute <joueur> <minutes> [raison]` | Staff MOD+ | Rendre muet un joueur |
| `/arcadia_adminpanel unmute <joueur>` | Staff MOD+ | Retirer le mute |
| `/arcadia_adminpanel staffchat <message>` | Staff HELPER+ | Message dans le canal staff |
| `/arcadia_adminpanel stafftoggle` | Staff HELPER+ | Activer/desactiver le mode chat staff |
| `/arcadia_adminpanel stafflist` | Staff HELPER+ | Lister le staff en ligne |

## Installation

### Prerequis
- Minecraft **1.21.1**
- NeoForge **21.1+**
- [Arcadia Lib](https://github.com/Team-Arcadia) **>= 1.2.0**

### Etapes
1. Telecharger la derniere release depuis [Releases](https://github.com/laforetbrut/Arcadia-Admin-Pannel/releases)
2. Placer `arcadia-lib-1.2.0.jar` dans le dossier `mods/`
3. Placer `arcadia-admin-panel-1.2.1.jar` dans le dossier `mods/`
4. Demarrer le serveur

### Installation Client (Optionnel)
Installer sur le client active le rendu steampunk ArcadiaTheme pour tous les menus admin. Le mod fonctionne sans installation client (rendu vanilla).

## Compiler depuis les Sources

```bash
git clone https://github.com/laforetbrut/Arcadia-Admin-Pannel.git
cd Arcadia-Admin-Pannel
./gradlew build
```

Le JAR compile sera dans `build/libs/`.

## Contribuer

Les contributions sont les bienvenues ! Lisez notre [Guide de Contribution](.github/CONTRIBUTING.md) avant de soumettre une pull request.

## Liens

- [Arcadia: Echoes of Power](https://arcadia-echoes-of-power.fr/)
- [Discord](https://discord.gg/xjF8Rtzyd4)
- [Donation](https://buy.stripe.com/3cI3co6X97Vy4IK50QfIs00)

## Credits

**Auteur :** vyrriox
**Organisation :** [Team Arcadia](https://github.com/Team-Arcadia)
