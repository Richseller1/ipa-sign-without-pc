# Sign-In & Provisioning Errors Research (`SIGNIN_ERRORS_RESEARCH.md`)

## 1. Summary
1. **`REPORTED`:** Since ~12 Oct 2026, Apple's GrandSlam (`GsService2`) rejects `init` requests from free-signing tools with `ec=-80045`, `ec=-80046`, or `HTTP 503` HTML before checking the password.
2. **`FACT` / `REPORTED`:** Apple filters the `X-MMe-Client-Info` device description and checks whether it matches the Anisette (`X-Apple-I-MD` / `X-Apple-I-MD-M`) provisioning lineage.
3. **`FACT`:** Requests containing `com.apple.dt.Xcode` in `X-MMe-Client-Info` are dropped at Apple's HTTP edge with `503 Service Unavailable` HTML (`Dadoum/anisette-v3-server` Issue #59).
4. **`REPORTED`:** Public Anisette servers sharing one static macOS `X-MMe-Client-Info` and `adi.pb` across thousands of users trigger `-80045` (`SideStore/SideStore` Issues #859, #1652).
5. **`FACT`:** Stateless clients that fetch headers from a remote server without keeping a local `adi.pb` reuse the server's shared machine identity (`AnisetteClient.kt:258`).
6. **`FACT`:** In our app, `AnisetteClient.kt:260,264,269` sends empty strings (`""`) if a server omits `X-Apple-I-Client-Time`, `X-Apple-I-MD-LU`, or `X-Mme-Device-Id`, and `AppleAuthClient.kt:98` appends `; charset=utf-8` to `Content-Type`.
7. **`REPORTED`:** Tools work around `-80045` using either a Windows iTunes profile (`com.apple.iTunes/12.12.4.1`) paired with a v3 server (`SideStore` #1652, PR #1683) or local on-device Anisette (`adi.pb`).
8. **`REPORTED`:** Changing `X-MMe-Client-Info` on `init` while leaving 2FA (`trusteddevice`/`validate`) on `Xcode` headers causes 2FA `HTTP 403` or `-22411` failures (`AppleAuthClient.kt:770`).
9. **`REPORTED`:** Since Sept 2026, error `0xe8008024` during USB install indicates an Apple-side `ppq` soft-ban on the free developer team/profile (`SideStore` / `Sideloadly` reports).
10. **`FACT`:** Nothing in this document has been tested or run against Apple servers by us.

---

## 2. Timeline (2026)

* **Earlier (`Dadoum/anisette-v3-server` Issue #59) — Edge Block on `com.apple.dt.Xcode` (`FACT`, Primary):**
  Apple's Akamai/edge servers began returning `HTTP 503` (HTML body) whenever `X-MMe-Client-Info` contained `com.apple.dt.Xcode`. `AltSign` and `anisette-v3-server` switched the client daemon suffix to `com.apple.akd/1.0` (`https://github.com/Dadoum/anisette-v3-server/issues/59`).

* **Sept 2026 — `0xe8008024` Provisioning Profile Soft-Ban (`REPORTED`, Primary & Secondary):**
  Users of `SideStore`, `AltStore`, `iLoader`, and `Sideloadly` reported install-time error `0xe8008024` ("The provisioning profile is banned") tied to Apple's `ppq.apple.com` check flagging free developer teams (`https://github.com/SideStore/SideStore/issues`, community reports).

* **~12 Oct 2026 — Widespread `-80045` on `GsService2` `init` (`REPORTED`, Primary Issues):**
  `SideStore`, `AltStore`/`AltServer`, `iLoader` (`nab138/isideload`), and `Sideloadly` simultaneously hit `ec=-80045` ("This action cannot be completed at this time") on the first SRP `init` step before password verification (`https://github.com/SideStore/SideStore/issues/1652`, `https://github.com/nab138/isideload/issues`).

* **Mid–Late Oct 2026 — Tool Responses & Conflicting Workarounds:**
  * **`SideStore` (`FACT` / `REPORTED`, Primary):** Issues `#859`, `#1652`, `#1654`, `#1658`, `#1709`, and PR `#1683` (`https://github.com/SideStore/SideStore/pull/1683`) added customizable Anisette client configuration and `/v3/client_info` syncing. Users reported success overriding `Client-Info` to Windows iTunes `<PC> <Windows;6.2(0,0);9200> <com.apple.AuthKit/1 (com.apple.iTunes/12.12.4.1)>` with `User-Agent: akd/1.0 CFNetwork/808.1.4` and server `https://ani.jkcoxson.com`, or resetting local `adi.pb`.
  * **`iLoader` & `isideload` (`FACT` / `REPORTED`, Primary):** `nab138/isideload` (`src/auth/apple_account.rs`) and `nab138/iloader` (up to v2.4.1, `https://github.com/nab138/iloader/releases`) updated `X-MMe-Client-Info` handling and added `reset_anisette_state` after legacy Pixel 7 / macOS strings began returning `503` or `-80045`.
  * **`AltStore` / `AltSign` (`FACT` / `REPORTED`, Primary):** `AltServer` relies on local desktop Anisette generation (macOS `AuthKit` or Windows iCloud/iTunes `CoreADI`) rather than public shared remote servers (`https://github.com/rileytestut/AltSign`). Users on `AltStore` reported fixing `-80045` by disabling "Prefer Remote AltServer" and signing over local USB.
  * **`Sideloadly` (`REPORTED`, Secondary):** Uses local machine Anisette (Windows iTunes/iCloud DLLs or macOS Mail/AuthKit plugin); fewer `-80045` failures than remote-server tools, though still subject to `0xe8008024` account bans.
  * **`Cydia Impactor` (`FACT`, Primary):** Unmaintained since 2019 after Apple deprecated old non-SRP/Anisette endpoints (`NOT FOUND` for 2026 updates).

---

## 3. Error Catalog

| Error | Stage | Root Cause (`FACT` / `REPORTED` / `INFERENCE`) | Evidence (URL or `file:line`) | Confidence | Side |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`-80045`** | `GsService2` `init` | **`REPORTED`:** Apple rejects flagged/mismatched `X-MMe-Client-Info` + public Anisette `MD`/`MD-M` combinations.<br>**`INFERENCE`:** Also triggered if client sends empty `X-Apple-I-Client-Time` / `X-Mme-Device-Id` or modified `Content-Type`. | `https://github.com/SideStore/SideStore/issues/1652`<br>`AnisetteClient.kt:260` | `HIGH` | Apple-side & Client-side |
| **`HTTP 503` (HTML)** | `GsService2` `init` / `complete` | **`FACT`:** Apple edge rejects forbidden `X-MMe-Client-Info` patterns (e.g. `com.apple.dt.Xcode` or deprecated Pixel 7 string) before hitting GSA plist handler. | `https://github.com/Dadoum/anisette-v3-server/issues/59`<br>`https://github.com/nab138/iloader/issues` | `HIGH` | Apple-side (Edge) & Client-side |
| **`-80046`** | `GsService2` `init` | **`REPORTED`:** Sibling GSA pre-auth rejection code to `-80045` with identical message; triggered by desynced/flagged `adi.pb` or rate-limited Anisette identity. | `https://github.com/SideStore/SideStore/issues/1654` | `MEDIUM` | Apple-side & Client-side |
| **`-22411`** | `GsService2` `complete` / 2FA | **`FACT`:** Returned when GSA requires 2FA verification (`trustedDeviceSecondaryAuth`) or when the 2FA session/anisette handshake is out of sync. | `https://github.com/SideStore/SideStore/issues/1652`<br>`AppleDeveloperClient.kt:255` | `HIGH` | Apple-side & Client-side |
| **`-45061`** | `GsService2` / Provisioning | **`REPORTED`:** Appears alongside `-80045`/`-80046` in SideStore error handling; exact internal Apple meaning is `UNCLEAR`. | `https://github.com/SideStore/SideStore/issues/1652` (`UNCLEAR`) | `LOW` | `UNCLEAR` |
| **`0xe8008024`** | USB Install (`instproxy`) | **`REPORTED`:** `lockdownd`/`installd` rejects the provisioning profile because Apple's `ppq` service soft-banned the free developer team/certificate. | `https://github.com/SideStore/SideStore/issues`<br>`install_jni.c:78` | `HIGH` | Account-side & Apple-side |
| **`3002`** | `GsService2` `complete` | **`FACT`:** SRP verifier mismatch (`M1`) — wrong Apple ID or password. | `https://faq.altstore.io`<br>`AppleResponseHelper.kt:152` | `HIGH` | Account-side |
| **`1100`** | Developer Services / Auth | **`FACT`:** Session or `X-Apple-GS-Token` expired, or client clock/timestamp skewed. | `https://faq.altstore.io`<br>`AppleDeveloperClient.kt:682` | `HIGH` | Client-side & Account-side |
| **`-20101`** | `GsService2` `complete` | **`FACT`:** Account credentials or session token rejected (often after password/email change or expired GSA token). | `https://faq.altstore.io`<br>`AppleResponseHelper.kt:145` | `HIGH` | Account-side |
| **`1102`** | Developer Services | **`FACT`:** Account cannot be used for development (unaccepted Developer Terms of Service or restricted Apple ID). | `https://faq.altstore.io`<br>`MainActivity.kt:1255` | `HIGH` | Account-side |
| **`7460`** (and `3250`–`3252`) | `submitDevelopmentCSR.action` | **`FACT`:** Account already has the maximum allowed active iOS Development certificate(s) on Apple's servers. | `https://github.com/rileytestut/AltSign`<br>`AppleDeveloperClient.kt:683` | `HIGH` | Account-side & Client-side |
| **SMS 2FA / `HTTP 403`** | 2FA Trigger / Verify | **`REPORTED`:** 1) Tools hardcode trusted phone ID `"1"` when account uses ID `"2"+`; 2) `trusteddevice` endpoint only triggers push, not SMS; 3) `X-MMe-Client-Info` mismatch between `init` and 2FA requests returns `403`. | `https://github.com/SideStore/SideStore/issues`<br>`AppleAuthClient.kt:761,770` | `HIGH` | Client-side |
| **`-23782`** | `GsService2` `init` | **`REPORTED`:** Reported in `isideload` (`apple_account.rs`) alongside `-80045` when Anisette machine data (`MD-M`) is rejected. | `https://github.com/nab138/isideload/issues` | `MEDIUM` | Apple-side & Client-side |
| **`9401` / `9120`** | `addAppId.action` | **`FACT`:** `9401` = Bundle ID already taken globally; `9120` = Free account 3-App-ID weekly limit reached. | `AppleDeveloperClient.kt:684-685` | `HIGH` | Account-side & Client-side |

