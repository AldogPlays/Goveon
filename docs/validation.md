# Validated milestone: 0.3.0 alpha 1

Validated headlessly on October 12, 2026. Android hardware, radio behavior and on-device visual behavior have not been verified. ADB 37.0.1 is available. Only its version was checked during this milestone; no phone was contacted, installed to or launched on.

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed: 55 tests, 0 failures/errors/skips |
| `:app:lintDebug` | Passed: 0 errors; 10 advisory warnings (5 newer dependency versions, 4 KTX style suggestions, 1 API-level permission-flag advisory) |
| `:app:assembleDebug` | Passed; Kotlin, resources, manifest, R8 shrinking and APK packaging |
| `apksigner verify --verbose` | Verified; one debug signer; APK Signature Scheme v2 |
| `aapt dump badging` | `com.goveon.app`, version code 3 / name 0.3.0, minimum API 26, target/compile API 35 |
| `scripts/check-public-files.ps1 -History` | Passed; tracked and non-ignored source plus reachable commit identities checked |
| APK metadata/DEX/resource privacy scan | Passed; no matching personal filesystem paths, personal email or GitHub token patterns |
| Evidence index | 164 unique records; all 74 report references resolve to indexed records |

The 20 Bluetooth protocol tests cover recovered UUID roles, exact padded/XOR frames, automatic family selection, both brightness scales, native-white frames/ranges, capability gating, read-only probes, acknowledgement semantics and malformed packets. Eleven deterministic coroutine tests cover automatic page ownership, cancellation, background release, bounded retries, stable-connection reset and terminal failures; three tests distinguish ATT callback errors from Android write-submission errors. Two tests cover diagnostic redaction. Four LAN transport tests exercise actual loopback UDP, network failures, cleanup and cancellation. The existing 15 tests cover LAN/cloud serialization, capabilities, state semantics, groups, scenes and quick actions.

The packaged APK is `artifacts/goveon-0.3.0-alpha.1.apk`, **7,246,097 bytes** (6.91 MiB). Its SHA-256 is:

```text
d9f11c2aa19858c57f50f7405292589b3a75630dd3016e515ed23afbee07c856
```

The artifact is debug-signed for manual testing and uses code/resource shrinking. Its signing certificate matches the locally released 0.2.0 APK, allowing an in-place update that preserves app data. Debuggable R8 builds retain debug behavior and disable aggressive optimization/obfuscation. A newer SDK command-line-tools XML-version advisory and the deprecated `ConnectivityManager.allNetworks` compiler warning remain; neither prevented compilation or packaging. Production signing is not configured.

GitHub CI repeats the public-file guard, tests, lint and assembly. Its APK uses the runner's debug identity, so its checksum/signature need not match this locally packaged release APK.

The [hardware checklist](testing.md) covers automatic connections, reconnection, overlapping quick actions, permissions, native-white output, 1/50/100% brightness comparisons, group timing, lifecycle cleanup and visual behavior. The [Bluetooth investigation](ble-protocol.md) confirms the native CCT encoding and separates static evidence from physical firmware acceptance. Authentication, provisioning and advanced Bluetooth effects remain unsupported. In particular, the traced H6006 flow sends byte 100 at 100%; the separate H6005 flow sends byte 254. Physical light output and the reason for the reported H6006 dimness are not established by JVM tests or decompilation. Compare native White and RGB white on hardware before claiming that maximum brightness is fixed.
