//[leakcanary-android-core](../../../../index.md)/[leakcanary](../../index.md)/[LeakCanary](../index.md)/[Config](index.md)/[dumpHeap](dump-heap.md)

# dumpHeap

[androidJvm]\
val [dumpHeap](dump-heap.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true

Whether LeakCanary should dump the heap when enough retained instances are found. This needs to be true for LeakCanary to work, but sometimes you may want to temporarily disable LeakCanary (e.g. for a product demo).

Defaults to true.
