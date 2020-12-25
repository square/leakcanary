[leakcanary-android-core](../index.md) / [leakcanary](index.md) / [&lt;no name provided&gt;](./-no name provided-.md)

# &lt;no name provided&gt;

`fun <no name provided>(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Listener set in [LeakCanary.Config](-leak-canary/-config/index.md) and called by LeakCanary on a background thread when the
heap analysis is complete.

This is a functional interface with which you can create a [OnHeapAnalyzedListener](-on-heap-analyzed-listener/index.md) from a lambda.

Usage:

``` kotlin
val listener = OnHeapAnalyzedListener { heapAnalysis ->
  process(heapAnalysis)
}
```

