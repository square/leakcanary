//[leakcanary-android-core](../../../../../index.md)/[leakcanary](../../../index.md)/[EventListener](../../index.md)/[Event](../index.md)/[DumpingHeap](index.md)

# DumpingHeap

[androidJvm]\
class [DumpingHeap](index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [EventListener.Event](../index.md)

Sent from the "LeakCanary-Heap-Dump" HandlerThread.

## Constructors

| | |
|---|---|
| [DumpingHeap](-dumping-heap.md) | [androidJvm]<br>fun [DumpingHeap](-dumping-heap.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [uniqueId](../unique-id.md) | [androidJvm]<br>val [uniqueId](../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](../-heap-dump/index.md) gets reset right before [HeapDump](../-heap-dump/index.md) is sent. |
