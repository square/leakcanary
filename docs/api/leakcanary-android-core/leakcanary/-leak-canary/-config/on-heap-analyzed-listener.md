[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [onHeapAnalyzedListener](./on-heap-analyzed-listener.md)

# onHeapAnalyzedListener

`val onHeapAnalyzedListener: `[`OnHeapAnalyzedListener`](../../-on-heap-analyzed-listener/index.md)

Called on a background thread when the heap analysis is complete.
If you want leaks to be added to the activity that lists leaks, make sure to delegate
calls to a [DefaultOnHeapAnalyzedListener](../../-default-on-heap-analyzed-listener/index.md).

Defaults to [DefaultOnHeapAnalyzedListener](../../-default-on-heap-analyzed-listener/index.md)

