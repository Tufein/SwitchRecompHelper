# AGENTS.md — SwitchRecompHelper

Instructions for coding agents (OpenCode, Claude Code, Codex and similar). `CONTRIBUTING.md` is
loaded alongside this file through `opencode.json`.

## Project

Android app that extracts an XCI/NSP/NSZ dump of the user's **own** Switch cartridge into ExeFS and
RomFS (plus uncompressed NSOs and `recomp_info.toml`) as input for static recompilation.

- Package / application id: `com.tufein.switchrecomphelper`
- Version: `app/build.gradle.kts` (`versionName` / `versionCode`), currently 0.6.0 / 6
- compileSdk 35, minSdk 26, targetSdk 35, JDK 17 (CI uses Temurin 17)
- Language: Kotlin, Gradle Kotlin DSL

## Layout

| Path | What lives there |
| --- | --- |
| `core/` | Pure JVM library: container parsing, crypto (`core/crypto`, AES-XTS/CTR), filesystems (`core/fs`: PFS0/HFS0/RomFS/IVFC/BKTR), IO. **All parsing logic goes here**, with a JVM test. |
| `core/src/test/` | Synthetic XCI/NSP/NSZ fixtures built in code with test keys, plus the IEEE 1619 XTS vector. |
| `app/` | Android UI, Storage Access Framework, foreground service. Not tested in CI, so no parsers here. |

## Build and test

```sh
./gradlew :core:test                 # JVM tests (fast, run these first)
./gradlew :core:test :app:assembleDebug   # exactly what CI runs
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Hard rules

- No Nintendo keys, titles or dumps in the repository or in tests. Tests generate their own fixtures and keys.
- The app never ships keys; `prod.keys` is user supplied and stays in app-private storage.
- `exefs/` output stays byte-identical to the dump. Hash failures stop the extraction with a message instead of writing a broken file.
- Do not change behaviour of a check without a test that shows the new behaviour.

## Conventions

- README and user-facing text are **Dutch**; each release adds a "Nieuw in x.y.z" section to `README.md`.
- Small, focused commits with a clear message describing what changed and why.

## Release process

1. Bump `versionName`/`versionCode` in `app/build.gradle.kts` and add the README section.
2. Push a tag `vX.Y.Z`. `.github/workflows/build.yml` runs the tests, builds the APK and attaches
   `SwitchRecompHelper-<version>.apk` to the GitHub release (it can also be started manually with `release_tag`).
3. The owner always wants work shipped as a GitHub release with notes that explain what changed in the code.
