# WipeTogether 1.2.1 — release notes

Status: **published on GitHub on October 29, 2026**: [release and downloads](https://github.com/hildebrandchase/WipeTogether/releases/tag/v1.2.1). The release includes the plugin JAR and a SHA-256 checksum. Publication of 1.2.1 on Hangar or other sites is not yet confirmed; the remaining notes can be reused when publishing there.

## Native hardcore mode

- Use `hardcore=true` and `difficulty=hard` in `server.properties`.
- New run worlds have hardcore enabled, and existing run/default worlds have their hardcore flags repaired while preserving terrain, seeds, and records.
- The normal hardcore death screen and Spectate World choice remain available. At the next-world transfer, dead players are respawned and everyone is restored to survival.
- Players who choose Title Screen or disconnect as spectators join the current run in survival when they return. Reconnecting during the countdown waits for the transfer.

## Configurable presentation

- Customize chat announcements, five title styles and their timings, scoreboard/TAB text, and admin feedback in the new `messages.yml`.
- Use MiniMessage colors/styles and placeholders for names, countdown seconds, run number, final time, team, and seed.
- Hide individual titles or chat messages without changing gameplay or saved records.
- Missing options use defaults; invalid option values or formatting warn and fall back safely. Malformed YAML is preserved and stops plugin startup for correction.
- Settings now have a numbered layout, inline help, and a [settings guide](SETTINGS.md).

## Upgrade

Stop and back up your server. Replace the old plugin JAR with `WipeTogether-1.2.1.jar`, keep the existing plugin data and world folders, enable `hardcore=true`, and fully restart. Reconnect so clients receive the corrected hardcore flag. The plugin creates `messages.yml` automatically; existing game records do not need to be deleted or moved.

The author reported the core hardcore/reset flow working in gameplay. Configurable presentation is covered by automated formatting and lifecycle tests; its final appearance still benefits from a client playtest.

Validation: **84 automated tests passed**, with no failures, errors, or skipped tests. The packaged plugin includes the editable defaults and MIT license; Paper and test libraries are not bundled.
