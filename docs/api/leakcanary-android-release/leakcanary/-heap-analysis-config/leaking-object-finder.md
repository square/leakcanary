[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisConfig](index.md) / [leakingObjectFinder](./leaking-object-finder.md)

# leakingObjectFinder

`val leakingObjectFinder: LeakingObjectFinder`

Finds the objects that are leaking, for which LeakCanary will compute leak traces.

Defaults to a [FilteringLeakingObjectFinder](#) that scans all objects in the heap dump and
delegates the decision to [AndroidObjectInspectors.appLeakingObjectFilters](#).

