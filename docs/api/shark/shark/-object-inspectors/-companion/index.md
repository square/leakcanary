//[shark](../../../../index.md)/[shark](../../index.md)/[ObjectInspectors](../index.md)/[Companion](index.md)

# Companion

[jvm]\
object [Companion](index.md)

## Functions

| Name | Summary |
|---|---|
| [createLeakingObjectFilters](create-leaking-object-filters.md) | [jvm]<br>fun [createLeakingObjectFilters](create-leaking-object-filters.md)(inspectors: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[ObjectInspectors](../index.md)&gt;): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[FilteringLeakingObjectFinder.LeakingObjectFilter](../../-filtering-leaking-object-finder/-leaking-object-filter/index.md)&gt;<br>Creates a list of [LeakingObjectFilter](../../-filtering-leaking-object-finder/-leaking-object-filter/index.md) based on the passed in [ObjectInspectors](../index.md). |

## Properties

| Name | Summary |
|---|---|
| [jdkDefaults](jdk-defaults.md) | [jvm]<br>val [jdkDefaults](jdk-defaults.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ObjectInspector](../../-object-inspector/index.md)&gt; |
| [jdkLeakingObjectFilters](jdk-leaking-object-filters.md) | [jvm]<br>val [jdkLeakingObjectFilters](jdk-leaking-object-filters.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[FilteringLeakingObjectFinder.LeakingObjectFilter](../../-filtering-leaking-object-finder/-leaking-object-filter/index.md)&gt;<br>Returns a list of [LeakingObjectFilter](../../-filtering-leaking-object-finder/-leaking-object-filter/index.md) suitable for common JDK projects. |
