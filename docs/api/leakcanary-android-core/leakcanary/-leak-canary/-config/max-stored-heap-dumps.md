[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [maxStoredHeapDumps](./max-stored-heap-dumps.md)

# maxStoredHeapDumps

`val maxStoredHeapDumps: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

How many heap dumps are kept on the Android device for this app package. When this threshold
is reached LeakCanary deletes the older heap dumps. As several heap dumps may be enqueued
you should avoid going down to 1 or 2.

Defaults to 7.

