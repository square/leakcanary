[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [dumpHeap](./dump-heap.md)

# dumpHeap

`val dumpHeap: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Whether LeakCanary should dump the heap when enough retained instances are found. This needs
to be true for LeakCanary to work, but sometimes you may want to temporarily disable
LeakCanary (e.g. for a product demo).

Defaults to true.

