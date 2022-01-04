//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[GcRoot](../index.md)/[JniLocal](index.md)

# JniLocal

[jvm]\
class [JniLocal](index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), frameNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](../index.md)

A local variable in native code.

## Constructors

| | |
|---|---|
| [JniLocal](-jni-local.md) | [jvm]<br>fun [JniLocal](-jni-local.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), frameNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [frameNumber](frame-number.md) | [jvm]<br>val [frameNumber](frame-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>frame number in stack trace (-1 for empty) |
| [id](id.md) | [jvm]<br>open override val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
| [threadSerialNumber](thread-serial-number.md) | [jvm]<br>val [threadSerialNumber](thread-serial-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Corresponds to [ThreadObject.threadSerialNumber](../-thread-object/thread-serial-number.md) |
