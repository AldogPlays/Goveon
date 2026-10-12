# Validated release: 1.0.0

Validated headlessly on October 12, 2026. No phone was enumerated, contacted, installed to or launched on. ADB 37.0.1 is available; only its version was checked. Its Windows preference-directory lookup requires a normal Windows process rather than this restricted execution sandbox.

| Check | Result |
| --- | --- |
| Debug and release JVM tests | Each passed 64 tests, 0 failures/errors/skips |
| Debug and release Android lint | Each passed, 0 errors; 9 advisory warnings: 5 newer dependency versions, 3 KTX suggestions, 1 API-level permission-flag advisory |
| Debug and release assembly | Passed: Kotlin, manifest, resources, R8, shrinking, packaging and release resource optimization |
| `apksigner verify --verbose` | Verified: one signer, APK Signature Schemes v2 and v3 |
| `zipalign -c -P 16 4` | Passed on the final signed APK |
| APK package/flags | `com.goveon.app`, code 4 / name 1.0.0, min API 26, target/compile API 35; not debuggable |
| Previous signer comparison | Same certificate as the locally released 0.3.0 APK |
| Public-file privacy guard | Passed on source and reachable commit identities |
| APK metadata/DEX/resource scan | No matching personal filesystem paths, personal emails, GitHub token patterns or retired cloud endpoint/transport classes |

The 64 tests comprise 20 Bluetooth protocol tests, 11 automatic-session coroutine tests, 3 BLE failure-policy tests, 2 diagnostic-redaction tests, 7 LAN/group protocol tests, 4 real loopback UDP tests, 10 persistence/migration tests and 7 command-execution tests. They cover exact frames and model-dependent brightness/CCT, capability gating, retries/lifecycle cancellation, partial LAN replies, group failure isolation, local-only upgrades, corrupt records, invalid recipes, per-target ordering and independent-target concurrency.

The release APK uses the optimized, obfuscated, non-debuggable release variant. Its application source revision is `709d21db1c3aa9aff1da4ca2655108b8461e4a83`; the subsequent release-record commit changes only documentation/checksum and ignores detached signing artifacts. The final artifact is `artifacts/goveon-1.0.0.apk`, **1,375,419 bytes** (1.31 MiB). SHA-256:

```text
77f9997d82bfa4905a30e7460a9fef43cc171ad1c367d2f44112b00023317135
```

It is signed offline with the previous local certificate for in-place update continuity. The certificate is labelled Android Debug; the private keystore is ignored and is not published. Fresh Gradle release builds are unsigned until signed. The packaging script accepts passwords only through transient environment variables, verifies the requested version, aligns and signs, and produces the checksum. CI validates both variants and uploads only its separately signed debug APK for development; that certificate/checksum need not match the release asset.

The original SDK XML-version advisory and deprecated `ConnectivityManager.allNetworks` warning remain. Neither prevents compilation or packaging. R8 optimizes the release build; successful JVM tests do not execute the optimized APK on a phone.

The [hardware checklist](testing.md) includes upgrade preservation, Android credential cleanup, automatic Bluetooth connections/reconnection, overlapping actions, native White, brightness comparisons, foreground/background transitions and LAN changes. The user reports the earlier Bluetooth implementation works on their lights; this release's optimized APK, storage cleanup and firmware/radio variations still need manual verification. No local scenes, account authentication or provisioning protocols are fabricated. [BLE evidence](ble-protocol.md) documents recovered family coverage and remaining limits.
