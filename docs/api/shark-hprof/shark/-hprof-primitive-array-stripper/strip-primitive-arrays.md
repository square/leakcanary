[shark-hprof](../../index.md) / [shark](../index.md) / [HprofPrimitiveArrayStripper](index.md) / [stripPrimitiveArrays](./strip-primitive-arrays.md)

# stripPrimitiveArrays

`fun stripPrimitiveArrays(inputHprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, outputHprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)` = File(
        inputHprofFile.parent, inputHprofFile.name.replace(
        ".hprof", "-stripped.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-stripped" })): `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)
`fun stripPrimitiveArrays(hprofSourceProvider: `[`StreamingSourceProvider`](../-streaming-source-provider/index.md)`, hprofSink: BufferedSink): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

**See Also**

[HprofPrimitiveArrayStripper](index.md)

