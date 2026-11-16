# Third-party components

All Sprig application and server source is Java. Android's platform runtime and binary dependencies are separate components. There is no Kotlin compiler plugin, Kotlin source, Compose, NDK module or bundled native QUIC implementation in this project.

| Component | Version | License | Role |
| --- | --- | --- | --- |
| Netty HTTP/2 | 4.1.139.Final | Apache-2.0 | HPACK/Huffman implementation |
| Kwik Android source port (`quic`) | 0.12.2 | LGPL-3.0-or-later | Pure Java QUIC v1/v2, portable heap diagnostic hook |
| Agent15 | 4.0 | LGPL-3.0-or-later | Pure Java TLS 1.3 for QUIC |
| QPACK Android source port (`qpack`) | 2.1 | LGPL-3.0-or-later | HTTP/3 header codec |
| Bouncy Castle | 1.80.2 | MIT-style Bouncy Castle license | Local CA certificates |
| Gson | 2.13.2 | Apache-2.0 | Scanner structured JSON and OAST JSON boundary |
| RE2J | 1.8 | BSD-3-Clause | Bounded, non-backtracking Intruder grep/extract |
| Material Components Android | 1.13.0 | Apache-2.0 | Material 3 views |
| AndroidX | Versions resolved by Gradle | Apache-2.0 | Android application components |
| desugar_jdk_libs | 2.1.5 | See library notices | Java API compatibility on Android |

AndroidX/Material may contain Kotlin implementation internally; all authored code in this repository is Java. Tests use JUnit and Robolectric, neither is shipped in the APK.

## LGPL source and relinking

Unmodified source archives for the exact QUIC/TLS/QPACK dependency versions are supplied in `third-party/sources/`. The full LGPL and GPL texts are supplied as `third-party/LGPL-3.0.txt` and `third-party/GPL-3.0.txt`.

Regenerate the source copies with:

```sh
./gradlew :core:collectThirdPartySources
```

Source provenance:

- [Kwik source repository](https://github.com/ptrd/kwik), Maven `tech.kwik:kwik:0.12.2:sources`.
- [Agent15 source repository](https://github.com/ptrd/agent15), Maven `tech.kwik:agent15:4.0:sources`.
- [QPACK source repository](https://github.com/ptrd/qpack), Maven `tech.kwik:qpack:2.1:sources`.
- [Netty source repository](https://github.com/netty/netty), Apache-2.0.

Sprig compiles Kwik from the `quic` source module. The sole modified upstream Java file is `tech/kwik/core/util/MemoryWatcher.java`: on 2026-11-14 its optional JMX heap-warning logger was replaced with a documented no-op compatibility hook preserving the constructor and constants. Android has no ManagementFactory/JMX runtime, while the upstream server creates this hook unconditionally. This change adds no polling or monitor threads. The application heap/flow limits and QUIC receive-buffer budgets remain active. The original LGPL headers are retained, JPMS `module-info.java` is omitted, and the original version resource is unchanged. See `quic/README.md` for the exact source-JAR SHA-256 and reproduction steps.

To modify the QUIC library, edit/build the supplied `quic` module. Alternatively, build replacement sources into a JAR and replace `implementation project(':quic')` in `core/build.gradle` with `implementation files('/path/to/replacement.jar')`. QPACK is built from the `qpack` source module, with one bounded read loop replacing `InputStream.readNBytes` for older Android; see `qpack/UPSTREAM.md`. Agent15 remains an unmodified Maven dependency. Both can be replaced/rebuilt similarly. Rebuild with `./gradlew :app:assembleDebug` or configure your own release signing key. The complete application source and build scripts are supplied so the application can be rebuilt/relinked with modified libraries; no restriction on debugging such modifications is added by this project.

The `fixture-test-only.p12` file under server test resources contains a deliberately public local test key. It is never used by production services or packaged into the server distribution. Production TLS credentials come from the user's configured PKCS12 store.

RE2J 1.8 is included as an unmodified Maven dependency. Its complete license is supplied in `third-party/RE2J-LICENSE.txt` and the APK notices. Source and license: [RE2J 1.8](https://github.com/google/re2j/tree/re2j-1.8), [license](https://github.com/google/re2j/blob/re2j-1.8/LICENSE).

## Privacy audit 0.1.2 Preview

`androidx.startup:startup-runtime:1.1.1` is now declared directly because the source manifest overrides its provider metadata. This is the same Android-compatible Apache-2.0 artifact already resolved transitively in0.1.1; no runtime library version was upgraded or new SDK added. The explicit compile dependency makes manifest-class verification accurate. The packaged AndroidX notice already covers this component. The default EmojiCompatInitializer (emoji2:1.3.0) is removed from merged startup metadata, disabling automatic downloadable-font provider discovery/loading. System emoji fonts remain; newer emoji coverage on older Android may differ. Lifecycle/profile initializers remain enabled. [Android guidance](https://developer.android.com/develop/ui/views/text-and-emoji/emoji2).

The audited release dependency graph is recorded in docs/privacy/; no advertising, analytics, Firebase, attribution or crash-reporting SDK was found in that graph. Full findings and limits: [PRIVACY_AUDIT.md](PRIVACY_AUDIT.md). `tools/ReleasePrivacyAudit.java` is a JDK17 development utility and is not included in the Android APK.
