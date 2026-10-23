# Native decoder licensing

The existing Java and JNI decoder wrapper sources are copied from AndroidX
Media3 1.9.1 and licensed under Apache License 2.0 (`ANDROIDX-LICENSE`).
The added `ApeExtractor.java` adapts the APE header/packet rules from FFmpeg
6.1.4 `libavformat/ape.c` and is LGPL 2.1 or later (`COPYING.LGPLv2.1`). It
streams through Media3's supplied data source and does not embed libavformat.

The native FFmpeg libraries are built from FFmpeg 6.1.4 with the LGPL-compatible
configuration in `scripts/build-ffmpeg-android.sh`. FFmpeg's
license text is included in `FFMPEG-LICENSE.md`. This module enables decoders
only; it does not enable encoders, network protocols, or GPL/nonfree libraries.

The generated `src/main/jniLibs` files are build outputs and are intentionally
not checked into source control by this task.

Sources: AndroidX Media3 tag `1.9.1`, commit
`01c37572827dc77543fe6d0deda3efe06ec95f61`,
https://github.com/androidx/media/tree/1.9.1/libraries/decoder_ffmpeg
and FFmpeg https://ffmpeg.org/releases/ffmpeg-6.1.4.tar.xz.

Local adaptation: Gradle integration, selected ABI/audio codec configuration,
16KB ELF alignment, and CMake `-idirafter` header lookup to avoid the generated
FFmpeg `version` file shadowing libc++ `<version>`. The experimental video
renderer is omitted. APE support adds the `ape` decoder to the original ten
selected decoders, MIME mapping, codec extradata/bit-depth/sample-rate/channel
initialization, and the Java extractor. Existing decoder initialization retains
its original behavior. No GPL/nonfree code, network protocols, demuxer library,
or encoders are enabled.
Full Apache 2.0 and LGPL 2.1 license texts are packaged in APK assets/licenses.
