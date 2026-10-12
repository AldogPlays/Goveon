# Goveon 0.2.0 — Bluetooth-first testing alpha

Discover nearby Govee lights and connect directly over BLE, without a developer key, account or cloud service. Android Nearby Devices permission is requested when using Bluetooth; Android 8–11 also require the system Location permission/services for scanning. LAN and developer cloud access are optional extras.

- Foreground Bluetooth discovery, direct GATT connection, notification subscriptions and reported-state queries.
- Power, brightness and RGB for the recovered H6005 profile. Additional H6 lighting models can gain basic controls after answering both power and brightness reads, and RGB after a matching color-mode reply. Unknown protocols stay explicitly unsupported.
- Connect/disconnect controls, remembered devices, manual model identification, group connections and customizable quick actions. Bluetooth connections close when the app backgrounds.
- Material 3 dynamic colors, dark mode and clearer permission, connection and capability feedback.
- LAN now binds discovery/replies to a physical Wi-Fi network and preserves actionable native failure details.
- Public-source checks for personal paths, common secrets and private commit identities.

Download `goveon-0.2.0-alpha.1.apk` and its SHA-256 file. Android 8.0/API 26+ is required. This 6.83 MiB APK is debug-signed for manual testing. SHA-256:

```text
f450c980cd775928836a13ec2351248588e3c9c9e1d049f1d2aca3fc532a510d
```

All 33 JVM tests, Android lint and APK assembly passed, and the APK signature verified. No phone was contacted or app installed/launched. Actual BLE scanning, connections and lighting commands still require hardware testing; start with the [manual checklist](https://github.com/AldogPlays/Goveon/blob/main/docs/testing.md).

Bluetooth authentication/enrollment handshakes, Wi-Fi provisioning, temperature writes, native scenes/music, segments and DreamView are not implemented. Some models require these protocols and will remain unclassified or reject a connection. Group commands use best-effort delivery without a shared device clock. See the [evidence and coverage report](https://github.com/AldogPlays/Goveon/blob/main/docs/ble-protocol.md) before treating a model as verified.
