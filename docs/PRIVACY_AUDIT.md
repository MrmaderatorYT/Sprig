# Data safety audit — 2026-11-16

Scope: user-reported Google Play warnings for versionCode2: Installed apps and Device or other IDs. Reviewed source, resolved release dependencies, merged manifest, signed0.1.1 APK/AAB and replacement0.1.2 build. This report distinguishes observed code/data flow from an unverified explanation of Google's detection.

## Baseline: what was found

Baseline signed APK SHA256: `62a93c68b6a989106f57674999d3f773396bfe20f74987aa147f12134d1cf7e2`. Its two DEX files are byte-identical to the corresponding base/dex files of the signed0.1.1 AAB. [DEX references](privacy/0.1.1-dex.json), [resolved dependency graph](privacy/0.1.1-runtime-dependencies.txt).

| Question | Observed evidence | Conclusion and limit |
| --- | --- | --- |
| Does Sprig access installed apps? | MainActivity.chooseApps invoked PackageManager.getInstalledApplications, then filtered launchable apps, on a worker after the user tapped Choose apps. | Yes, local access. Not evidence of off-device collection. Broader API than necessary; replaced with launcher query. |
| Where does that list go? | Labels/package names populate a local picker. Selected package names go to private SharedPreferences selected_apps, then VpnService.Builder.addAllowedApplication. HAR/report/OAST code does not read those preferences or the catalog. | No inventory upload or insertion into outbound requests/exports found in the reviewed paths. This is source tracing, not a device packet trace. |
| Does getDeviceId identify the handset? | Actual DEX method owners are android.view.KeyEvent and android.view.MotionEvent. AndroidX uses them for keyboard selection and scrolling/haptics. | Input event device IDs, not TelephonyManager.getDeviceId, Android ID, IMEI, MAC or advertising ID. These local UI calls remain; no corresponding outbound path found. |
| Are persistent ID APIs present? | DEX scan found no candidate TelephonyManager/Build.getSerial/WifiInfo MAC/NetworkInterface hardware-address/advertising-ID/Firebase-installation getter, Settings.Secure method reference or android_id marker in the baseline. | No positive evidence of those collection APIs. Static references cannot rule out every reflection/native/dynamic behavior; this APK has no native .so files. |
| Do dependencies add telemetry? | Runtime graph consists of Bouncy Castle, Netty, Kwik/Agent15/QPACK, HKDF/SipHash, Gson, RE2J, AndroidX/Material and Java desugaring dependencies. No analytics, advertising, Firebase, attribution or crash-reporting SDK in the resolved graph. Netty's unused hardware-address machinery from its full JAR is not referenced in the release DEX. | No telemetry SDK or developer telemetry endpoint found. A vendor-prefix string scan alone would not prove this; graph/source review supports it. |
| Does the management HTTP client add device metadata? | CollaboratorClient did not override HttpURLConnection User-Agent; Android runtime defaults may include OS/model/build information. | Optional user-selected server requests could receive this metadata. Set a static User-Agent in0.1.2 and verify create/poll/revoke requests against a local server. This is not proof of a persistent handset ID or Google's exact trigger. |
| Is any implicit external-service path present? | Merged baseline manifest eagerly initialized EmojiCompatInitializer. Its default implementation discovers a system downloadable font provider and requests the emojicompat font. | An unnecessary implicit provider-loading path is confirmed. It may download through the provider; actual traffic or device IDs from that provider were not captured here. Disabled in0.1.2. |

There is no proof that Google attributed its two warnings to these exact APIs or to the font initializer. The screenshot supplies categories and versionCode, but no endpoint, payload, SDK attribution or packet evidence. A false-positive/static heuristic is plausible, not established. Changes below minimize access and implicit service use; they do not establish that Google's warning has been cleared.

## Important distinction: network inspection still transmits traffic

Sprig forwards selected apps' network traffic to original destinations. That traffic can itself contain cookies, authentication tokens, account/device identifiers or other data supplied by those apps. Repeater/Intruder/Scanner/WebSocket send user-configured requests to selected targets. Collaborator sends API credentials and random per-test tokens to the user's configured server; it has no default external endpoint. These are different from Sprig reading/uploading a device identifier or an installed-app inventory.

Settings' own package/version are displayed locally. CA serials, QUIC IDs and callback session tokens are random protocol/security identifiers, not sourced from phone hardware. Explicit exports and voluntary email feedback are separate user actions. Do not infer that all Data safety questions should be answered No from this audit.

## Implemented changes in 0.1.2 Preview / versionCode3

