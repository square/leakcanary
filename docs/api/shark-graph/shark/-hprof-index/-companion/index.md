//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HprofIndex](../index.md)/[Companion](index.md)

# Companion

[jvm]\
object [Companion](index.md)

## Functions

| Name | Summary |
|---|---|
| [defaultIndexedGcRootTags](default-indexed-gc-root-tags.md) | [jvm]<br>fun [defaultIndexedGcRootTags](default-indexed-gc-root-tags.md)(): [EnumSet](https://docs.oracle.com/javase/8/docs/api/java/util/EnumSet.html)&lt;HprofRecordTag&gt; |
| [indexRecordsOf](index-records-of.md) | [jvm]<br>fun [indexRecordsOf](index-records-of.md)(hprofSourceProvider: DualSourceProvider, hprofHeader: HprofHeader, proguardMapping: ProguardMapping? = null, indexedGcRootTags: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;HprofRecordTag&gt; = defaultIndexedGcRootTags()): [HprofIndex](../index.md)<br>Creates an in memory index of an hprof source provided by [hprofSourceProvider](index-records-of.md). |
