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

## Local validation only

GitHub Actions is disabled for this repository, and its Android workflow has been removed. Keep builds, tests, lint and privacy checks local; do not add workflows or enable Actions unless the owner explicitly authorizes it.

Run both variants from the repository root with the configured JDK and Android SDK:

```powershell
./gradlew.bat --no-daemon --console=plain --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
./scripts/check-public-files.ps1 -History
```

Use `./gradlew` on Linux/macOS. Successful local checks verify buildability and offline behavior; BLE/LAN hardware behavior, synchronization latency and device-specific effects still require the documented hardware test plan. Version 1.0 is an optimized, non-debuggable release build signed offline with the existing local certificate for update continuity. Keep its private keystore outside Git; never publish signing secrets.
