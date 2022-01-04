//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofPrimitiveArrayStripper](index.md)/[stripPrimitiveArrays](strip-primitive-arrays.md)

# stripPrimitiveArrays

[jvm]\
fun [stripPrimitiveArrays](strip-primitive-arrays.md)(inputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), outputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html) = File(
      inputHprofFile.parent, inputHprofFile.name.replace(
      ".hprof", "-stripped.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-stripped" })): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)

fun [stripPrimitiveArrays](strip-primitive-arrays.md)(hprofSourceProvider: [StreamingSourceProvider](../-streaming-source-provider/index.md), hprofSink: [BufferedSink](https://square.github.io/okio/2.x/okio/okio/-buffered-sink/index.html))

## See also

jvm

| | |
|---|---|
| [shark.HprofPrimitiveArrayStripper](index.md) |  |
