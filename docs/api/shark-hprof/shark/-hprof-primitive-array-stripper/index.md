[shark-hprof](../../index.md) / [shark](../index.md) / [HprofPrimitiveArrayStripper](./index.md)

# HprofPrimitiveArrayStripper

`class HprofPrimitiveArrayStripper`

Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes,
which can be useful to remove PII. Char arrays are handled slightly differently because 0 would
be the null character so instead these become arrays of '?'.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HprofPrimitiveArrayStripper()`<br>Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes, which can be useful to remove PII. Char arrays are handled slightly differently because 0 would be the null character so instead these become arrays of '?'. |

### Functions

| Name | Summary |
|---|---|
| [stripPrimitiveArrays](strip-primitive-arrays.md) | `fun stripPrimitiveArrays(inputHprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, outputHprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)` = File(
        inputHprofFile.parent, inputHprofFile.name.replace(
        ".hprof", "-stripped.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-stripped" })): `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)<br>`fun stripPrimitiveArrays(hprofSourceProvider: `[`StreamingSourceProvider`](../-streaming-source-provider/index.md)`, hprofSink: BufferedSink): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
