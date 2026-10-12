# Goveon

A small, independent Android home for your Govee lights. Kotlin, Jetpack Compose, Material 3, local-first storage, and real lighting protocols.

**First working alpha: 0.1.0.** Android 8.0/API 26 or newer. Hardware compatibility and physical-device visual checks are still being established; see [testing](docs/testing.md).

## Controls

- Discover LAN lights on your Wi-Fi, with no account or API key.
- Optionally discover cloud lights using your own Govee Developer API key.
- Power, brightness, full RGB color, hue/saturation/intensity, and white temperature.
- Searchable real scenes and DIY catalogs, plus device-advertised music modes, segments, toggles, and other enum/range/structured capabilities.
- Rename and forget devices, create/edit groups, and control shared capabilities together.
- Save customizable multi-light quick actions, including scene/effect commands or a device's reported state.
- Material You wallpaper colors on Android 12+, system/light/dark themes, animated navigation, accessible control labels, and generous touch targets.

Goveon starts empty. It never inserts simulated lights. Controls send real UDP or HTTPS requests. A command can be **confirmed**, **accepted**, or **failed**; cloud acceptance and a UDP send are not proof that a physical light changed. Groups retain individual results and use concurrent fan-out rather than pretending every device has a shared clock.

## First connection

1. Provision your lights onto Wi-Fi using Govee Home. Enable **LAN Control** in each supported device's settings.
2. Connect your phone to that Wi-Fi and tap **Discover lights**. The router must permit multicast and device-to-phone traffic; guest/client isolation can prevent this.
3. For supported cloud-only lights or advanced scenes/effects, request a Developer API key from Govee and save it in Goveon **Settings**. Discover again. Your Govee password is not needed.
4. Open a light, refresh its state, then use its controls. Scene catalogs load for cloud devices and can be refreshed explicitly.

The developer key is encrypted with Android Keystore and stored in app-private preferences. Backup is disabled. Device names, groups, capabilities, settings, and recipes stay on the phone. No Goveon server, account, telemetry, background polling, or foreground service is required. The Wi-Fi multicast lock is released when the app goes into the background.

## Device coverage

| Connection | Supported behavior | Requirements / boundary |
| --- | --- | --- |
| LAN | Discovery, state, power, brightness, RGB, white temperature | Device implements Govee's public LAN API and LAN is enabled. Temperature range is not advertised over LAN; confirmation reveals unsupported values. |
| Developer cloud | Device list/state, basic lighting and schema-driven controls, real scene/DIY catalogs | User API key; model must expose that capability. Internet and Govee service availability required. |
| Bluetooth-only / initial pairing | Not implemented in this alpha | REA found model-specific framing and discovery. Full handshake, model selection and state parsing require further verification. |
| Proprietary streaming / DreamView | Not implemented | Public LAN does not expose native scene catalogs or arbitrary per-pixel streaming. Advertised cloud music/segment controls are available instead. |

An advanced JSON editor is available for device-reported schemas that do not fit a simple control. Values are validated against the advertised schema before transmission. Goveon can select existing DIY scenes; it does not author proprietary DIY programs. It does not invent model capabilities or scene IDs.

## Build and wired testing

Install a full JDK 17 and Android SDK Platform 35/build tools 35.0.0, set `JAVA_HOME` and `ANDROID_HOME`, then:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Linux/macOS use `./gradlew`. On the configured development machine, `./scripts/toolchain.ps1 build` selects workspace-local tools; see [toolchain setup](scripts/toolchain/README.md).

The test APK is `app/build/outputs/apk/debug/app-debug.apk`. It is debug-signed and shrunk to remove unused code/resources. Releases require a separately managed production signing identity. CI builds downloadable test APK artifacts and reports on pushes and pull requests.

When **you choose to install** onto a USB-connected phone:

```powershell
adb devices -l
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch Goveon yourself and follow [the manual verification checklist](docs/testing.md). Development automation never installs or launches apps on a phone without permission.

## Project shape

`ui/` contains Compose destinations and contextual controls. `GoveonViewModel` manages user actions and progress. `lighting/` holds pure protocol codecs, LAN/cloud transports, persistence, and the stateful repository. Android/JDK APIs supply networking and encrypted storage; coroutines and StateFlow keep I/O off the UI thread. There is no DI framework or custom backend.

The [REA investigation](docs/reverse-engineering.md) explains what was observed in the supplied Govee APK, what was inferred, and what remains unknown. Goveon implements original code and UI. [TrailRelay](https://github.com/AldogPlays/TrailRelay) inspired the readable Material surfaces, local-first behavior, status labels, contextual sheets, and small responsibility-based project structure.

Goveon is independent of Govee and is not an official Govee product.
