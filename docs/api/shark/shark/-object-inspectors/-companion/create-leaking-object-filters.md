//[shark](../../../../index.md)/[shark](../../index.md)/[ObjectInspectors](../index.md)/[Companion](index.md)/[createLeakingObjectFilters](create-leaking-object-filters.md)

# createLeakingObjectFilters

[jvm]\
fun [createLeakingObjectFilters](create-leaking-object-filters.md)(inspectors: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[ObjectInspectors](../index.md)&gt;): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[FilteringLeakingObjectFinder.LeakingObjectFilter](../../-filtering-leaking-object-finder/-leaking-object-filter/index.md)&gt;

Creates a list of [LeakingObjectFilter](../../-filtering-leaking-object-finder/-leaking-object-filter/index.md) based on the passed in [ObjectInspectors](../index.md).
