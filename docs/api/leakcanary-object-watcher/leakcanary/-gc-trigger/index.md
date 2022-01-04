//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[GcTrigger](index.md)

# GcTrigger

[jvm]\
fun interface [GcTrigger](index.md)

[GcTrigger](index.md) is used to try triggering garbage collection and enqueuing [KeyedWeakReference](../-keyed-weak-reference/index.md) into the associated [java.lang.ref.ReferenceQueue](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/ReferenceQueue.html). The default implementation [Default](-default/index.md) comes from AOSP.

## Types

| Name | Summary |
|---|---|
| [Default](-default/index.md) | [jvm]<br>object [Default](-default/index.md) : [GcTrigger](index.md)<br>Default implementation of [GcTrigger](index.md). |

## Functions

| Name | Summary |
|---|---|
| [runGc](run-gc.md) | [jvm]<br>abstract fun [runGc](run-gc.md)()<br>Attempts to run garbage collection. |

## Inheritors

| Name |
|---|
| [Default](-default/index.md) |
