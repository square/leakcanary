//[leakcanary-android-core](../../../../../index.md)/[leakcanary](../../../index.md)/[EventListener](../../index.md)/[Event](../index.md)/[HeapDumpFailed](index.md)

# HeapDumpFailed

[androidJvm]\
class [HeapDumpFailed](index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), exception: [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html), willRetryLater: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) : [EventListener.Event](../index.md)

Sent from the "LeakCanary-Heap-Dump" HandlerThread.

## Constructors

| | |
|---|---|
| [HeapDumpFailed](-heap-dump-failed.md) | [androidJvm]<br>fun [HeapDumpFailed](-heap-dump-failed.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), exception: [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html), willRetryLater: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [exception](exception.md) | [androidJvm]<br>val [exception](exception.md): [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html) |
| [uniqueId](../unique-id.md) | [androidJvm]<br>val [uniqueId](../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](../-heap-dump/index.md) gets reset right before [HeapDump](../-heap-dump/index.md) is sent. |
| [willRetryLater](will-retry-later.md) | [androidJvm]<br>val [willRetryLater](will-retry-later.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
