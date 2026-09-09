# Repository Guidelines

## Project Structure & Module Organization
- `lib/` is the Kotlin Android animation library. Sources live in `lib/src/main/java/com/asyncanimator/`, grouped into `core`, `thread`, `anim`, `playback`, `seq`, `control`, and `manager`; launcher integration also lives under `com/android/launcher3/`.
- `lib/src/test/java/` contains JVM unit tests mirroring library packages.
- `demo/` is the Android demo app: activities are under `demo/src/main/java/com/asyncanimator/demo/`, with layouts, drawables, and strings in `demo/src/main/res/`.
- `docs/` contains usage, architecture, trace validation, and review records. Prefer `animation-thread-analysis-v4.md` over older architectural conclusions.
- Dependency versions and aliases are centralized in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands
Use a full JDK 21 via `JAVA_HOME`, Android SDK 37, and Build Tools 37.0.0. Configure your SDK path in untracked `local.properties`. Run from the repository root:

```powershell
.\gradlew.bat :lib:test                 # Library JVM tests
.\gradlew.bat :demo:assembleDebug       # Build the debug APK
.\gradlew.bat :lib:lint :demo:lint       # Android lint checks
.\gradlew.bat :demo:installDebug        # Install on a connected device
adb shell am start -n com.asyncanimator.demo/.LauncherEntryActivity
```

Devices/emulators must support API 36 or newer. On Unix, use `./gradlew`. The debug APK is produced under `demo/build/outputs/apk/debug/`.

## Coding Style & Naming Conventions
Follow the configured official Kotlin style: four-space indentation, `UpperCamelCase` types, and `lowerCamelCase` functions/properties. Match filenames to their primary types and use `snake_case` Android resource names. Prefer idiomatic Kotlin and existing platform/AndroidX animation APIs rather than duplicating them. Preserve thread ownership, callback cleanup, and safe feature-disabled behavior. No standalone formatter is configured; use IDE Kotlin formatting and Android lint.

## Testing Guidelines
Use JUnit 4; AssertJ is also available. Name classes `*Test` and methods descriptively, following existing `testBehaviorName` examples. Add regression tests for frame callbacks, state transitions, and sequence handling. No coverage threshold is configured. JVM tests return default Android stub values, so validate real Looper/VSYNC behavior on a device; report the demo exercised and any testing skipped.

## Commit & Pull Request Guidelines
History mixes imperative summaries with prefixes such as `lib:`, `docs:`, and `chore:`; use a concise, scoped summary. Keep changes focused. PR descriptions should explain behavior, link relevant issues/review records, list validation commands/results, and include screenshots or traces for visual/threading changes. Update affected documentation. Do not commit SDK paths, build outputs, temporary scripts, or unrelated local changes.
