//[shark-android](../../../../index.md)/[shark](../../index.md)/[AndroidObjectInspectors](../index.md)/[Companion](index.md)

# Companion

[jvm]\
object [Companion](index.md)

## Functions

| Name | Summary |
|---|---|
| [createLeakingObjectFilters](create-leaking-object-filters.md) | [jvm]<br>fun [createLeakingObjectFilters](create-leaking-object-filters.md)(inspectors: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[AndroidObjectInspectors](../index.md)&gt;): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;FilteringLeakingObjectFinder.LeakingObjectFilter&gt;<br>Creates a list of LeakingObjectFilter based on the passed in [AndroidObjectInspectors](../index.md). |

## Properties

| Name | Summary |
|---|---|
| [appDefaults](app-defaults.md) | [jvm]<br>val [appDefaults](app-defaults.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt; |
| [appLeakingObjectFilters](app-leaking-object-filters.md) | [jvm]<br>val [appLeakingObjectFilters](app-leaking-object-filters.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;FilteringLeakingObjectFinder.LeakingObjectFilter&gt;<br>Returns a list of LeakingObjectFilter suitable for apps. |