1. Query only ACTION_MAIN + CATEGORY_LAUNCHER activities on demand; deduplicate packages and exclude Sprig. No getInstalledApplications/getInstalledPackages. Preserve private selection and VPN routing behavior.
2. Explain local-only app selection next to Choose apps. No catalog data is added to HTTP history, exports, support intents or network requests.
3. Remove only EmojiCompatInitializer startup metadata; preserve lifecycle/profile startup components. Use platform emoji fonts, whose newest-character coverage can differ on older Android. Declare the already-resolved startup-runtime1.1.1 directly for source-manifest class verification, without upgrading runtime versions or adding a new SDK.
4. Before each user-started capture, show a rotation-safe explicit disclosure of sensitive traffic, local capture storage, forwarding, user-selected tool destinations and stop control. Declining does not prepare VPN or start capture. Agreeing proceeds to standard Android permission flow; it does not grant VPN permission itself.
5. Replace the ambiguous status text "traffic stays on this device" with "capture history stored locally". Add an in-app public privacy-policy link and EN/UK policy describing local selections, traffic forwarding, plaintext protocols, exports, CA storage and optional services/feedback.
6. Set a constant `Sprig-Collaborator/1` User-Agent on Collaborator API requests, avoiding Android's model/build-bearing default. A loopback HTTP fixture verifies all three operations, an empty body and only their explicit session/admin credentials. Forwarded traffic and user-created request templates are not rewritten.
7. Add a JDK17 audit utility, tools/ReleasePrivacyAudit.java, so release DEX references can be checked independently of JADX decompilation. --check flags broad inventory APIs, candidate persistent-ID APIs/markers or known tracking-SDK classes for review; this is a regression boundary, not an exfiltration detector.

## Verification and reproducibility

```sh
java tools/ReleasePrivacyAudit.java dist/releases/0.1.1-preview/Sprig-0.1.1-Preview.apk /private/tmp/sprig-v2-dex.json
java tools/ReleasePrivacyAudit.java dist/releases/0.1.2-preview/Sprig-0.1.2-Preview.apk /private/tmp/sprig-v3-dex.json --check
./gradlew --max-workers=2 :core:test :oast:test :collaborator-server:test :benchmark:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

Five new Robolectric tests cover catalog deduplication/self exclusion, malformed entries, decline across rotation, explicit acceptance before Android VPN consent, and no EmojiCompat configuration or JDK socket attempts while navigating the main workspace. An additional OAST JVM loopback test records actual create/poll/revoke requests to verify the constant User-Agent and absence of extra request bodies; it is not an Android packet capture. The socket guard is test-only, relies on the specified JDK17 SecurityManager facility and emits its deprecation warning; it is not in Android production code. Its window starts after the test Application was created and covers main-workspace navigation, not all app/network features or Android/GMS behavior. Manifest/initialization assertions separately check that the downloadable-font initializer is absent.

JADX1.5.6 simple-mode baseline decompilation reported20 reconstruction errors; it was used for navigation, not as proof that a method is absent. Raw DEX references and actual source/data flows are the stronger evidence here. The utility scans known API/marker families, not arbitrary obfuscated behavior or every possible SDK.

Fresh automated results and artifact verification are in [RELEASE_0_1_2_PREVIEW.md](RELEASE_0_1_2_PREVIEW.md). No connected device appeared in `adb devices -l` during this audit, and no emulator is installed. Therefore no device packet capture, provider egress observation, fresh device VPN/UI acceptance or Play re-review outcome is claimed. Earlier Pixel7a reports are historical evidence of their respective builds only.

## Google Play next step

Upload the new signed code3 AAB rather than reusing code2. Use the published privacy-policy URL, and ensure the store listing/Data safety declarations reflect actual forwarding, optional services, exports and support processing. Do not mark Installed apps or persistent IDs as collected merely to silence a warning if that transmission is not actually present. If Google still flags these categories, request the endpoint, data field and SDK/call attribution, and provide this report plus the new APK/AAB hashes. The developer's actual Console form and previously linked policy were not available for verification in this audit; no form or review submission has been changed automatically.

Primary references:

- [Google Data safety scope and on-device processing](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
- [Android InputEvent.getDeviceId](https://developer.android.com/reference/android/view/InputEvent#getDeviceId()).
- [Android EmojiCompat downloadable fonts and initializer control](https://developer.android.com/develop/ui/views/text-and-emoji/emoji2).
- [Google user-data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).

- [AOSP RuntimeInit default HttpURLConnection User-Agent](https://android.googlesource.com/platform/frameworks/base/+/b9c98916b11932e7e55849c9c69d116002671a0e/core/java/com/android/internal/os/RuntimeInit.java).
