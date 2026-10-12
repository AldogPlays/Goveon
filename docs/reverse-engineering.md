# Govee investigation and Goveon design

This is a bounded static investigation of the user-supplied APK, performed with the **REA skill**, REA 6.4.0 and headless JADX MCP 0.7.1. The APK was never executed. No phone traffic or physical device behavior was captured. The resulting app is original Kotlin/Compose code; the APK and decompiled source are excluded from Git.

## Artifact and reproducibility

- Input: `reference/com.govee.home_6.2.01-778_minAPI24(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk` (the workspace uses singular `reference/`).
- APK SHA-256: `833b42b39fc1c68a807adaa37fb8261ee13f52dee85c142882c6e09e7ec2d452`.
- REA package inspection: `ev_30b3145372064b2aea71f15fc4bbc85f54e4d898e3d99baeb4c8fd617fdcf1d8`.
- Observed package/version: `com.govee.home`, 6.2.01 / 778, minimum SDK24, target SDK34; 40,672 classes and 14,235 resources.
- JADX engine JAR SHA-256: `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`; revision `5844800a486d2248fa949b74c9896285f5b54de2`; one analysis worker.

[Evidence index](evidence-index.json) records exact selectors, evidence IDs, APK digests, authority/confidence, source coverage and limitations. Complete CLI envelopes are retained locally under ignored `docs/evidence/`, including the decoded manifest and class inventory. The index is an analyst-generated metadata catalog, not a replacement for the original REA records. APK signatures and native libraries were not analyzed by this Android provider.

Example replay after supplying the same APK and verified engine:

```powershell
./scripts/toolchain.ps1 rea inspect-android-package 'C:/absolute/path/govee.apk' --format json --full-output
./scripts/toolchain.ps1 rea inspect-android-class 'C:/absolute/path/govee.apk' com.govee.base2light.ble.controller.BrightnessController --format json --full-output
./scripts/toolchain.ps1 rea inspect-android-method 'C:/absolute/path/govee.apk' com.govee.base2kt.utils.BleUtils.Companion i --overload-index 0 --format json --full-output
```

REA's Android provider required a caller-supplied JADX engine and JDK. The headless workspace toolchain supplies those without changing Codex registration. Its Windows process-isolation helper required an execution environment with access beyond the restricted shell sandbox. This did not authorize or execute the target APK.

## Finding ledger

Evidence IDs below refer to decompiler observations (`shipped-artifact` authority, derived confidence), not runtime measurements. Public protocol sources are listed separately.

