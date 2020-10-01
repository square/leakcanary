[shark-hprof](../../index.md) / [shark](../index.md) / [HprofHeader](index.md) / [parseHeaderOf](./parse-header-of.md)

# parseHeaderOf

`fun parseHeaderOf(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`): `[`HprofHeader`](index.md)

Reads the header of the provided [hprofFile](parse-header-of.md#shark.HprofHeader.Companion$parseHeaderOf(java.io.File)/hprofFile) and returns it as a [HprofHeader](index.md)

`fun parseHeaderOf(source: BufferedSource): `[`HprofHeader`](index.md)

Reads the header of the provided [source](parse-header-of.md#shark.HprofHeader.Companion$parseHeaderOf(okio.BufferedSource)/source) and returns it as a [HprofHeader](index.md).
This does not close the [source](parse-header-of.md#shark.HprofHeader.Companion$parseHeaderOf(okio.BufferedSource)/source).

