//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[JobContext](index.md)

# JobContext

[androidJvm]\
class [JobContext](index.md)(starter: [Class](https://developer.android.com/reference/kotlin/java/lang/Class.html)&lt;*&gt;?)

In memory store that can be used to store objects in a given [HeapAnalysisJob](../-heap-analysis-job/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access.

By convention, [starter](starter.md) should be the class that triggered the start of the job.

## Constructors

| | |
|---|---|
| [JobContext](-job-context.md) | [androidJvm]<br>fun [JobContext](-job-context.md)(starter: [KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;*&gt;) |
| [JobContext](-job-context.md) | [androidJvm]<br>fun [JobContext](-job-context.md)(starter: [Class](https://developer.android.com/reference/kotlin/java/lang/Class.html)&lt;*&gt;? = null) |

## Functions

| Name | Summary |
|---|---|
| [contains](contains.md) | [androidJvm]<br>operator fun [contains](contains.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [get](get.md) | [androidJvm]<br>operator fun &lt;[T](get.md)&gt; [get](get.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [T](get.md)? |
| [getOrPut](get-or-put.md) | [androidJvm]<br>fun &lt;[T](get-or-put.md)&gt; [getOrPut](get-or-put.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), defaultValue: () -&gt; [T](get-or-put.md)): [T](get-or-put.md) |
| [minusAssign](minus-assign.md) | [androidJvm]<br>operator fun [minusAssign](minus-assign.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |
| [set](set.md) | [androidJvm]<br>operator fun &lt;[T](set.md)&gt; [set](set.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), value: [T](set.md)) |

## Properties

| Name | Summary |
|---|---|
| [starter](starter.md) | [androidJvm]<br>val [starter](starter.md): [Class](https://developer.android.com/reference/kotlin/java/lang/Class.html)&lt;*&gt;? = null |
