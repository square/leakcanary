[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [LeakCanary](index.md) / [dumpHeap](./dump-heap.md)

# dumpHeap

`fun dumpHeap(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Immediately triggers a heap dump and analysis, if there is at least one retained instance
tracked by [AppWatcher.objectWatcher](#). If there are no retained instances then the heap will not
be dumped and a notification will be shown instead.

