# Develop WipeTogether in IntelliJ IDEA

1. Open this folder or its `pom.xml` as a project.
2. Let Maven import dependencies.
3. Select JDK 21 as the project SDK and Maven runner JRE.
4. Run **Maven > Lifecycle > verify**, or run `mvn clean verify` in a terminal.

The build produces `target/WipeTogether-1.2.1.jar`. A Paper plugin has no standalone main method: install the JAR in a disposable Paper server to exercise gameplay. The server may need a newer Java runtime than the build SDK.

## Source map

| File | Responsibility |
| --- | --- |
| `src/main/java/com/wipetogether/WipeTogether.java` | Reset lifecycle, player profiles, scoreboard, configuration integration. |
| `src/main/java/com/wipetogether/ConfigStore.java` | Strict YAML reads, atomic writes, legacy data-folder migration. |
| `src/main/java/com/wipetogether/Messages.java` | Validated MiniMessage text, safe placeholders, title timings/toggles, defaults. |
| `src/main/java/com/wipetogether/FreshRun.java` | Unique names, fresh seeds, and dimension generation. |
| `src/main/java/com/wipetogether/RunFiles.java` | Confined world retirement and deletion. |
| `src/main/java/com/wipetogether/WorldStorage.java` | Legacy and modern Paper world storage. |
| `src/main/java/com/wipetogether/VictoryEvents.java` | Victory qualification, records, and fireworks. |
| `src/main/java/com/wipetogether/AdminCommands.java` | Commands, permissions, and tab completion. |
| `src/main/java/com/wipetogether/WipeTogetherTimerIntegrated.java` | TAB timer and elapsed-time persistence. |
| `src/main/resources/plugin.yml` | Plugin entry point, commands, and permission declarations. |
| `src/main/resources/config.yml` | Default settings. |
| `src/main/resources/messages.yml` | Commented titles, chat, sidebar/TAB text, and command feedback. |
| `src/test/java/com/wipetogether/` | Regression tests. |

## Versions and tests

Change the release version in `pom.xml`. Maven filters `@project.version@` into the packaged `plugin.yml`, so the plugin and artifact versions stay in sync.

Run tests through Maven: the build configures the Mockito Java agent for the test JVM. Tests use mocked Bukkit objects and real temporary files; they do not simulate Minecraft terrain or a connected client. See [docs/TESTING.md](docs/TESTING.md) for server checks.

Use a complete server restart after replacing the plugin. Back up the test server before exercises that delete run worlds. Keep one plugin JAR installed, including when upgrading from HardcoreReset.

Version 1.2.1 is [released on GitHub](https://github.com/hildebrandchase/WipeTogether/releases/tag/v1.2.1). See [the settings guide](docs/SETTINGS.md) for customization and [release notes](docs/RELEASE-NOTES-1.2.1.md) for the changes and publication status on other sites.
