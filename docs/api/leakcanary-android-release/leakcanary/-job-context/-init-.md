[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [JobContext](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`JobContext(starter: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<*>)``JobContext(starter: `[`Class`](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html)`<*>? = null)`

In memory store that can be used to store objects in a given [HeapAnalysisJob](../-heap-analysis-job/index.md) instance.
This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access.

By convention, [starter](starter.md) should be the class that triggered the start of the job.

