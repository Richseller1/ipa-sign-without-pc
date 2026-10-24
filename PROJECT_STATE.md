# PROJECT STATE

## Current phase
Phase 7: GrandSlam AppTokens (com.apple.gs.xcode.auth) & AES-GCM Extraction Pipeline -> Ready for Device Verification

## Stage table
| Stage | Code Exists | Called From UI | Tested How | Label |
|---|---|---|---|---|
| USB Permission | Yes (`UsbBridge.kt`) | Yes (`onRequestPermission`) | Real iPad hardware test (IMG_0249.jpeg) | PASSED |
| USB Open + Claim | Yes (`UsbBridge.kt`) | Yes (`openDevice`) | Real iPad hardware test (IMG_0249.jpeg) | PASSED |
| usbmuxd Embedded Start | Yes (`UsbBridge.kt`, `usb_jni.c`) | Yes (`start()`) | Real iPad hardware test (IMG_0249.jpeg) | PASSED |
| Lockdown Pairing / Trust | Yes (`lockdown_jni.c`) | Yes (`queryLockdownDeviceInfo`) | Real iPad hardware test (IMG_0249.jpeg) | PASSED |
| Device Info (Name, iOS, UDID) | Yes (`lockdown_jni.c`) | Yes (`queryLockdownDeviceInfo`) | Real iPad hardware test: iPad15,8, UDID 00008120...46601E | PASSED |
| Anisette Client | Yes (`AnisetteClient.kt`) | Yes (`inspectSignInRequest`) | Real server test (ani.sidestore.io reachable 1322ms) | PASSED |
| GrandSlam Login (SRP) | Yes (`AppleAuthClient.kt`) | Yes (`onSignInClick`) | Real user Apple ID sign-in | PASSED |
| 2FA Verification | Yes (`AppleAuthClient.kt`) | Yes (`onSubmit2FaClick`) | Real user 2FA submission (code accepted) | PASSED |
| GrandSlam AppTokens & GCM Decrypt | Yes (`AppleAuthClient.kt`, `SrpCrypto.kt`) | Yes (`signIn`, `submitTwoFactorCode`) | Offline unit test (`SrpAuthUnitTest`) | PASSED |
| Developer Team Lookup | Yes (`AppleDeveloperClient.kt`) | Yes (`refreshTeams`) | Blocked in prior run by empty token; code inspected | CODE-CHECKED |
| Device (UDID) Registration | Yes (`AppleDeveloperClient.kt`) | Yes (`executeFullProvisioning`) | Not tested on device | UNTESTED |
| CSR + Certificate | Yes (`CsrGenerator.kt`, `AppleDeveloperClient.kt`) | Yes (`executeFullProvisioning`) | Unit test only (`ProvisioningUnitTest`) | PASSED |
| App ID Registration | Yes (`AppleDeveloperClient.kt`) | Yes (`executeFullProvisioning`) | Not tested on device | UNTESTED |
| Provisioning Profile Download | Yes (`AppleDeveloperClient.kt`) | Yes (`executeFullProvisioning`) | Not tested on device | UNTESTED |
| zsign IPA Signing | Yes (`IpaSigner.kt`, `zsigner_api.cpp`) | Yes (`signIpa`) | Fake cert on PC only | UNTESTED |
| AFC Upload & Instproxy Install | Yes (`install_jni.c`) | Yes (`installIpa`) | Real test gave AFC Transfer Failure | UNTESTED |
| App Launch | No | No | Not implemented in mobile substrate | CODE-CHECKED |
| Installed Apps List | No | No | Not implemented | CODE-CHECKED |
| 7-Day Renewal / Re-sign | Yes (`ProvisionedIdentity` expiry check) | UI warning only | Not tested on device | UNTESTED |
| Adaptive & Responsive UI | Yes (`MainActivity.kt`, `UiPreviews.kt`) | Yes (`AppScaffold`, `NavigationRail`, `Scaffold`) | Compose previews & code inspection | CODE-CHECKED |

