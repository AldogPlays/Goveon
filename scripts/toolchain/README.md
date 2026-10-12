# Headless local toolchain

The development machine uses workspace-local tools in `.tools/`, which Git ignores. No Android Studio or desktop windows are needed, and no global PATH or Codex configuration was changed.

Installed tools:

- Eclipse Temurin JDK 17.0.20.1+1, full Windows x64 JDK.
- Android command-line tools archive `commandlinetools-win-15859902_latest.zip`.
- Android SDK platform 35, build tools 35.0.0, and platform tools 37.0.1.
- JADX headless MCP 0.7.1, with the exact SHA-256 required by REA 6.4.0.

Run from the repository root in PowerShell:

```powershell
. ./scripts/toolchain.ps1
./scripts/toolchain.ps1 build
./scripts/toolchain.ps1 adb version
./scripts/toolchain.ps1 adb devices -l
./scripts/toolchain.ps1 rea inspect-android-package 'C:/absolute/path/app.apk' --format json
```

`build` defaults to the debug APK and JVM unit tests, caps Gradle at two workers, and runs Kotlin compilation in the build process to avoid a persistent compiler daemon. Gradle uses `.tools/gradle-user`; SDK and Android user settings are scoped to `.tools`. `adb devices` only lists devices, although ADB starts its background server if needed. Never install or launch an app on a connected phone without the user's approval. The newer Windows ADB client uses Windows profile resolution for its own authentication files and may require execution outside the Codex restricted sandbox; the script does not change that profile.

On another machine, install a full JDK 17+ and Android SDK platform 35/build tools 35.0.0, then set `JAVA_HOME` and `ANDROID_HOME` or adapt the local path in the script. The Gradle wrapper is portable. The `rea` action uses an installed command or a launcher supplied through `REA_LAUNCHER` and `REA_NODE`. Machine-specific paths can also live in ignored `.tools/rea-local.json` with `launcher` and `node` fields. Personal filesystem paths never belong in committed scripts. The large reference APK needs an 8 GiB analysis heap; run one headless REA worker at a time.

Verified SHA-256 values:

| Download | SHA-256 |
| --- | --- |
| JDK archive | `e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0` |
| Android command-line tools | `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` |
| JADX MCP JAR | `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75` |
| Gradle 8.11.1 archive | `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` |

Official sources: [Temurin](https://adoptium.net/temurin/releases/), [Android SDK tools](https://developer.android.com/studio#command-tools), [JADX release](https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1), [Gradle checksum](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256).

## GitHub Actions

`.github/workflows/android.yml` runs on pull requests, pushes to `main`, and manual dispatch. It uses GitHub's Ubuntu 24.04 image, Temurin 17, Android API 35/build tools 35.0.0, and the checked-in Gradle wrapper. It runs JVM unit tests, Android lint, and debug APK assembly. Successful runs upload a debug APK with its SHA-256 checksum; test and lint reports upload even after a failed build when available. Artifacts remain available for 14 days on the workflow run's page.

Actions are pinned to exact commits resolved from their official release tags on October 12, 2026: [checkout 7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1), [setup-java 6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1), [setup-gradle 6.4.0](https://github.com/gradle/actions/releases/tag/v6.4.0), and [upload-artifact 7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2). Gradle uses its open-source basic cache provider, validates the wrapper JAR, and restricts pull-request runs to reading the cache. Repository permissions are read-only and checkout does not retain Git credentials. The workflow does not publish a release or communicate with a phone.

CI verifies buildability and offline checks. It has no emulator or physical lights, so BLE/LAN hardware behavior, synchronization latency, and device-specific effects still require the documented hardware test plan. Hosted images and package repositories can change or become unavailable; SDK package versions and the Gradle distribution checksum are explicit. CI debug APKs use the runner certificate and must not replace published release assets. Version 1.0 is an optimized, non-debuggable release build signed offline with the existing local certificate for update continuity. Keep its private keystore outside Git; never publish signing secrets. CI verifies both variants but uploads only its debug APK for development.

Primary workflow references: [Ubuntu 24.04 runner tools](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md), [Gradle Actions guide](https://docs.gradle.org/current/userguide/github-actions.html), and [artifact upload inputs](https://github.com/actions/upload-artifact#usage).
