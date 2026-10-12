# Validated milestone: 0.2.0 alpha 1

Validated headlessly on October 12, 2026. Android hardware, radio behavior and on-device visual behavior have not been verified. ADB 37.0.1 is available. Only its version was checked during this milestone; no phone was contacted, installed to or launched on.

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed: 33 tests, 0 failures/errors/skips |
| `:app:lintDebug` | Passed: 0 errors; 10 advisory warnings (5 newer dependency versions, 4 KTX style suggestions, 1 API-level permission-flag advisory) |
| `:app:assembleDebug` | Passed; Kotlin, resources, manifest, R8 shrinking and APK packaging |
| `apksigner verify --verbose` | Verified; one debug signer; APK Signature Scheme v2 |
| `aapt dump badging` | `com.goveon.app`, version code 2 / name 0.2.0, minimum API 26, target/compile API 35 |
| `scripts/check-public-files.ps1 -History` | Passed; tracked and non-ignored source plus reachable commit identities checked |

The 14 Bluetooth protocol tests cover the recovered UUID roles, exact padded/XOR frames, model identification, capability gating, read-only probes, read versus write acknowledgement semantics, bounds, malformed packets and strict color-mode recognition. Four LAN transport tests exercise actual loopback UDP, detailed network failures, setup cleanup and cancellation. The existing 15 tests cover LAN/cloud protocol serialization, capabilities, state semantics, groups, scenes and quick actions.

The packaged APK is `artifacts/goveon-0.2.0-alpha.1.apk`, **7,160,025 bytes** (6.83 MiB). Its SHA-256 is:

```text
f450c980cd775928836a13ec2351248588e3c9c9e1d049f1d2aca3fc532a510d
```

The artifact is debug-signed for manual testing and uses code/resource shrinking. Debuggable R8 builds retain debug behavior and disable aggressive optimization/obfuscation. A newer SDK command-line-tools XML-version advisory and the deprecated `ConnectivityManager.allNetworks` compiler warning remain; neither prevented compilation or packaging. Production signing is not configured.

GitHub CI repeats the public-file guard, tests, lint and assembly. Its APK uses the runner's debug identity, so its checksum/signature need not match this locally packaged release APK.

The [hardware checklist](testing.md) covers permissions, foreground BLE discovery, direct GATT connections, notifications, real state replies, command effects, group timing, lifecycle cleanup, LAN network changes and visual behavior. The [Bluetooth investigation](ble-protocol.md) distinguishes APK evidence from inference and lists unsupported authentication, provisioning, temperature encoding and advanced effect protocols. Passing JVM tests does not establish physical model compatibility or successful phone-to-light communication.