| Question | Observation and evidence | Coverage / design consequence |
| --- | --- | --- |
| How does Bluetooth discovery start? | `ScanManager.m` saves scan parameters, resets existing scanning and schedules discovery; `k` checks Bluetooth availability and calls the scanner with mode flag false. `ev_d91c876ef12a7fff09a4a5c2020e24060c43b712f4403d9bfcc20c13e8474238`, `ev_c87fa261276cb822165482f9d229ddd0de593b8f33727b2e8c54ce22cd619139`. | Partially answered. Availability/lifecycle gates matter; actual advertising data and caller-supplied filter configuration remain unknown. |
| Which scan API/filters are used? | `BleScanCallbackImp21.startBleScan` calls `BluetoothLeScanner.startScan`; `f` constructs separate service-UUID, exact-address and exact-name filters from parameter arrays, with a null-filter fallback. `d` selects scan mode1 or2; the inspected discovery path uses mode2. `ev_7bb932132b47d35ead5385ad064bec9b7b7d3fc87036f928c3466aa24e0735b2`, `ev_50fb8b70c402d68cefb756e3005f6c96c24965f39c4c9f38ba79957bdc3f9617`, `ev_6cd8e92751ddc3ae0f45ad6d23da62f3712b5cc2ffaa2f47e20137f45c7c4a57`. | Answered for these methods. It does not establish which UUIDs or advertisements identify every model. |
| How are common BLE messages framed? | `AbsSingleController.g` passes protocol type, command type and payload into `BleUtils.e`, which delegates to `Companion.i`. The builder allocates20 bytes, puts the protocol byte at0, command at1, payload from2, and checksum at19. `getProType` returns write0x33/read0xAA. `Companion.m` XORs the first19 bytes. `ev_e413a8eed39404d6a5815af45ecc8b4186f1c3f8bc33b9ec5262b855fbf87de8`, `ev_04bc7f396a93868131410e13de40d0877c0ed5f9850e8af89c0684956b55b45e`, `ev_b5ff2a51e7f29870b2d9671c41981bde6e79f5b611630153816e078247ad2f6f`, `ev_170fbc3c3ddfb05e6ef7e8c6c6dc86c012ce57303d1a79e54add3c4177467435`. | Answered for the common builder. It is not a universal Govee BLE profile or a verified connection/handshake sequence. |
| What are the basic command tags? | `SwitchController.getCommandType` returns0x01, with a one-byte stored payload in `q`. `BrightnessController.getCommandType` returns0x04, `q` casts its stored integer to one byte, and its integer constructor directly stores the input. `ev_e923087a6af3001dede99875bae1249d89dd545f5504d2e7f9e3bc1be8b86311`, `ev_6c1a6173817c044d4369ff5d5e9b03f86bb7b1a7fe8e65444bf5e005958f1743`, `ev_ae6e6143bf24fd65cde9922f343a635e88265ff4801a15cdb7a9e49b3837b165`, `ev_4be221f001571c8ae15617849eeba51f3c145c21a0f4307ab50104f01b3e320e`, `ev_c5e79f5ba2cc12182d5ded224ffff7e8b1b85663acf71e35ce6493f72c4190a2`. | Partially answered. Model brightness scaling, acknowledgements and permitted ranges are not established by these methods. |
| Are these controllers actually referenced? | REA incoming references to `BrightnessController` returned543 static references across common UI/group and model-specific lighting code. `ev_a4b2faac56f4d5815f7d7599b40f7510208f5f62033cfbeea69b91c176348858`. | Complete within this static-reference query. Reflection/dynamic loading are outside coverage; references do not prove executed behavior on a particular model. |
| Is a WLAN switch equivalent to ordinary lighting frames? | `WlanControlOperator.g` writes `CmdWlanSwitch`. `WlanController` overrides its frame with a3-byte command/constant/checksum message; its type differs for write/read (0xE3/0xEA). `ev_9d2b1d2d7dee0af6ac8cbed88774ec7708a32cad5aca62a8a52e60869ac12f21`, `ev_038544fb8c8f747dd8730aef5ac5cba31f302e5e1ecd4d8b8c995b772c9aeb52`, `ev_3639a5d050c75ddc2d9b82e50262e8afcad1ca8bd2b403e6069df7c6718b1fff`. | Answered for the inspected implementations. Control and setup packets can differ; do not wrap every command in one universal frame. |
| How are cloud messages routed? | `IotClientProxy.sendMsg` delegates to `H`, which converts a typed `GMessage` with `trans2Iot()` and selects read/write paths. `F` routes reads through `Iot.n/o`; `M` routes writes through `Iot.w`. The raw-byte `sendMsgBytes` path delegates to `I`, which returns false. `ev_7ba96af1744890f83462ec4ace083bfdb4f68fa4ad50178c179ee1816c5ed108`, `ev_663de416770aa020495de92991bde0cd67e375d5dde5374651b445af479ba99e`, `ev_f0516a7f1028d0a3e18ae3c0b791103654cc6fcb766852fecfa70b7e96228515`, `ev_4ef070cfb1cb33a4f0af68cc8630694fd12a4921a80bff4dccb3ab7edcd15af3`, `ev_8cdd6bc82a5e9cfc8a2735ae44b67f2ba7c2ae27f938a0cf45a1d7bc8e056d85`. | Partially answered. The vendor transport is semantic and device-associated; this does not establish account authentication, broker topics or device execution. Goveon uses the public developer API. |

