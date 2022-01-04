//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofPrimitiveArrayStripper](index.md)

# HprofPrimitiveArrayStripper

[jvm]\
class [HprofPrimitiveArrayStripper](index.md)

Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes, which can be useful to remove PII. Char arrays are handled slightly differently because 0 would be the null character so instead these become arrays of '?'.

## Constructors

| | |
|---|---|
| [HprofPrimitiveArrayStripper](-hprof-primitive-array-stripper.md) | [jvm]<br>fun [HprofPrimitiveArrayStripper](-hprof-primitive-array-stripper.md)() |

## Functions

| Name | Summary |
|---|---|
| [stripPrimitiveArrays](strip-primitive-arrays.md) | [jvm]<br>fun [stripPrimitiveArrays](strip-primitive-arrays.md)(inputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), outputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html) = File(       inputHprofFile.parent, inputHprofFile.name.replace(       ".hprof", "-stripped.hprof"     ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-stripped" })): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)<br>fun [stripPrimitiveArrays](strip-primitive-arrays.md)(hprofSourceProvider: [StreamingSourceProvider](../-streaming-source-provider/index.md), hprofSink: [BufferedSink](https://square.github.io/okio/2.x/okio/okio/-buffered-sink/index.html)) |
