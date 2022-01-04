//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HprofHeapGraph](../index.md)/[Companion](index.md)/[openHeapGraph](open-heap-graph.md)

# openHeapGraph

[jvm]\
fun [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html).[openHeapGraph](open-heap-graph.md)(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;HprofRecordTag&gt; = HprofIndex.defaultIndexedGcRootTags()): [CloseableHeapGraph](../../-closeable-heap-graph/index.md)

A facility for opening a [CloseableHeapGraph](../../-closeable-heap-graph/index.md) from a [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html). This first parses the file headers with HprofHeader.parseHeaderOf, then indexes the file content with [HprofIndex.indexRecordsOf](../../-hprof-index/-companion/index-records-of.md) and then opens a [CloseableHeapGraph](../../-closeable-heap-graph/index.md) from the index, which you are responsible for closing after using.

[jvm]\
fun DualSourceProvider.[openHeapGraph](open-heap-graph.md)(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;HprofRecordTag&gt; = HprofIndex.defaultIndexedGcRootTags()): [CloseableHeapGraph](../../-closeable-heap-graph/index.md)
