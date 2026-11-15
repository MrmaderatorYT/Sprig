# Sprig 0.1 Preview release verification

Date: 2026-11-15. Application ID: `com.ccs.sprig`; versionName: `0.1 Preview`; versionCode: `1`; minSdk: 26; targetSdk: 36.

This is a preview signed with the developer's release certificate. It does not establish complete Burp Suite Pro parity or measured maximum performance. Historical stage 0.4 reports retain their original build versions and dates.

## Checks performed for this release

JDK 17 and Gradle Wrapper:

```sh
./gradlew --max-workers=2 :core:test :oast:test :collaborator-server:test :benchmark:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Result: BUILD SUCCESSFUL. Test XML reports: core 161, OAST 2, collaborator-server 6, benchmark 9, app 27; total 205, with zero failures, errors or skipped tests. Lint completed successfully; warnings are not evidence of device verification.

The signed APK passed `apksigner verify` (v2 and v3 signatures) and `zipalign -c -P 16 4`. `aapt dump badging` confirmed the identity and versions above. The packaged QPACK static/Huffman tables, QUIC/Agent15 version resources and open-source assets were inspected. No native `.so` libraries are packaged.

Certificate SHA-256:

```text
f5d9e47ab065f8ab8083d920c43ba8b149b52b9bdbe28e9d0cd0163762ea8878
```

APK SHA-256:

```text
f146d4ad9b579c497d94c8b10f5f3c84c76b4263d0fdbae950099bd7b93bf698
```

## Distribution and limits

Release assets include the signed APK, source archive with vendored sources/notices, checksums and historical stage04 fixture evidence. Unzip `stage04-validation-evidence.zip` into the repository root to restore `dist/stage04/` references used by existing reports. Evidence files are historical, not fresh device measurements of this signed APK.

The release APK was not installed or exercised on a device during this release pass. Existing device results are documented separately in VALIDATION.md and STAGE_0_4.md. See README and ROADMAP for protocol coverage and remaining limitations.

Old debug previews use a different certificate and some have versionCode 3. This versionCode 1 release is not an in-place update for those installations. Preserve needed local data before changing installations.

Git excludes signing keys, passwords, local SDK paths, captures and build outputs. The deliberately public test-only PKCS12 fixture is documented in THIRD_PARTY.md. The signing keystore is never a release asset.