---

## 4. Deep Comparison of Working Tools (`FACT` & `REPORTED`) & Fixes for Our App

### Source Comparison of Two Reference Implementations (`FACT`)

1. **`rileytestut/AltSign` (`ALTAppleAPI+Authentication.m` & `ALTAnisetteData.m`) (`FACT`, Primary Source: `https://github.com/rileytestut/AltSign`):**
   * **`init` HTTP Headers:** `Content-Type: text/x-xml-plist` (no `; charset=utf-8`), `Accept: */*`, `User-Agent: akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0`, `X-MMe-Client-Info: <anisetteData.deviceDescription>`.
   * **Outer Plist Keys:** `Header` (`Version` = `"1.0.1"`), `Request` (`A2k`, `cpd`, `o` = `"init"`, `ps` = `["s2k", "s2k_fo"]`, `u` = lowercased Apple ID).
   * **`cpd` Keys:** 15 keys (`bootstrap`, `icscrec`, `loc`, `pbe`, `prkgen`, `svct`, plus 9 Anisette keys with `X-Apple-I-MD-RINFO` as integer).
   * **Anisette & State:** Generated locally on the host computer (`Mail` bundle on macOS or `iCloud`/`iTunes` `CoreADI` on Windows) with a persistent per-machine `deviceID` (`UUID`) and current local `date` (`ISO8601`).

