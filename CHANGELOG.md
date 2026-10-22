# Changelog

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