## Claims ledger
| Claim | Label | Evidence |
|---|---|---|
| Anisette client works against real servers | PASSED | Confirmed ani.sidestore.io reachable (1322 ms) |
| USB single-owner claim and lockdown handshake | PASSED | Hardware test photo showed iPad15,8 detected with UDID |
| USB attach/detach crash fix | CODE-CHECKED | Added package to PendingIntent and hardened BroadcastReceivers |
| GrandSlam 2FA verification prompt delivery | PASSED | User confirmed receiving 6-digit code and authenticating successfully |
| AppTokens request & AES-GCM token extraction | PASSED | Unit tested offline (`testAppTokensChecksumCalculation`, `testAesGcmEncryptAndDecryptRoundtrip`) |
| Developer Team lookup with xcode auth token | UNTESTED | Code verified; pending execution on user device |
| Certificate & Profile download from Apple | UNTESTED | Code verified; pending execution on user device |
| zsign engine with real Apple certificate | UNTESTED | Fake self-signed cert on PC only |
| Adaptive layout across phone, tablet, TV, and font scales | CODE-CHECKED | Validated via Compose previews; pending user verification on Galaxy A14 |
| Application ID is com.aistudio.iossideloadbridge.kxmpzq | PASSED | Verified in app/build.gradle.kts and compiled manifest |
| All native prebuilt libraries (arm64-v8a, x86_64) are present in app/src/main/jniLibs | PASSED | Verified in jniLibs directory and in final APK |

## Open bugs & Resolutions
1. **USB `USB_DEVICE_ATTACHED` receiver crash**: Resolved by setting explicit package `setPackage(context.packageName)` and `FLAG_MUTABLE or FLAG_UPDATE_CURRENT` on `PendingIntent.getBroadcast()`, hardening `attachDetachReceiver` and `permissionReceiver`, and adding `onNewIntent` in `MainActivity`.
2. **Empty PET / Xcode token after 2FA**: Resolved by implementing the `apptokens` request to `GsService2` with HMAC-SHA256 checksum, decrypting the response `et` field using AES-GCM and session key `sk`, and re-running the full SRP handshake on 2FA validation to complete the second pass.
3. **Premature "Authenticated" status**: Resolved by gating "Authenticated" badge on `session.tokens["com.apple.gs.xcode.auth"].isNotBlank()` and returning explicit errors if any step fails.
4. **AFC Transfer Failure on IPA install**: Lockdown pairing/trust required before AFC access.

## Build id history
- Baseline commit: `45837db` (baseline: state before rework)
- Fix commit: `5d5dc06` (phase2_and_3: USB single-owner and TLS anchor fixes)
- Crash fix commit: `dbaaebf` (fix(compose): eliminate return@Column crash in ProvisioningPipelineCard)
- Cert & Key Hardening: Phase 6 (unwrapped PKCS#1 DER key, robust cert/profile decoders)
- AppTokens & GCM Decrypt: Phase 7 (GsService2 apptokens, AES-GCM decryptDataGcm, 2FA re-run pass)
- 2FA Trigger Endpoint: Phase 8 (explicit GET /auth/verify/trusteddevice with fresh anisette, resend button)
- 2FA AltSign Headers & Anisette Consistency: Phase 9 (single-fetch anisette reuse, AltSign Xcode header parity, pre-2FA identity fallback, diagnostic header trace)
- 2FA Header Hardening: Phase 10 (removed Cookie header, removed dsid/u fallbacks, redacted SignInContext.toString)
- 2FA Error Reporting & Second-Pass Inspection: Phase 11 (step attribution, HTTP/ec/em parsing, idTokenSent status)
- 2FA Validate Success & Second-Pass Tracing: Phase 12 (HTTP 2xx + ec absent/0 success condition, removed responseDict==null check, validate response keys logging, second pass start/end diagnostics)
- AppTokens & Universal Wrapper-Level Parsing: Phase 13 (unified AppleResponseHelper, unnesting Response/Status across init/complete/validate/apptokens/developer services, detailed et decryption attribution)
- AppTokens Binary Cookie & AltSign CPD Parity: Phase 14 (spd['c'] as ByteArray <data>, full cpd dictionary parity with AltSign booleans/strings, lowercase appleId normalization, pre-request spd['c'] and cpd diagnostics)
- AltSign Status au & Diagnostics Parity: Phase 15 (reading au from Status dictionary with fallback to activeDict, au location & value logging)
- Build ID: `bld-20261014-signin-altsign-v11`
- Build Timestamp: `2026-10-14 13:10 UTC`
