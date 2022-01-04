//[leakcanary-android-core](../../../../../index.md)/[leakcanary](../../../index.md)/[EventListener](../../index.md)/[Event](../index.md)/[HeapDump](index.md)

# HeapDump

[androidJvm]\
class [HeapDump](index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), file: [File](https://developer.android.com/reference/kotlin/java/io/File.html), durationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), reason: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [EventListener.Event](../index.md)

Sent from the "LeakCanary-Heap-Dump" HandlerThread.

## Constructors

| | |
|---|---|
| [HeapDump](-heap-dump.md) | [androidJvm]<br>fun [HeapDump](-heap-dump.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), file: [File](https://developer.android.com/reference/kotlin/java/io/File.html), durationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), reason: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [durationMillis](duration-millis.md) | [androidJvm]<br>val [durationMillis](duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [file](file.md) | [androidJvm]<br>val [file](file.md): [File](https://developer.android.com/reference/kotlin/java/io/File.html) |
| [reason](reason.md) | [androidJvm]<br>val [reason](reason.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [uniqueId](../unique-id.md) | [androidJvm]<br>val [uniqueId](../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](index.md) gets reset right before [HeapDump](index.md) is sent. |
