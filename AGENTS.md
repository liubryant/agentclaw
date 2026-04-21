# Repository Guidelines

## Project Structure & Module Organization
This is a multi-module Android project (Groovy Gradle DSL).

- `app/`: main Android app (`ai.inmo.openclaw`), XML layouts + ViewBinding, Room schemas in `app/schemas/`.
- `core/core_common/`: shared base library (`ai.inmo.core_common`) for base activities, view models, adapters, utils, and logging.
- `gradle/libs.versions.toml`: centralized dependency and plugin versions.
- `doc/`: project documentation (for example, migration notes).
- `keystore/`: signing artifacts (treat as sensitive material).

Source sets follow standard Android layout: `src/main`, `src/test`, `src/androidTest`.

## Build, Test, and Development Commands
Run from repository root:

- `./gradlew assembleDebug` (Windows: `.\gradlew.bat assembleDebug`): build debug APK.
- `./gradlew assembleRelease`: build release APK.
- `./gradlew test`: run JVM unit tests for all modules.
- `./gradlew :app:testDebugUnitTest`: run app module unit tests only.
- `./gradlew connectedAndroidTest`: run instrumented tests on a connected device/emulator.
- `./gradlew clean`: clean Gradle outputs.

## Coding Style & Naming Conventions
- Language: Kotlin + Android XML (no Compose currently).
- Indentation: 4 spaces, no tabs.
- Class/object names: `PascalCase`; methods/vars: `camelCase`; constants: `UPPER_SNAKE_CASE`.
- Package names remain lowercase under `ai.inmo.openclaw` / `ai.inmo.core_common`.
- Keep module boundaries clear: reusable primitives go to `core_common`, feature-specific logic stays in `app`.
- Prefer existing base classes/utilities in `core_common` before introducing new abstractions.

## Testing Guidelines
- Unit tests: JUnit4 in `src/test/java`.
- Instrumented tests: AndroidX test + Espresso in `src/androidTest/java`.
- Name tests by behavior, e.g., `ChatMessageEntityTest`, `fromDomain_mapsRoleCorrectly`.
- Add/refresh Room schema outputs when database entities or DAOs change.

## Commit & Pull Request Guidelines
- Recent history uses short, task-focused subjects (often Chinese) and may include a `Subject:` prefix.
- Recommended commit format: `<scope>: <brief action>` (example: `core_common: add coroutine helper extensions`).
- PRs should include:
  - what changed and why,
  - impacted modules (`app`, `core_common`),
  - test evidence (`./gradlew test`, device test notes),
  - screenshots/video for UI changes.

## Security & Configuration Tips
- Never commit secrets or production credentials.
- Keep signing material and passwords out of versioned Gradle files where possible; use local/private properties for sensitive overrides.
