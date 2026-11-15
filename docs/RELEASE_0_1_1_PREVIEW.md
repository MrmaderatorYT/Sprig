# Sprig 0.1.1 Preview — localization and open beta

Date: 2026-11-15 (Europe/Kyiv). Package `com.ccs.sprig`; versionName `0.1.1 Preview`; versionCode `2`; minSdk26, targetSdk36. Java/Groovy only; no dependency, transport, scope or TLS-policy changes.

## Changes

- 339 previously hardcoded UI/worker/service strings moved into English/Ukrainian resources. Added localized labels/descriptions for all16 Scanner check categories, formatted counters, settings/about and open-beta notice. Resource audit:511 default entries,507 Ukrainian entries; four invariant entries explicitly non-translatable. No missing translatable keys; positional argument signatures match.
- Settings show installed package/version/versionCode from PackageManager, a selectable GitHub URL and source action, feedback action, reopenable beta notice and language-settings action. Android13+ uses native per-app language settings (`localeConfig` declares en/uk); API26–32 follow the device language.
- First launch shows a non-cancelable Material dialog until explicit acknowledgement. DialogFragment retains it across rotation; acknowledgement is saved asynchronously in private preferences. Sending feedback does not acknowledge or dismiss the notice. Reopening from Settings remains possible after acknowledgement.
- Feedback opens ACTION_SENDTO with mailto:cybercraftstudiopro@gmail.com and the version in the subject. No body, attachments, captures or diagnostics are added automatically; no email is sent by Sprig. Missing email/browser handlers produce a localized message.
- Fixed internal traffic filter identifiers remain independent of translated labels. Payloads, wire templates, protocol names and raw errors are not translated or rewritten. Technical core finding/report narratives remain English; this is not a claim of complete technical-report localization or full accessibility acceptance.
- Store artwork committed under store-assets/. GitHub Pages branch play-demo contains EN/UK landing content and subtitles. The existing video is explicitly identified as historical 0.1 Preview/versionCode1 evidence, not a video of0.1.1.

## Automated verification

JDK17, Gradle Wrapper:

```sh
./gradlew --max-workers=2 :core:test :oast:test :collaborator-server:test :benchmark:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

BUILD SUCCESSFUL. Executed suites: core161, benchmark9, app31 (including four new beta/metadata/feedback tests), total201 with no failures/errors/skips. Unchanged OAST2 and collaborator-server6 suites were UP-TO-DATE; their eight prior passing results are retained, not represented as newly executed. Combined reports:209 tests.

Regression tests cover pending notice restoration after rotation, explicit acknowledgement across recreation, feedback intent without data/attachments or implicit acknowledgement, localized English/Ukrainian metadata and source intent. Lint:0 errors/fatal,12 warnings. The added UnusedAttribute warning is expected because Android localeConfig is honored only on API33+; earlier Android uses existing system-language resources. The other11 warnings remain documented in ANDROID_QUALITY.md. No baseline or blanket suppression added. The existing optional Netty BlockHound R8 service warning remains nonfatal.

## Signed artifacts

APK signature v2/v3 verified; zipalign `-c -P16 4` passed; package/version/min/target SDK checked with aapt. Packaged QUIC/Agent15 version resources, QPACK Huffman/static tables and dependency notices/licenses retained; no native .so files. AAB signed with the same developer key and verified with jarsigner. Jarsigner reports the expected self-signed certificate/no timestamp, not a failed signature.

Certificate SHA256: `f5d9e47ab065f8ab8083d920c43ba8b149b52b9bdbe28e9d0cd0163762ea8878`.

APK SHA256: `62a93c68b6a989106f57674999d3f773396bfe20f74987aa147f12134d1cf7e2`.

AAB SHA256: `5289417b37ad26af8115b00d0be90de35363fe6f6d8d09ebe3e47f61fdbdb613`.

## Device verification

Physical Pixel7a/API37: release-signed0.1.1 APK installed over release-signed0.1/versionCode1 with `adb install -r` successfully, without uninstall/data reset. First-launch notice visibly rendered in Ukrainian, then English via native app locale change, remaining unacknowledged across that change. Acknowledgement followed by a normal idle and force-stop/relaunch did not show it again. About/settings metadata and source URL visibly verified in both languages: com.ccs.sprig,0.1.1 Preview,2. Initial empty per-app locale override restored; device-wide language unchanged.

Screenshots: [beta UK](images/0.1.1-preview/beta-uk.png), [beta EN](images/0.1.1-preview/beta-en.png), [about UK](images/0.1.1-preview/about-uk.png), [about EN](images/0.1.1-preview/about-en.png). Only application UI is shown, no captures/keys or personal notifications.

No new VPN/protocol throughput, radio/battery, OEM/API matrix or accessibility/device-rotation acceptance was measured in this pass. Rotation behavior above is a Robolectric regression check. AAB was signed/verified, not uploaded to Play Console or installed through Play delivery. Historical reports retain their original version/evidence boundaries. Older debug previews with another certificate and/or versionCode3 cannot be updated in place by this code2 build.