## Corrections and bounded negative results

Names alone were not treated as behavior. `com.govee.base2light.Uuid` contains a login callback and string state; its inventory did not establish Bluetooth service UUIDs. A more specific `BleCommUUID.<clinit>` initializes `00010203-0405-0607-0809-0a0b0c0d1910` and `00010203-0405-0607-0809-0a0b0c0d2b11` (`ev_2c497ca6021a93a08f0c9253ea58adedd2bd1e9a7ab8a8b64447e391d88e2bda`). These are observed UUID candidates; their service/characteristic roles and model coverage were not established by the initializer. `BleProtocolConstants.<clinit>` only initializes direction values5/6, so it did not establish packet constants. `BleUtils.g` reaches a different helper that sums bytes modulo256; it is not the common20-byte frame's XOR checksum. These intermediate probes are retained in the evidence index so later work does not repeat the same mistakes.

The complete class-name inventory contains numerous model-specific BLE protocols, callbacks and controllers. A bounded name search did not identify an obvious dedicated UDP implementation. That is not evidence that LAN behavior is absent: obfuscated names, native code and other paths were not exhaustively traced. Goveon's LAN commands therefore cite the public specification rather than misattributing them to these APK methods.

## Public specifications used by the implementation

- [Govee LAN guide](https://app-h5.govee.com/user-manual/wlan-guide): multicast `239.255.255.250:4001`, replies on4002, controls on4003; JSON `scan`, `devStatus`, `turn`, `brightness`, `colorwc`. This supplies Goveon's account-free LAN boundary.
- [Device capabilities](https://developer.govee.com/reference/get-you-devices): SKU/device identity, exact capability schemas, numeric ranges and option values.
- [Control API](https://developer.govee.com/reference/control-you-devices): structured capability values, segments and music settings.
- [Device state](https://developer.govee.com/reference/get-devices-status): offline values are historical. Goveon retains timestamps and does not manufacture fresh readings.
- [Scene and DIY API](https://developer.govee.com/reference/get-light-scene): live catalogs and exact scene objects/IDs, replacing retired dynamic options while preserving device-list static options.

The public specifications and the app's private transport are distinct evidence sources. Goveon does not use captured account tokens, embedded credentials, guessed broker endpoints, or copied vendor code. Network payloads are independently encoded and tested against the documented formats.

## Design choices derived from the investigation

1. Separate transport, protocol and capability state. The APK's controller families and typed cloud conversion justify avoiding one guessed universal protocol.
2. Prefer LAN for simple controls and allow documented cloud fallback. Bind local sockets to the Wi-Fi network, hold multicast only while foreground, and report response failures explicitly.
3. Drive scenes/effects/segments/music and range limits from the actual cloud device schema. Do not advertise unsupported controls or assign speculative scene IDs.
4. Preserve unknown/historical state and distinguish command acceptance from confirmation. Concurrent group fan-out retains each member's outcome; it cannot provide hardware clock synchronization.
5. Keep original uncluttered UI and private local customization. TrailRelay contributed design principles, not its Android Views implementation or Govee's UI.

## Remaining work requiring further evidence

Bluetooth-only control/onboarding needs verified service/write/notify UUIDs, advertisements, connect/initialization/keepalive sequence, state parsers, model profiles and physical tests. Proprietary streaming, DreamView, native scene catalogs and arbitrary DIY editing remain unresolved. Account/broker authentication and native-library behavior were not reconstructed. Those gaps are documented instead of shipping guessed packets as supported functionality.

Discovery, communication and basic control questions are answered within the named static/public-protocol boundaries. Broad model-specific behavior remains partially answered. This report does not claim complete source equivalence or full Govee feature parity.
