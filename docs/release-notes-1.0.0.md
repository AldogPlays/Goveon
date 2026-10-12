# Goveon 1.0.0

The first local-only release: real Bluetooth lighting controls, optional LAN, custom groups and quick actions in a compact Material 3 app.

- Removed developer-key entry, encryption/storage, cloud discovery/control, HTTPS transport, cloud schemas and scene/effect UI. No account or cloud service is required or contacted.
- Retained native RGB/White, model-specific brightness scales, automatic foreground Bluetooth connections, bounded reconnection and quiet feedback.
- Added a versioned persistence codec that isolates damaged records and preserves local customization on upgrade. Cloud-only records and recipes containing retired commands are dropped as whole recipes. Existing local groups and valid actions remain; saved credentials and their old Keystore alias are removed without decryption.
- Quick actions validate every target/value before sending and retain one device lock for their full recipe. Overlapping recipes cannot interleave on the same light; separate lights still run concurrently. Repeated runtime updates avoid duplicate preference writes.
- LAN command/read exchanges serialize per IP. Old sockets cannot restore online state after pause/rebind. Network transitions fail the affected operation without disguising it as cancellation of an entire group. Missing status fields leave commands accepted instead of falsely rejected. Invalid discovery identities are ignored.

The downloadable APK is an optimized, non-debuggable **release** build, signed offline with the existing local certificate for in-place updates from 0.2/0.3. The retained certificate is labelled Android Debug; its private keystore is not in the repository. Fresh Gradle release builds are unsigned until signed offline. The separate CI debug APK is for development and has a different runner certificate.

Debug and release each pass **64 JVM tests**, lint with **0 errors**, and assembly. APK signature, alignment, package metadata and public-file privacy are checked. See [validation](validation.md) for the final checksum and [manual checks](testing.md) for hardware verification.

No phone was contacted, installed to or launched on during development. BLE firmware variants, radio timing, physical brightness/white calibration, LAN behavior and the actual Android upgrade cleanup still require hardware checks. Local Bluetooth scenes, authentication/provisioning and advanced effects are not implemented and are not exposed as supported features.
