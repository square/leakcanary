//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HprofHeapGraph](../index.md)/[Companion](index.md)

# Companion

[jvm]\
object [Companion](index.md)

## Functions

| Name | Summary |
|---|---|
| [openHeapGraph](open-heap-graph.md) | [jvm]<br>fun [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html).[openHeapGraph](open-heap-graph.md)(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;HprofRecordTag&gt; = HprofIndex.defaultIndexedGcRootTags()): [CloseableHeapGraph](../../-closeable-heap-graph/index.md)<br>A facility for opening a [CloseableHeapGraph](../../-closeable-heap-graph/index.md) from a [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html). This first parses the file headers with HprofHeader.parseHeaderOf, then indexes the file content with [HprofIndex.indexRecordsOf](../../-hprof-index/-companion/index-records-of.md) and then opens a [CloseableHeapGraph](../../-closeable-heap-graph/index.md) from the index, which you are responsible for closing after using.<br>[jvm]<br>fun DualSourceProvider.[openHeapGraph](open-heap-graph.md)(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;HprofRecordTag&gt; = HprofIndex.defaultIndexedGcRootTags()): [CloseableHeapGraph](../../-closeable-heap-graph/index.md) |

## Properties

| Name | Summary |
|---|---|
| [INTERNAL_LRU_CACHE_SIZE](-i-n-t-e-r-n-a-l_-l-r-u_-c-a-c-h-e_-s-i-z-e.md) | [jvm]<br>var [INTERNAL_LRU_CACHE_SIZE](-i-n-t-e-r-n-a-l_-l-r-u_-c-a-c-h-e_-s-i-z-e.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 3000<br>This is not a public API, it's only public so that we can evaluate the effectiveness of different cache size in tests in a different module. |
