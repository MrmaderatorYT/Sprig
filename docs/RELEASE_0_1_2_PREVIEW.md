# Sprig 0.1.2 Preview — privacy audit follow-up

2026-11-16 (Europe/Kyiv). Package `com.ccs.sprig`, versionName `0.1.2 Preview`, versionCode `3`, minSdk26/targetSdk36. This version follows the reported Play Data safety warning for code2; it is not a confirmation of Play approval.

## Changes and evidence

See [PRIVACY_AUDIT.md](PRIVACY_AUDIT.md) for baseline/source/dependency analysis and its limits. Local launcher-only app selection replaces broad installed-application enumeration. Optional downloadable emoji-font startup is disabled. Collaborator API requests use a constant User-Agent instead of an Android default that can include OS/model/build metadata. A rotation-safe disclosure now precedes user-started VPN capture, and status text distinguishes capture storage from forwarding. EN/UK privacy policy and in-app link added.

No analytics SDK, external OAST default, telemetry, new network permission or weakened TLS validation added. The direct startup-runtime1.1.1 declaration exposes the same previously transitive artifact to compile-time manifest verification, without changing the resolved runtime version. See THIRD_PARTY.md and the [new dependency graph](privacy/0.1.2-runtime-dependencies.txt).

## Verification

Initial full audit command (core/benchmark/app executed, OAST/server unchanged at that point):

```sh
./gradlew --console=plain --max-workers=2 :core:test :oast:test :collaborator-server:test :benchmark:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

A tool session was interrupted before a verified release result; the same command was resumed only after confirming no Gradle processes remained. It completed successfully. After the additional Collaborator User-Agent change, the final command also completed successfully:

```sh
./gradlew --console=plain --max-workers=2 :oast:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease :app:dependencies --configuration releaseRuntimeClasspath
```

Across this audit: core161, benchmark9, app36 and OAST3 tests executed successfully (209); unchanged collaborator-server6 results were UP-TO-DATE from their earlier passing run. Combined215 reports have no failures/errors/skips. Five new Robolectric privacy tests and one new OAST loopback HTTP test passed. Final lint:0 errors/fatal,12 warnings, no new baseline/blanket suppression. The existing optional Netty BlockHound R8 warning remains nonfatal. The extra JDK17 SecurityManager deprecation warning belongs to a test-only socket guard, not Android production code.

Early test compilation failed because transitive EmojiCompat/startup types were not on the public compile classpath; runtime checks now inspect merged provider metadata and use reflection for the existing optional class. A manifest MissingClass lint error was fixed by explicitly declaring the already-resolved startup-runtime artifact rather than suppressing the error. Final checks above passed after those fixes.

The merged release manifest has no EmojiCompatInitializer metadata and retains lifecycle/profile initializers. Release APK has only launcher queryIntentActivities for the catalog, no getInstalledApplications/getInstalledPackages reference. The DEX guard finds no candidate persistent identifier API, Settings.Secure getter, known identifier marker or tracking-SDK class family; input-event getDeviceId references remain. [Signed APK DEX report](privacy/0.1.2-dex.json). This is static analysis, not proof about all dynamic/user-controlled transmissions.

## Artifacts

APK and AAB signed with the existing developer key. APK signature verification and zipalign `-c -P16 4` passed. AAB jarsigner verification passed; expected self-signed/no timestamp warnings apply. APK and AAB DEX payloads match byte-for-byte. aapt confirmed package/version/min/target values above. QPACK static/Huffman tables, QUIC/Agent15 version resources, dependency notices and LGPL/GPL licenses retained; no native .so files. Packaged THIRD_PARTY.md matches the updated notice.

Certificate SHA256: `f5d9e47ab065f8ab8083d920c43ba8b149b52b9bdbe28e9d0cd0163762ea8878`.

APK SHA256: `132f37905741c217f6604ef38f6be4a0428e32180df9c18b1d2029096694f231`.

AAB SHA256: `85126c3e5f0a5922fd81965e78786e62ab50928a0213fdaea5ad2021991c23ef`.

No keys, passwords, captures, raw device logs or local SDK paths are release assets. Dependency evidence has local workspace paths redacted. Original0.1.1 artifacts are preserved.

## Limits and next steps

ADB returned no attached device; no emulator is installed. No fresh on-device packet capture, app upgrade, VPN/disclosure UI acceptance or real provider egress observation is claimed. Earlier Pixel7a results and video remain attributed to their original versions. The loopback User-Agent fixture verifies JVM HTTP requests, with Android default-header behavior supported by AOSP source; it does not replace a device capture.

The Console form and its previously linked privacy policy were not provided/accessed. No Play Console declaration or review submission was changed. Upload the new code3 AAB and correct policy URL before re-review; [technical response and next steps](PLAY_DATA_SAFETY_RESPONSE.md). If the warning remains, request the flagged destination/field/SDK attribution. These changes do not guarantee review approval or prove Google's exact internal detection cause.

## Final server integration recheck

After publishing the verified artifacts, `./gradlew --console=plain --max-workers=2 :collaborator-server:test` was executed against the updated OAST JAR and passed all6 tests (BUILD SUCCESSFUL). The UP-TO-DATE statement above records the earlier packaging phase. Across the complete audit, all215 tests have now been executed successfully; no product source or artifact bytes changed for this final check. The tagged source archive retains the packaging-time report.