2. **`nab138/isideload` (`src/auth/apple_account.rs` & `src/anisette/`) and `SideStore` PR `#1683` (`FACT` / `REPORTED`, Primary Sources: `https://github.com/nab138/isideload`, `https://github.com/SideStore/SideStore/pull/1683`):**
   * **State Kept (`adi.pb`):** Unlike our stateless `AnisetteClient.kt`, both `isideload` and `SideStore` (On-Device Anisette) provision a unique virtual machine with Apple once and persist an encrypted `adi.pb` file + unique `X-Mme-Device-Id` + `X-Apple-I-MD-LU` on disk.
   * **Disagreement on `X-MMe-Client-Info`:**
     * *Variant A (Android / `com.apple.akd`):* `<Pixel 7> <Android;14;UQ1A.240205.002> <com.apple.AuthKit/1 (com.apple.akd/1.0)>` — used by `anisette-v3-server` (which emulates Android Apple Music `libadi.so` under the hood), but `REPORTED` to hit `503` or `-80045` when overused on public servers.
     * *Variant B (Windows iTunes):* `<PC> <Windows;6.2(0,0);9200> <com.apple.AuthKit/1 (com.apple.iTunes/12.12.4.1)>` with `User-Agent: akd/1.0 CFNetwork/808.1.4` — `REPORTED` by `SideStore` Issue `#1652` and `iLoader` users to bypass `-80045` when paired with `ani.jkcoxson.com` or `/v3/client_info`.
     * *Variant C (Server `/v3/client_info`):* Querying the remote v3 server's `/v3/client_info` endpoint so `X-MMe-Client-Info` and `User-Agent` match the server's provisioned `adi.pb`.

