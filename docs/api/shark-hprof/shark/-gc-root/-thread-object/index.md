//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[GcRoot](../index.md)/[ThreadObject](index.md)

# ThreadObject

[jvm]\
class [ThreadObject](index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](../index.md)

A thread.

Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c

## Constructors

| | |
|---|---|
| [ThreadObject](-thread-object.md) | [jvm]<br>fun [ThreadObject](-thread-object.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [id](id.md) | [jvm]<br>open override val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | [jvm]<br>val [stackTraceSerialNumber](stack-trace-serial-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [threadSerialNumber](thread-serial-number.md) | [jvm]<br>val [threadSerialNumber](thread-serial-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
