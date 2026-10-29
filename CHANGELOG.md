# Changelog

## 1.2.1 — 2026-10-29

Published on [GitHub Releases](https://github.com/hildebrandchase/WipeTogether/releases/tag/v1.2.1). Publication of this version on Hangar or other sites is not yet confirmed.

### Configurable presentation

- Add a separate, commented `messages.yml` for chat announcements, countdown/victim/teammate/manual-reset titles, welcome and victory titles, sidebar title, TAB text, and admin feedback.
- Support MiniMessage colors and styles, safe text placeholders, configurable title timings, individual title toggles, and blank chat messages.
- Validate presentation settings at startup; use defaults for invalid option values or formatting without interrupting resets. Preserve malformed YAML and existing custom files.
- Create the presentation file automatically for existing installations without moving or clearing saved runs, players, deaths, or wins.
- Replace the next-tick hardcoded countdown overlay with one configured title per audience so custom titles are not overwritten.
- Refresh the gameplay settings layout, preserve comments when creating configuration files, and add a settings guide and examples.
- Verify the combined update with 84 automated tests covering presentation settings and gameplay regression paths. No new client visual playtest is claimed for configurable presentation.

### Hardcore behavior

- Change the recommended server setting to `hardcore=true`.
- Create the Overworld, Nether, and End with hardcore enabled and Hard difficulty.
- Repair hardcore settings on restored run worlds and retained default dimensions before players log in, preserving terrain, seeds, and records.
- Preserve the vanilla hardcore death screen and Spectate World choice by disabling immediate respawn. At the next-world transfer, respawn anyone still dead and restore survival after teleporting.
- Support returning to the current run after leaving from the death screen or disconnecting as a spectator. Rejoining before the countdown finishes preserves the old-run state until transfer.
- Add regression tests for hardcore creation, existing-world repair, dead-player migration, and death-screen/spectator disconnect and rejoin paths.

## 1.2.0 — WipeTogether

- Rename the project, Java package, plugin entry point, messages, and Maven artifact to WipeTogether.
- Describe the plugin as a hardcore reset plugin for teams playing hardcore.
- Migrate the legacy HardcoreReset data folder without discarding saved runs, death counts, or wins; reject ambiguous duplicate folders and simultaneous old/new installations.
- Add `wipetogether.admin`, retaining the legacy permission as an alias.
- Reject malformed configuration instead of continuing with defaults or overwriting the original file.
- Save configuration through a flushed temporary file and atomic replacement.
- Require a successful run-state save before updating active runtime identity or retiring old worlds; retain worlds and retry on failure.
- Replace the removed-in-future attribute lookup with the typed maximum-health attribute.
- Use Adventure APIs for countdown title overlays and TAB display updates.
- Align plugin metadata with the Maven version, correct the minimum API declaration, and remove placeholder metadata and encoding artifacts.
- Add persistence/migration regression tests and a GitHub Actions build.
- Add an MIT license to the source and packaged plugin.

## 1.1.1 — previous project name

- Support modern Paper dimension storage alongside legacy separate world folders.
- Recover genuinely missing active worlds at the same run number.
- Confine cleanup to retired generated dimensions.

## 1.1.0 — previous project name

- Add dragon victory, persistent records, frozen final time, and harmless celebration fireworks.
- Add run/death administration and manual world resets.
- Reset player advancements and recipes between attempts.