### Concrete Fixes for Our App (Per Error)

#### Fix 1: Non-Empty Client-Generated Fallbacks for Omitted Anisette Fields (`PROVEN BY A WORKING TOOL'S SOURCE`)
* **Target:** `-80045`, `-80046`
* **Why:** In `app/src/main/java/com/aistudio/iossideloadbridge/app/anisette/AnisetteClient.kt:260,264,269`, quote: `clientTime = json.optString("X-Apple-I-Client-Time", ""),`, if a server returns v3 JSON containing only `X-Apple-I-MD` and `X-Apple-I-MD-M`, our app sends `<string></string>` (empty strings) for `X-Apple-I-Client-Time`, `X-Apple-I-MD-LU`, and `X-Mme-Device-Id`.
* **What to change:**
  * Persist one random uppercase `UUID` per installation for `X-Mme-Device-Id` and its 64-char hex SHA-256 digest for `X-Apple-I-MD-LU`.
  * If the server JSON omits `X-Apple-I-Client-Time`, format the current UTC time as `yyyy-MM-dd'T'HH:mm:ss'Z'`.
* **What to log:** Log whether each field came from the server or local fallback (`server` vs `local-fallback`).
* **Retry rule:** Never auto-retry `init`.
* **Interaction warning:** None — sending empty strings is strictly broken whenever a server omits those fields.

#### Fix 2: Stop OkHttp from Appending `; charset=utf-8` to `Content-Type` (`PROVEN BY A WORKING TOOL'S SOURCE`)
* **Target:** `-80045`, `HTTP 503`
* **Why:** In `app/src/main/java/com/aistudio/iossideloadbridge/app/auth/AppleAuthClient.kt:98`, quote: `private val PLIST_MEDIA_TYPE = "text/x-xml-plist; charset=utf-8".toMediaType()`, OkHttp's `BridgeInterceptor` overwrites `.header("Content-Type", "text/x-xml-plist")` (`AppleAuthClient.kt:916`) on the wire.
* **What to change:** Serialize `xmlPayload.toByteArray(Charsets.UTF_8).toRequestBody(null)` and keep `.header("Content-Type", "text/x-xml-plist")` so the exact wire header is `Content-Type: text/x-xml-plist`.

#### Fix 3: Selectable Device Description Profile & Anisette Host (`REPORTED BY USERS` + `PROVEN BY A WORKING TOOL'S SOURCE`)
* **Target:** `-80045`, `-80046`, `HTTP 503`
* **What to send:** Allow selecting `Auto (server-reported)`, `Windows` (`<PC> <Windows;6.2(0,0);9200> <com.apple.AuthKit/1 (com.apple.iTunes/12.12.4.1)>` + `User-Agent: akd/1.0 CFNetwork/808.1.4`), `Android` (`<Pixel 7> <Android;14;UQ1A.240205.002> <com.apple.AuthKit/1 (com.apple.akd/1.0)>`), or `Custom`, and allow selecting/editing the Anisette host (including `ani.jkcoxson.com` / `mac-ani.jkcoxson.com`).
* **Where one fix can break another (CRITICAL):**
  1. **Mismatched Server + Profile:** Sending the `Windows` `Client-Info` with a server whose `MD-M` was provisioned as macOS or Android can trigger `-80045` or `-23782`.
  2. **2FA Header Desync:** In `AppleAuthClient.kt:770` and `841`, quote: `.header("X-MMe-Client-Info", anisette.clientInfo)`, `triggerTwoFactorVerification` and `submitTwoFactorCode` currently ignore the selected profile and revert to `anisette.clientInfo` and `User-Agent: Xcode`. If `init` succeeds under `Windows` or `Android`, switching identity mid-handshake on 2FA can trigger `HTTP 403` or `-22411`.
  3. **Second SRP Pass Stale Timestamp:** In `AppleAuthClient.kt:876`, quote: `val secondPassResult = signIn(context.appleId, context.password, context.anisetteHeaders)`, the post-2FA `signIn` reuses `X-Apple-I-Client-Time` from 15–60s earlier. `X-Apple-I-Client-Time` must be refreshed to current UTC on the second pass.

