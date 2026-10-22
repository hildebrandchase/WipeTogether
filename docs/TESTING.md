# Testing and compatibility

## Automated validation

Local verification on October 21, 2026: **55 tests passed**, with no failures, errors, or skips, on Windows with JDK 21. The final JAR was inspected for its entry point, filtered version, license, and dependency exclusions. The hosted GitHub Actions workflow has not run yet because the repository has not been pushed.

`mvn clean verify` compiles against Paper 1.21.4 with Java 21 and executes the JUnit/Mockito regression suite. The suite covers fresh seeds and dimensions, reset retries, portal routing, player profile resets, commands, victory, timers, legacy/modern storage, strict YAML loading, atomic saves, migration, and retention/retry after a failed run-state commit.

The lifecycle tests use mocked worlds and players. Filesystem tests use real temporary directories. A passing suite does not certify terrain generation, client visuals, all Paper versions, or third-party plugin compatibility.

The packaged JAR should contain only WipeTogether classes/resources and Maven metadata. Paper and test libraries must not be included. The packaged `plugin.yml` must contain the Maven version, `com.wipetogether.WipeTogetherIntegrated`, and `wipetogether.admin`.

## Server playtest

Use a disposable server and two clients before promoting a build to a gameplay-tested release. Keep the server's required Java version separate from the Java 21 build requirement.

1. Install the JAR on a clean Paper server. Confirm WipeTogether enables, creates configuration, and prepares three standby dimensions.
2. Join with two players, record `/seed`, place a recognizable block, and visit both dimensions.
3. Kill a player. Check the countdown, titles, sound, team migration, incremented run number, and changed seed. Verify old generated dimensions retire while default dimensions remain.
4. Reset again before standby preparation finishes. Confirm another fresh seed and no reused terrain.
5. Disconnect a player, reset, and reconnect. Confirm inventory, ender chest, XP, advancements, and recipes are reset. Reconnect within the same run and verify progress remains.
6. Stop and restart. Confirm the same run, seed, player progress, and accumulated timer resume.
7. Exercise all admin commands from console, an operator, a user with `wipetogether.admin`, a user with only the legacy permission, and a user with no permission.
8. Defeat the active dragon and enter its exit portal. Confirm one victory record, harmless fireworks, a frozen timer, and visible titles after the credits. Test a second returner and another restart.
9. Use `/resetworld` after victory. Check that the world/timer reset while death counts, run number, and historical wins remain.
10. Stop the server, make the configuration YAML invalid, and restart. Verify startup stops, the file remains byte-for-byte unchanged, and no worlds are deleted. Restore the valid backup.
11. On disposable data only, simulate an unwritable config destination during a reset. Confirm errors are logged, old worlds remain, and the reset completes once saving succeeds. Restart and verify the committed world loads.
12. Upgrade a copy of legacy data with only the old folder present. Confirm migration preserves records and active worlds. With both folders present, confirm startup refuses to choose or overwrite either one.

## Compatibility scope

Paper 1.21.4 is the compilation target and declared minimum API version. Code supports the legacy world layout and Paper 26.1+ dimension layout, including clockless Nether/End dimensions. Do not infer a fully tested version range from the API declaration.

An earlier development README reported a Paper 26.3 / Java 25 server smoke test for version 1.1.1. The renamed 1.2.0 build has not yet completed the multiplayer checklist above. Record the exact server build, Java runtime, and checklist outcomes when testing it.
