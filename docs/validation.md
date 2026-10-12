# Validated milestone: 0.1.0 alpha 1

Validated headlessly on October 12, 2026. Android hardware and on-device visual behavior have not been verified. ADB 37.0.1 was available; the read-only device inventory was empty. No app was installed or launched on a phone.

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed: 15 tests, 0 failures/errors/skips |
| `:app:lintDebug` | Passed: 0 errors; 8 advisory warnings (5 newer dependency versions, 3 KTX style suggestions) |
| `:app:assembleDebug` | Passed; Kotlin, resources, manifest, R8 shrinking and APK packaging |
| `apksigner verify --verbose` | Verified; one debug signer; APK Signature Scheme v2 |
| `aapt dump badging` | `com.goveon.app`, version code 1 / name 0.1.0, minimum API 26, target/compile API 35 |

The tests exercise exact LAN RGB/Kelvin serialization, bounds and invalid readings, discovery identity/source IP, cloud light filtering, scene object identity, online/historical-state semantics, schema precision/required fields/segment validation, group partial failure, scene refresh replacement, quick-action round trips, and the exact HTTPS request envelope/control payload used by the transport.

The final packaged APK is `artifacts/goveon-0.1.0-alpha.1.apk`, **6,825,005 bytes** (6.51 MiB). Its SHA-256 is:

```text
746663ce29cba5cffaa140aa28c1efb1aa0b32d6b23621b3c6412db5aaa8d8fe
```

The artifact is debug-signed for manual testing and uses code/resource shrinking. Debuggable R8 builds retain debug behavior and disable aggressive optimization/obfuscation. A newer SDK command-line-tools XML-version advisory and the deprecated `ConnectivityManager.allNetworks` compiler warning remain; neither prevented compilation or packaging.

GitHub CI repeats tests/lint/assembly and publishes build artifacts. Its APK will use the runner's debug identity, so its checksum/signature need not match this locally packaged APK. Production signing is not configured.

The manual [hardware checklist](testing.md) covers actual light behavior, pairing prerequisites, cloud permissions, group timing, lifecycle/network changes, Keystore persistence, accessibility, rotation, and themes. Unsupported Bluetooth-only provisioning and proprietary streaming effects are documented in the [investigation](reverse-engineering.md).
