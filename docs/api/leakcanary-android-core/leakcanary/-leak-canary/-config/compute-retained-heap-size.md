[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [computeRetainedHeapSize](./compute-retained-heap-size.md)

# computeRetainedHeapSize

`val computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Whether to compute the retained heap size, which is the total number of bytes in memory that
would be reclaimed if the detected leaks didn't happen. This includes native memory
associated to Java objects (e.g. Android bitmaps).

Computing the retained heap size can slow down the analysis because it requires navigating
from GC roots through the entire object graph, whereas [shark.HeapAnalyzer](#) would otherwise
stop as soon as all leaking instances are found.

Defaults to true.