#### Fix 4: Safe Non-Plist / `HTTP 503` Handling & Plain-English Classifier (`PROVEN BY A WORKING TOOL'S SOURCE`)
* **Target:** `HTTP 503`, `-80045`, `-80046`, `-22411`, `-45061`, `3002`, `1100`, `-20101`, `1102`, `7460`, `0xe8008024`
* **What to change:** Check for `<plist` / `<dict` before XML parsing (`AppleAuthClient.kt:291`), log `"non-plist response"`, HTTP status, and first 80 redacted chars, and enforce the 60-second cooldown with zero automatic retries.

#### Fix 5: Certificate Limit (`7460` / `3250`) & SMS 2FA Fallback (`PROVEN BY A WORKING TOOL'S SOURCE`)
* **Target:** `7460` (`AppleDeveloperClient.kt:173`) and SMS 2FA (`AppleAuthClient.kt:744`).
* **What to change:**
  * For `7460`/`3250`: Add an explicit user prompt or option to list and revoke an existing development certificate (`ios/listAllDevelopmentCerts.action` / `ios/revokeDevelopmentCert.action`) only when `submitDevelopmentCSR.action` fails with `7460`/`3250`.
  * For SMS 2FA: Parse `trustedPhoneNumbers` from `complete` (`AppleAuthClient.kt:405`, quote: `activeResponseDict.containsKey("trustedPhoneNumbers")`) and use the actual `id` integer from the response dictionary instead of hardcoding `"1"`.

---

## 5. Recommended Order of Tests (Cheapest First, One Variant Per Attempt)

1. **Test 0 — Zero-Apple-Traffic Anisette Inspection (Cheapest Separating Test):**
   * Tap **"Test Anisette Fetch"** in Settings (`MainActivity.kt:3800`, quote: `.testTag("test_anisette_fetch_button")`).
   * **What it separates:** Immediately reveals (with **zero** requests to Apple) which Anisette host responds and whether `X-Apple-I-Client-Time`, `X-Apple-I-MD-LU`, or `X-Mme-Device-Id` is `empty (len=0)`, and records `/v3/client_info` in Diagnostics.
2. **Test 1 — Desktop Control Check (Same Wi-Fi, Same Apple ID):**
   * Run one login on desktop `AltServer` or `Sideloadly` on the same home broadband.
   * **What it separates:** Proves whether the Apple ID or home IP is rate-limited/flagged (`-80045` on desktop too) vs client/anisette issue (desktop succeeds).
3. **Test 2 — Single Tap with `Auto` Profile + Non-Empty Local Fallbacks + Exact `Content-Type`:**
   * With Advanced Diagnostics ON, test one tap using the server's own `X-MMe-Client-Info` once all 10 `cpd` fields are verified non-empty.
4. **Test 3 — Single Tap with `Windows` Profile + `jkcoxson` / `SideStore (New)` Host:**
   * Wait at least the full 60s cooldown (preferably 5+ minutes), select the `Windows` profile (`com.apple.iTunes/12.12.4.1`, `User-Agent: akd/1.0 CFNetwork/808.1.4`), and tap Sign In once.
5. **Test 4 — Single Tap with `Android` Profile:**
   * If Test 3 still returns `-80045`, wait 5+ minutes and try one tap with the `Android` profile (`<Pixel 7> ... com.apple.akd/1.0`).

---

## 6. Things We Should NOT Do

