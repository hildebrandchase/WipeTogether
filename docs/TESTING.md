# Testing and compatibility

## Automated validation

Local verification on October 29, 2026: **84 tests passed**, with no failures, errors, or skips, on Windows with JDK 21.

Version 1.2.1 includes regression coverage for hardcore flags, repair of existing worlds, dead-player migration, and disconnect/rejoin paths from the death screen and spectator mode. The client heart display and death-screen appearance still require a live playtest.

The author subsequently reported the core hardcore/reset flow working in gameplay. The configurable presentation update has no new client playtest. Its automatic tests exercise all bundled message fields, custom colors/text/timings, literal name placeholders, blank messages, title toggles, malformed formatting/YAML, invalid value types, timing limits, file preservation, and actual reset/victory/command/TAB integration. Version 1.2.1 was [published on GitHub](https://github.com/hildebrandchase/WipeTogether/releases/tag/v1.2.1) on October 29, 2026; publication on other sites still requires confirmation.

`mvn clean verify` compiles against Paper 1.21.4 with Java 21 and executes the JUnit/Mockito regression suite. The suite covers fresh seeds and dimensions, reset retries, portal routing, player profile resets, commands, victory, timers, legacy/modern storage, strict YAML loading, atomic saves, migration, and retention/retry after a failed run-state commit.

The lifecycle tests use mocked worlds and players. Filesystem tests use real temporary directories. A passing suite does not certify terrain generation, client visuals, all Paper versions, or third-party plugin compatibility.

The packaged JAR should contain only WipeTogether classes/resources and Maven metadata. Paper and test libraries must not be included. The packaged `plugin.yml` must contain the Maven version, `com.wipetogether.WipeTogetherIntegrated`, and `wipetogether.admin`.

## Server playtest

Use a disposable server and two clients before promoting a build to a gameplay-tested release. Keep the server's required Java version separate from the Java 21 build requirement.

1. Set `hardcore=true` and `difficulty=hard` in a disposable Paper server's `server.properties`, then install the JAR. Confirm WipeTogether enables, creates configuration, and prepares three standby dimensions.
2. Join with two players, record `/seed`, place a recognizable block, and visit both dimensions. Verify hardcore hearts after login, reconnects, and Nether/End travel.
3. Kill a player. Confirm the normal hardcore death screen appears. First leave it open through the countdown; on a later attempt choose Spectate World before the countdown ends. In both cases confirm the victim is teleported to the next world in survival, with hardcore hearts. Check the countdown, titles, sound, incremented run number, changed seed, and old-world cleanup.
4. Reset again before standby preparation finishes. Confirm another fresh seed and no reused terrain.
5. Test both disconnect paths: choose Title Screen on the hardcore death screen, then on another attempt choose Spectate World and disconnect. Let the reset finish while the victim is offline; reconnect and confirm the current world, survival mode, and reset inventory, ender chest, XP, advancements, and recipes. Also test rejoining before the countdown ends, and a normal reconnect after progressing in the new run; neither should wipe progress prematurely or twice.
6. Stop and restart. Confirm the same run, seed, player progress, and accumulated timer resume.
7. Exercise all admin commands from console, an operator, a user with `wipetogether.admin`, a user with only the legacy permission, and a user with no permission.
8. Defeat the active dragon and enter its exit portal. Confirm one victory record, harmless fireworks, a frozen timer, and visible titles after the credits. Test a second returner and another restart.
9. Use `/resetworld` after victory. Check that the world/timer reset while death counts, run number, and historical wins remain.
10. Stop the server, make the configuration YAML invalid, and restart. Verify startup stops, the file remains byte-for-byte unchanged, and no worlds are deleted. Restore the valid backup.
11. On disposable data only, simulate an unwritable config destination during a reset. Confirm errors are logged, old worlds remain, and the reset completes once saving succeeds. Restart and verify the committed world loads.
12. Upgrade a copy of legacy data with only the old folder present. Confirm migration preserves records and active worlds. With both folders present, confirm startup refuses to choose or overwrite either one.

13. Upgrade a copy of a pre-1.2.1 run whose worlds were created without hardcore enabled. Enable `hardcore=true`, fully restart, and reconnect. Confirm hardcore hearts in all three run dimensions, unchanged terrain/seed/win records, and successful death/manual resets. Check that `/gamemode spectator` still works for an administrator.
14. Customize the victim, teammate, manual-reset, welcome, and victory titles in `messages.yml`. Check that the correct audience sees each title and that no hardcoded overlay replaces it. Test a disabled title and blank chat message; the reset/victory must still complete. Confirm custom TAB/sidebar text, command feedback, and permission denial. Restart with a partial file and an invalid timing/formatting value; defaults should apply with a warning while custom fields and saved game data remain intact.

## Investigation notes

The world-creation code previously omitted `WorldCreator.hardcore(true)`, while the API defaults that flag to false. [Paper's login path](https://github.com/PaperMC/Paper/blob/ver/1.21.4/paper-server/patches/sources/net/minecraft/server/players/PlayerList.java.patch) sends the spawn world's hardcore flag before the join event, explaining why world flags must be repaired before login. [Paper's hardcore-death path](https://github.com/PaperMC/Paper/blob/ver/1.21.4/paper-server/patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch) applies its spectator transition after respawn. WipeTogether permits that vanilla behavior, then restores survival after a successful next-world teleport. Offline catch-up uses the persisted run-world marker, which is tested through a real YAML save/reload. The client buttons themselves are represented by their server-side states in automated tests; a live visual result is not claimed.

## Compatibility scope

Paper 1.21.4 is the compilation target and declared minimum API version. Code supports the legacy world layout and Paper 26.1+ dimension layout, including clockless Nether/End dimensions. Do not infer a fully tested version range from the API declaration.

An earlier development README reported a Paper 26.3 / Java 25 server smoke test for version 1.1.1. The 1.2.1 build has not yet completed the multiplayer checklist above. Record the exact server build, Java runtime, and checklist outcomes when testing it.
