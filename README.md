# Goveon

A small, independent Android home for your Govee lights. Kotlin, Jetpack Compose, Material 3, local-first storage, and real lighting protocols.

**Bluetooth-first alpha: 0.2.0.** Android 8.0/API 26 or newer. Hardware compatibility and physical-device visual checks are still being established; see [testing](docs/testing.md) and [BLE coverage](docs/ble-protocol.md).

## Controls

- Discover nearby Govee Bluetooth devices, inspect real GATT services, and connect directly without Wi-Fi, an account, or a developer key.
- Enable only the controls supported by the identified BLE protocol profile; unclassified devices remain visible with unsupported controls hidden.
- Optionally discover LAN lights on your Wi-Fi, with no account or API key.
- Optionally discover cloud lights using your own Govee Developer API key.
- Power, brightness, full RGB color, hue/saturation/intensity, and white temperature.
- Searchable real scenes and DIY catalogs, plus device-advertised music modes, segments, toggles, and other enum/range/structured capabilities.
- Rename and forget devices, create/edit groups, and control shared capabilities together.
- Save customizable multi-light quick actions, including scene/effect commands or a device's reported state.
- Material You wallpaper colors on Android 12+, system/light/dark themes, animated navigation, accessible control labels, and generous touch targets.

Goveon starts empty. It never inserts simulated lights. Controls send real BLE GATT writes, UDP datagrams, or optional HTTPS requests. A command can be **confirmed**, **accepted**, or **failed**. A successful Bluetooth write callback, cloud acceptance, or UDP send is not proof that a physical light changed. Only decoded device readings update reported state. Groups retain individual results and use concurrent fan-out; there is no shared hardware clock.

## First connection

1. Power on a light, bring your phone nearby, and tap **Discover**. Grant Android's Nearby Devices permission and enable Bluetooth when prompted. Android 8–11 additionally requires Location permission and the Location switch for scanning; Goveon does not collect location.
2. Open a discovered light and tap **Connect Bluetooth**. Close other apps' active connections if the light is busy. Supported Govee lights use a direct GATT connection rather than an Android Settings bond; Goveon does not invent a pairing password or require Govee account login.
3. Controls appear after identifying the supported protocol. H6005 has a traced RGB profile. Other H6 lighting models must answer valid power/brightness reads before those writes are enabled; a matching `0D` color-mode reply additionally enables the recovered RGB layout. A model name alone is insufficient. If an advertisement omits the model, use **Set lighting model** with the H6xxx SKU printed on its controller. The [BLE report](docs/ble-protocol.md) lists exact coverage and unresolved model behavior.
4. Optional Wi-Fi: provision a LAN-capable light using its existing setup process, enable **LAN Control**, join the same Wi-Fi, and tap **Find Wi-Fi lights**. Router multicast/guest isolation and VPN policies can prevent discovery.
5. Optional cloud: add your own Developer API key in Settings for cloud device lists and extra advertised effects. Bluetooth remains independent of this setting. Scene catalogs load only for cloud devices.

The optional developer key is encrypted with Android Keystore and stored in app-private preferences. Backup is disabled. Device names, Bluetooth addresses, groups, capabilities, settings, and recipes stay on the phone. No Goveon server, account, telemetry, background polling, or foreground service is required. Connected supported BLE lights receive read-only foreground status requests every 20 seconds. This does not substitute for any unknown proprietary heartbeat/authentication protocol. Bluetooth connections, scans, and the Wi-Fi multicast lock are released when the app goes into the background. Reconnect after returning.

## Device coverage

| Connection | Supported behavior | Requirements / boundary |
| --- | --- | --- |
| Bluetooth / BLE | Foreground discovery, GATT connection/notification setup, profile-driven reads and lighting controls, connected groups and quick actions | No developer key, cloud, internet, or LAN switch. Exact profiles are documented in the BLE report. Radio/firmware behavior still needs hardware testing. |
| LAN | Discovery, state, power, brightness, RGB, white temperature | Device implements Govee's public LAN API and LAN is enabled. Temperature range is not advertised over LAN; confirmation reveals unsupported values. |
| Developer cloud | Device list/state, basic lighting and schema-driven controls, real scene/DIY catalogs | User API key; model must expose that capability. Internet and Govee service availability required. |
| Authenticated/provisioning-only Bluetooth models | Not fully reconstructed | Unsupported GATT/authentication failures are reported. Goveon does not perform proprietary account binding, Wi-Fi credential provisioning, or guess encrypted handshakes. |
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

`ui/` contains Compose destinations, permission flows, and contextual controls. `GoveonViewModel` manages user actions and progress. `lighting/` holds pure protocol codecs, BLE/LAN/cloud transports, persistence, and the stateful repository. GATT operations are serialized per connection with callback acknowledgements and timeouts. Android/JDK APIs supply networking and encrypted storage; coroutines and StateFlow keep I/O off the UI thread. There is no DI framework or custom backend.

## Public repository privacy

Raw APKs, reverse-engineering envelopes, device/account captures, local tool configuration, keys, and build artifacts remain ignored. Public investigation records contain evidence IDs, relative selectors, and artifact digests. Run `./scripts/check-public-files.ps1 -History` before publishing; CI repeats this scan and checks for GitHub noreply commit identities. This pattern-based check is an additional guard, not a guarantee that every possible secret format is recognized. Never put credentials, personal paths, device identifiers, or account data in issues or release notes.

The [REA investigation](docs/reverse-engineering.md) explains what was observed in the supplied Govee APK, what was inferred, and what remains unknown. Goveon implements original code and UI. [TrailRelay](https://github.com/AldogPlays/TrailRelay) inspired the readable Material surfaces, local-first behavior, status labels, contextual sheets, and small responsibility-based project structure.

Goveon is independent of Govee and is not an official Govee product.
