//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HprofIndex](../index.md)/[Companion](index.md)/[indexRecordsOf](index-records-of.md)

# indexRecordsOf

[jvm]\
fun [indexRecordsOf](index-records-of.md)(hprofSourceProvider: DualSourceProvider, hprofHeader: HprofHeader, proguardMapping: ProguardMapping? = null, indexedGcRootTags: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;HprofRecordTag&gt; = defaultIndexedGcRootTags()): [HprofIndex](../index.md)

Creates an in memory index of an hprof source provided by [hprofSourceProvider](index-records-of.md).
