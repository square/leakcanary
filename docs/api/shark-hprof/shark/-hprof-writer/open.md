[shark-hprof](../../index.md) / [shark](../index.md) / [HprofWriter](index.md) / [open](./open.md)

# open

`fun open(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 4, hprofVersion: `[`Hprof.HprofVersion`](../-hprof/-hprof-version/index.md)` = HprofVersion.ANDROID): `[`HprofWriter`](index.md)

Opens a new file for writing hprof records. Don't forget to call [close](close.md) once done.

