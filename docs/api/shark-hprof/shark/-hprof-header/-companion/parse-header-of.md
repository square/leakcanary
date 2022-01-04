//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[HprofHeader](../index.md)/[Companion](index.md)/[parseHeaderOf](parse-header-of.md)

# parseHeaderOf

[jvm]\
fun [parseHeaderOf](parse-header-of.md)(hprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)): [HprofHeader](../index.md)

Reads the header of the provided [hprofFile](parse-header-of.md) and returns it as a [HprofHeader](../index.md)

[jvm]\
fun [parseHeaderOf](parse-header-of.md)(source: [BufferedSource](https://square.github.io/okio/2.x/okio/okio/-buffered-source/index.html)): [HprofHeader](../index.md)

Reads the header of the provided [source](parse-header-of.md) and returns it as a [HprofHeader](../index.md). This does not close the [source](parse-header-of.md).
