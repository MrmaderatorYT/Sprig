# QPACK Android compatibility port

Source: `tech.kwik:qpack:2.1:sources`, preserved in `third-party/sources/qpack-2.1-sources.jar`. License: LGPL-3.0-or-later; original file headers are preserved, full texts are in `third-party/`.

Sprig compatibility change dated 2026-11-14. One change in `tech/kwik/qpack/impl/DecoderImpl.java`: replace `InputStream.readNBytes(byte[], int, int)` with an equivalent bounded read loop. The newer JDK method is unavailable on older Android releases and is not retargeted by the configured desugaring library. No QPACK table, Huffman or framing algorithms are changed.

The complete modified source and build file are included for rebuilding/relinking. Core HTTP/3 codec tests exercise this module directly.
