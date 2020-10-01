[shark-graph](../../index.md) / [shark](../index.md) / [HprofIndex](./index.md)

# HprofIndex

`class HprofIndex`

An index on a Hprof file. See [openHeapGraph](open-heap-graph.md).

### Functions

| Name | Summary |
|---|---|
| [openHeapGraph](open-heap-graph.md) | `fun openHeapGraph(): `[`CloseableHeapGraph`](../-closeable-heap-graph.md)<br>Opens a [CloseableHeapGraph](../-closeable-heap-graph.md) which you can use to navigate the indexed hprof and then close. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [defaultIndexedGcRootTags](default-indexed-gc-root-tags.md) | `fun defaultIndexedGcRootTags(): `[`EnumSet`](https://docs.oracle.com/javase/6/docs/api/java/util/EnumSet.html)`<HprofRecordTag!>!` |
| [indexRecordsOf](index-records-of.md) | `fun indexRecordsOf(hprofSourceProvider: DualSourceProvider, hprofHeader: HprofHeader, proguardMapping: ProguardMapping? = null, indexedGcRootTags: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<HprofRecordTag> = defaultIndexedGcRootTags()): `[`HprofIndex`](./index.md)<br>Creates an in memory index of an hprof source provided by [hprofSourceProvider](index-records-of.md#shark.HprofIndex.Companion$indexRecordsOf(shark.DualSourceProvider, shark.HprofHeader, shark.ProguardMapping, kotlin.collections.Set((shark.HprofRecordTag)))/hprofSourceProvider). |
