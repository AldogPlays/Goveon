# Implementation notes

Technical details for contributors. For installation and everyday use, see the [README](../README.md).

Goveon starts empty and sends actual GATT writes or UDP packets. A command can be **confirmed**, **accepted**, or **failed**. Delivery acknowledgement does not prove a physical change. Only actual device readings update reported state. Groups have no shared hardware clock.

Names, device identifiers, groups, actions and appearance stay in app-private storage. Backup is disabled. Runtime readings and connections are never restored as current state. Supported foreground Bluetooth sessions request status every 20 seconds. Leaving controls releases their sessions; quick actions retain their required connections until completion. Backgrounding stops scans, releases GATT and releases the multicast lock. Returning to open controls reconnects automatically. Retries stop after five attempts or immediately for permission/unsupported/authentication failures.

Version 1.0 removes the developer API implementation entirely, including key entry/storage, HTTPS requests and cloud scenes/effects. Upgrading retains local lights, groups and valid basic recipes, drops cloud-only devices and recipes containing removed commands, and deletes retired credentials without decrypting them. A damaged saved record does not discard unrelated valid entries.

Detailed sanitized diagnostics stay in local Logcat under `Goveon`. Credentials and device/network addresses are redacted.

H6006's traced full-brightness command sends byte **100**; H6005 uses its separate recovered 20–254 scale. Physical brightness and white-channel calibration require comparison on hardware. RGB white and native White use different payload fields.

`ui/` contains Compose destinations and contextual controls. The ViewModel handles actions/progress. `lighting/` contains independent codecs, BLE/LAN transports, versioned persistence and the repository. Commands serialize per target; group members run concurrently. Coroutine cancellation and foreground ownership stop abandoned operations. There is no DI framework or custom backend.

Raw reference APKs, REA envelopes, captures, local tool configuration, keys and build artifacts are ignored. Public evidence contains selectors, IDs and artifact digests. Run `./scripts/check-public-files.ps1 -History` locally to check public source and reachable commit identities before publishing. This pattern scan is an additional guard, not a guarantee of detecting every secret format. Keep account/device identifiers out of public issues.
