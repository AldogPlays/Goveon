# Goveon contributor and agent guide

Goveon is a local Bluetooth/LAN Android lighting controller built with Kotlin and Jetpack Compose. Read [README.md](README.md) and [implementation notes](docs/implementation-notes.md) before changing behavior. For protocol work, also read [Bluetooth findings](docs/ble-protocol.md) and [the investigation record](docs/reverse-engineering.md).

## Working safely

- Inspect Git status, the current branch, and recent history before editing. Preserve unrelated work; do not reset, stash, or overwrite it automatically.
- Keep the existing local-control architecture. Do not add cloud accounts, API keys, a backend, speculative scenes, or guessed authentication/provisioning commands.
- Keep builds, tests, lint, and public-file checks local. Do not add workflows or enable GitHub Actions without explicit authorization.
- Do not install, launch, uninstall, or clear data on a phone without explicit authorization. Device tests are separate from JVM and build checks.
- Keep personal paths, device identifiers, account information, keys, raw reference APKs, decompiled source, captures, and build artifacts out of Git and public reports. Use the existing ignored directories and sanitization rules.

## Build and validation

Follow [the local toolchain guide](scripts/toolchain/README.md) for setup. With a configured JDK and Android SDK, run the checks relevant to the change:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

For release-related work, also run `:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease` and the existing public-file scan:

```powershell
./scripts/check-public-files.ps1 -History
```

Use `gradlew.bat` on Windows. The optional `scripts/toolchain.ps1` helper expects its tools under `.tools/`; configure a normal SDK/JDK if those local tools are absent. ADB may require access to its Windows user-profile authentication files; do not change credentials or weaken environment restrictions to bypass an access failure.

Documentation-only changes need formatting, link, and factual checks rather than an Android build. Report exactly which checks ran. Follow [the hardware checklist](docs/testing.md) for behavior that depends on Android, lights, firmware, networking, or layout; do not mark it as passed from JVM results alone.

## Protocol and evidence requirements

- Keep model support grounded in documented frames, device families, actual GATT capabilities, and valid readings. A write acknowledgement is not proof of physical state.
- Preserve per-light command ordering, independent group outcomes, foreground connection ownership, and unknown state after restart.
- Retain evidence IDs, artifact hashes, tool versions, and distinctions between static analysis, independent asset extraction, and physical tests. The local Smali fallback in `docs/ble-protocol.md` has separate provenance; do not present it as an unmodified provider result.
- Analysis scripts must not execute a reference APK or contact devices/accounts as a side effect. Additional device or account access needs explicit authorization.

## Signing and publication

- Preserve the retained signing certificate for in-place updates, including its documented Android Debug label. Fresh release builds are unsigned until signed offline; never generate a substitute official key.
- Keep signing credentials outside Git. Follow the existing packaging script and verify the final artifact's version, signature, alignment, and checksum.
- Follow the user's current Git publication instructions. Code or documentation changes do not by themselves authorize new version tags, app releases, APK uploads, signing changes, or hosted automation.

## Documentation

Keep README focused on installation, supported controls, requirements, and links. Put technical behavior in the linked implementation, protocol, and test guides. Keep assistant permissions and session mechanics in this file. Preserve historical test scope and hardware limitations; rewrite narration without turning reported results into verified device coverage.
