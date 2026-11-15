# Kwik 0.12.2 Android compatibility port

This module is derived from the exact Maven Central `tech.kwik:kwik:0.12.2:sources` artifact retained at `third-party/sources/kwik-0.12.2-sources.jar`.

Original source JAR SHA-256:

```
3333fdc3f4367b2a3c96f649b748be4b54f107a0fb245fd53a4745e95c18b050
```

All upstream Java files are copied with their LGPL notices. The sole Java modification is `tech/kwik/core/util/MemoryWatcher.java`, replaced on 2026-11-14 with a no-op implementation retaining its original public constructor and constants. The original implementation uses JMX/ManagementFactory, which Android does not supply, and the server constructor invokes it unconditionally. The replacement removes that optional logging mechanism and creates no watcher threads. Sprig's bounded flow/heap configuration and Kwik's transport receive-buffer limits remain active.

`module-info.java` is omitted for Android/non-JPMS use. `tech/kwik/core/version.properties` is copied to resources unchanged. Other Java and resource files are unchanged.

To reproduce: extract each `.java` from the original source JAR into `src/main/java`, excluding `module-info.java`; copy the version property to `src/main/resources`; then apply the documented `MemoryWatcher` replacement. Build with Java 17 and the dependencies pinned in `build.gradle`. The main project's HTTP/3 tests exercise this module through `core`.

Licensing: Kwik is LGPLv3-or-later. Full GPLv3 and LGPLv3 license texts and the original dependency sources are included under `third-party/`. This source module and its Android change are distributed under the upstream LGPLv3-or-later terms. The Sprig source project includes the code and build files needed to rebuild, replace the library, and re-sign the APK using your own key.

Upstream: [Kwik](https://github.com/ptrd/kwik), [exact 0.12.2 sources](https://repo.maven.apache.org/maven2/tech/kwik/kwik/0.12.2/kwik-0.12.2-sources.jar).
