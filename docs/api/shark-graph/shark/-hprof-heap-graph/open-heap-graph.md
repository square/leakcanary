[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](index.md) / [openHeapGraph](./open-heap-graph.md)

# openHeapGraph

`fun `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`.openHeapGraph(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<HprofRecordTag> = HprofIndex.defaultIndexedGcRootTags()): `[`CloseableHeapGraph`](../-closeable-heap-graph.md)

A facility for opening a [CloseableHeapGraph](../-closeable-heap-graph.md) from a [File](https://docs.oracle.com/javase/6/docs/api/java/io/File.html).
This first parses the file headers with [HprofHeader.parseHeaderOf](#), then indexes the file content
with [HprofIndex.indexRecordsOf](../-hprof-index/index-records-of.md) and then opens a [CloseableHeapGraph](../-closeable-heap-graph.md) from the index, which
you are responsible for closing after using.

`fun DualSourceProvider.openHeapGraph(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<HprofRecordTag> = HprofIndex.defaultIndexedGcRootTags()): `[`CloseableHeapGraph`](../-closeable-heap-graph.md)