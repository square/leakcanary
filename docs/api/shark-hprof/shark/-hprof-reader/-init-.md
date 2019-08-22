[shark-hprof](../../index.md) / [shark](../index.md) / [HprofReader](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`HprofReader(source: BufferedSource, identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, startPosition: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = 0L)`

Reads hprof content from an Okio [BufferedSource](#).

Not thread safe, should be used from a single thread.

Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html#mozTocId848088

The Android Hprof format differs in some ways from that reference. This parser implementation
is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev/perflib/src/main/java/com/android/tools/perflib

