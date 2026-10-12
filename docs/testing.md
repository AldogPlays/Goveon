# Alpha hardware verification

The build, JVM protocol tests, Android lint, APK metadata, and APK signature can be checked headlessly. Compilation does not verify radio behavior, firmware compatibility, Android Keystore on a particular phone, or visual layout. No phone install or launch was performed during development.

## Local setup

The workspace-local SDK includes ADB. `./scripts/toolchain.ps1 adb version` verifies the client and `./scripts/toolchain.ps1 adb devices -l` lists attached devices. Use USB debugging and your phone's own authorization dialog when you elect to connect. Do not run install or launch commands unless you intend to test that device.

## Manual checks

1. Install the downloaded APK yourself. Confirm the launcher opens an empty library without fabricated devices.
2. Discover a provisioned LAN-enabled light on the same Wi-Fi. Check its model/IP and refresh. Test power, brightness, RGB, and supported Kelvin values against the physical light. Check both success and no-response feedback.
3. Save an optional Developer API key. Discover cloud devices, verify capability ranges, and search/apply a real returned scene. Test DIY, music, and segment settings only when advertised by your model. Refresh afterwards; acceptance is distinct from state confirmation.
4. Create two groups and two quick actions. Rename/edit/delete each. Save multiple device-state presets and confirm each has its own identity. Relaunch and verify names, membership, recipes, appearance, and secure key persist. Readings should start unknown rather than being restored as current.
5. Put one group member offline; send a group command and inspect individual results. Check that the other light still receives its command. Group timing is best effort and affected by LAN/cloud latency.
6. Test app light/dark/system themes, dynamic colors, status bar legibility, landscape, larger text, screen reader labels, keyboard insets, scrollable sheets, and long scene catalogs.
7. Background the app during discovery/control. It must release local networking without reopening the multicast lock. Reopen, discover/refresh, and verify networking resumes. Test a Wi-Fi change, guest isolation, VPN, and a busy UDP response port.
8. Remove the key and verify cloud commands fail clearly while LAN remains usable. Do not include credentials in bug reports. Inspect encrypted persistence with appropriate development tools only on your own test device.

## Reports

JVM results: `app/build/reports/tests/testDebugUnitTest/index.html`.

Lint: `app/build/reports/lint-results-debug.html`.

Checksums, package metadata, and final build results are recorded in [validation](validation.md). For hardware reports, record phone/Android version, light SKU/firmware, connection route, the control used, and the command result. Actual hardware measurements take precedence over static protocol inference.
