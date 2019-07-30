[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [GcTrigger](./index.md)

# GcTrigger

`interface GcTrigger`

[GcTrigger](./index.md) is used to try triggering garbage collection and enqueuing [KeyedWeakReference](../-keyed-weak-reference/index.md) into
the associated [java.lang.ref.ReferenceQueue](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html). The default implementation [Default](-default/index.md) comes from
AOSP.

### Types

| Name | Summary |
|---|---|
| [Default](-default/index.md) | `object Default : `[`GcTrigger`](./index.md)<br>Default implementation of [GcTrigger](./index.md). |

### Functions

| Name | Summary |
|---|---|
| [runGc](run-gc.md) | `abstract fun runGc(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Attempts to run garbage collection. |

### Inheritors

| Name | Summary |
|---|---|
| [Default](-default/index.md) | `object Default : `[`GcTrigger`](./index.md)<br>Default implementation of [GcTrigger](./index.md). |