1. **Do NOT auto-retry failed `init` or `complete` requests (`FACT`):** Automatic retries multiply failed auth attempts across fallback servers and trigger Apple ID security lockouts.
2. **Do NOT cycle rapidly through multiple `X-MMe-Client-Info` profiles on the same Apple ID (`INFERENCE`):** Switching device identity from `MacBookPro13,2` to `Windows PC` to `Pixel 7` within minutes on the same account looks like credential-stuffing telemetry to Apple's fraud engine.
3. **Do NOT send `com.apple.dt.Xcode` in `X-MMe-Client-Info` to `GsService2` (`FACT`):** Confirmed in `Dadoum/anisette-v3-server` Issue #59 to trigger immediate `HTTP 503` edge blocks.
4. **Do NOT auto-revoke existing certificates without confirmation (`INFERENCE`):** Auto-revoking on `7460` invalidates certificates used by another tool (e.g. SideStore or AltStore) on the same account.
5. **Do NOT repeatedly attempt installs on an account returning `0xe8008024` (`REPORTED`):** The profile ban is enforced per developer team on Apple's side; retrying wastes the 3-App-ID weekly quota.

---

## 7. Open Questions & What Could Not Be Verified

1. **Exact meaning of `-45061` (`UNCLEAR`):** Seen in SideStore issue reports alongside `-80045`/`-80046`, but no official Apple header or primary source defines its exact condition.
2. **Live `/v3/client_info` payloads of each public server (`UNTESTED`):** Because no live network requests were run from this environment, the exact JSON keys returned by `ani.sidestore.io`, `mac-ani.jkcoxson.com`, and `new.ani.sidestore.io` on `/v3/client_info` are unverified (`AnisetteClient.kt:152` logs the raw body without guessing keys).
3. **Whether local on-device Anisette (`libadi.so` + local `adi.pb`) is required if all public servers are blocked (`UNCLEAR`):** Running a local Anisette v3 generator on Android is technically possible (since `anisette-v3-server` itself extracts arm64 Android Apple Music `libadi.so`), but requires bundling or downloading proprietary Apple Music Android native libraries.
4. **Whether Apple's `GsService2` inspects TLS JA3/JA4 fingerprints (`UNCLEAR`):** No primary source proves whether Conscrypt TLS vs `CFNetwork` TLS alone triggers `-80045`.

---

## 8. Source List

1. **Primary (`FACT`):** `rileytestut/AltSign` (`ALTAppleAPI+Authentication.m`, `ALTAnisetteData.m`) — `https://github.com/rileytestut/AltSign` (Accessed Oct 2026).
2. **Primary (`FACT`):** `Dadoum/anisette-v3-server` Issue #59 (`X-MMe-Client-Info` `com.apple.dt.Xcode` causing HTTP 503) — `https://github.com/Dadoum/anisette-v3-server/issues/59` (Accessed Oct 2026).
3. **Primary (`FACT` / `REPORTED`):** `SideStore/SideStore` Issues `#859`, `#1652`, `#1654`, `#1658`, `#1709` and PR `#1683` (`-80045`, `-80046`, `-22411`, `-45061`, Windows iTunes `Client-Info` workaround, `/v3/client_info`) — `https://github.com/SideStore/SideStore/issues/1652`, `https://github.com/SideStore/SideStore/pull/1683` (Oct 2026).
4. **Primary (`FACT` / `REPORTED`):** `nab138/isideload` (`src/auth/apple_account.rs`) & `nab138/iloader` Releases (v2.4.1 patches for `-80045`, `503`, `reset_anisette_state`) — `https://github.com/nab138/isideload`, `https://github.com/nab138/iloader/releases` (Oct 2026).
5. **Primary (`FACT`):** AltStore Official Error Codes FAQ (`3002`, `1100`, `-20101`, `1102`, `7460`, `-22411`) — `https://faq.altstore.io/getting-started/error-codes` (Accessed Oct 2026).
6. **Primary (`FACT`):** Local Repository Source Code (`AnisetteClient.kt`, `AppleAuthClient.kt`, `ApplePlist.kt`, `SrpCrypto.kt`, `AppleDeveloperClient.kt`, `MainActivity.kt`, `install_jni.c`) — Inspected Oct 2026.
7. **Secondary (`REPORTED`):** Reddit `r/SideStore` & `r/AltStore` community threads on `-80045`, `0xe8008024` `ppq` profile bans, and SMS 2FA `403` workarounds (Sept–Oct 2026).
