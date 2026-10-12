# Alpha hardware verification

The build, JVM protocol tests, Android lint, APK metadata, and APK signature can be checked headlessly. Compilation does not verify radio behavior, firmware compatibility, Android Keystore on a particular phone, or visual layout. No phone install or launch was performed during development.

## Local setup

The workspace-local SDK includes ADB. `./scripts/toolchain.ps1 adb version` verifies the client and `./scripts/toolchain.ps1 adb devices -l` lists attached devices. Use USB debugging and your phone's own authorization dialog when you elect to connect. Do not run install or launch commands unless you intend to test that device.

## Manual checks

1. Install the downloaded APK yourself. Confirm the launcher opens an empty library without fabricated devices.
2. Turn Wi-Fi/mobile data off, leave the API key empty, and tap **Discover** with Bluetooth enabled. Check Nearby Devices permissions on Android 12+, and Location permission plus the Location switch on Android 8–11. Open a saved light: it should connect automatically with a compact indicator. Test its identified profile's power, brightness, RGB and supported White mode against the physical light. Verify warm/balanced/cool presets and the full slider range, switch back to the remembered RGB selection, and compare 1/50/100% brightness with the vendor app. Refresh and verify actual replies; write acknowledgements must never become fabricated readings. Try permission denial, Bluetooth off, out-of-range lights, another app holding the connection, and unsupported devices. Confirm no large status popups or unsolicited permission dialogs appear.
3. Optional LAN: discover a provisioned LAN-enabled light on the same Wi-Fi using **Find Wi-Fi lights**. Check its model/IP and refresh. Test power, brightness, RGB, and supported Kelvin values against the physical light. Check both success and no-response feedback.
4. Save an optional Developer API key. Discover cloud devices, verify capability ranges, and search/apply a real returned scene. Test DIY, music, and segment settings only when advertised by your model. Refresh afterwards; acceptance is distinct from state confirmation.
5. Create two groups and two quick actions. Rename/edit/delete each. Save multiple device-state presets and confirm each has its own identity. Relaunch and verify names, membership, recipes, appearance, and secure key persist. Readings should start unknown rather than being restored as current.
6. Open a group and verify its Bluetooth members connect automatically. Send a shared control; put one member out of range, inspect the optional failure review, and check the other light still receives its command. Briefly power-cycle a light and verify quiet reconnection with bounded retries. Test quick actions from the Actions tab without first opening a control page. Try overlapping actions and opening/closing a group while one is running; shared connections should remain until their last owner finishes. Android connection limits and group timing vary by phone/firmware.
7. Test app light/dark/system themes, dynamic colors, status bar legibility, landscape, larger text, screen reader labels, keyboard insets, scrollable sheets, and long scene catalogs.
8. Leave a control page, then background the app during discovery/connection/control. It must release owned sessions, stop the scan, close GATT and release local networking without stale connected state. Return to an open control page and verify automatic reconnection. Test rotation, fast page changes, connection cancellation, permission revocation, authentication failures, Wi-Fi changes, guest isolation, VPN, and a busy UDP response port. Permission/authentication failures must stop retries until addressed; an unavailable light must not retry indefinitely.
9. Remove the key and verify cloud commands fail clearly while Bluetooth and LAN remain usable. Keep device Bluetooth addresses, serials, account data, and credentials out of public bug reports. Inspect encrypted persistence with appropriate development tools only on your own test device.

Goveon does not create Android bond records for common direct-GATT lights. Account-authenticated/unsupported pairing handshakes and Wi-Fi credential provisioning are outside this alpha's coverage. If a model requires authentication, report the SKU/firmware and sanitized GATT error rather than assuming an Android Settings bond will fix it. BLE advertisements and LAN/cloud device IDs can differ, so the same physical light may appear as separate entries; the app does not merge them by guessing from a shared model or name.

## LAN discovery diagnostics

LAN discovery needs a physical Wi-Fi connection with an IPv4 address on the lights' network. Internet validation is not required. The app binds its reply socket to UDP port 4002, binds outgoing traffic to the selected Wi-Fi network, joins the discovery multicast group on that interface, and holds Android's Wi-Fi multicast lock while foreground networking is active. Cellular and VPN interfaces are not used as fallback multicast interfaces.

The original generic "Network connection failed" report did not retain the underlying exception, so the exact cause on the reporting phone remains unknown. The updated transport reports which operation failed and preserves the native reason, such as `ENONET`, `EPERM`, or an occupied response port. Record that complete message along with the Android version, whether Wi-Fi is connected, and whether a VPN is active.

Check these cases on a phone before claiming the fix is hardware verified:

- With Wi-Fi disconnected, discovery should ask to connect to the lights' Wi-Fi rather than attempt multicast over mobile data.
- With a VPN active, test whether its local-network access setting permits the physical Wi-Fi socket. An always-on VPN with traffic blocking can still deny socket binding or LAN traffic; the app should preserve the operation and native error.
- Discover a LAN-enabled light with Wi-Fi connected. If discovery completes without finding it, check the light's LAN switch and router guest/client isolation. No response is distinct from socket setup failure.
- Switch Wi-Fi networks, return to the app, and discover again. The app should replace the socket bound to the previous network.
- Close another LAN controller if port 4002 is unavailable. Background Goveon, reopen it, and confirm the port and multicast lock are released and reacquired correctly.

JVM regression tests use actual loopback UDP to exercise the response socket and parser, check socket release after setup failure/cancellation, and verify that detailed LAN errors survive the repository's friendly-error mapper. They do not execute Android's network-binding API or verify Wi-Fi multicast reception, router behavior, or real light firmware.

Platform references: [socket-to-network binding](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket)), [Wi-Fi multicast locks](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock), and [multicast interfaces and group membership](https://developer.android.com/reference/java/net/MulticastSocket).

## Reports

JVM results: `app/build/reports/tests/testDebugUnitTest/index.html`.

Lint: `app/build/reports/lint-results-debug.html`.

Checksums, package metadata, and final build results are recorded in [validation](validation.md). For hardware reports, record phone/Android version, light SKU/firmware, connection route, the control used, and the command result. Actual hardware measurements take precedence over static protocol inference.
