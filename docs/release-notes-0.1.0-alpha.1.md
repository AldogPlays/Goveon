# Goveon 0.1.0 — first testing alpha

An original Material 3 / Compose Android light controller, inspired by TrailRelay's local-first simplicity.

- Real LAN discovery, state queries, power, brightness, RGB and white temperature.
- Optional Govee Developer API key for cloud discovery, real scenes/DIY, music, segments and device-advertised capabilities.
- Custom device names, editable groups, concurrent group controls with individual results, and customizable multi-light quick actions.
- Dynamic colors, system/light/dark themes, animated navigation and contextual control sheets.
- Android Keystore encrypted credentials, backup/transfer exclusions, foreground-only LAN networking, and honest accepted/confirmed/failed feedback.

Download `goveon-0.1.0-alpha.1.apk` and its SHA-256 file. Android 8.0/API26+ is required. This is a debug-signed manual-test APK (6.51 MiB), not a production-signed release.

All 15 protocol tests, Android lint and APK assembly passed; the APK signature verified. No phone install/launch or physical light test was performed. See the repository's hardware checklist before treating a model as verified.

LAN requires lights already provisioned to Wi-Fi with LAN Control enabled. Cloud scenes and advanced settings require an API key and advertised device support. Bluetooth-only setup, proprietary per-pixel streaming and DreamView are not implemented. Groups use best-effort concurrent fan-out, without a shared device clock.
