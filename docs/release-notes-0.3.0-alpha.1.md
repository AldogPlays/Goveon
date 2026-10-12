# Goveon 0.3.0 alpha 1

Native white lighting, quieter controls, and automatic foreground Bluetooth connections.

- RGB/White switching with warm, balanced and cool presets and a temperature slider constrained to each light's supported values. REA confirms native white payloads for H6005 and the type-64 bulb family: H6004, H6006, H6008, H6009, H600A, H6010 and H600D. The latter uses 2700–6500 K in 100 K steps. Generic Bluetooth profiles do not gain white control from RGB replies alone.
- Brightness follows the identified controller family. H6005's recovered scale maps 100% to device byte 254; the H6006 family's complete vendor send path maps 100% to byte 100. These scales are deliberately different. Physical output still needs comparison with the vendor app, particularly native White versus RGB white.
- Opening a light or group automatically establishes its Bluetooth connections. Brief drops reconnect quietly with bounded retries; permission, radio and unsupported authentication failures have clear inline actions. Sessions end when their last foreground page/action owner releases them. Quick actions can connect their Bluetooth targets from the Actions tab.
- Compact control pages, additional details under More, advanced color settings collapsed, and subtle Material 3 feedback. Detailed sanitized diagnostics stay in local Logcat. A simple Android robot replaces the previous logo, including adaptive and themed launcher variants.

No phone was contacted, installed to or launched on during development. Bluetooth timing, firmware variants, visual behavior and maximum physical brightness need manual hardware testing. Bluetooth authentication, provisioning and advanced scenes/effects remain outside the confirmed local profiles. LAN and developer API control are optional.

The release APK is debug-signed for manual testing, requires Android 8/API 26 or newer, and uses the same local signing identity as the previous release. Installing it yourself over 0.2.0 should preserve saved groups and actions. Follow the [hardware checklist](testing.md); [validation](validation.md) records tests and the APK checksum, and the [protocol report](ble-protocol.md) separates static evidence from hardware assumptions.
