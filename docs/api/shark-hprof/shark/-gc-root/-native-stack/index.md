//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[GcRoot](../index.md)/[NativeStack](index.md)

# NativeStack

[jvm]\
class [NativeStack](index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](../index.md)

Input or output parameters in native code

## Constructors

| | |
|---|---|
| [NativeStack](-native-stack.md) | [jvm]<br>fun [NativeStack](-native-stack.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [id](id.md) | [jvm]<br>open override val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
| [threadSerialNumber](thread-serial-number.md) | [jvm]<br>val [threadSerialNumber](thread-serial-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Corresponds to [ThreadObject.threadSerialNumber](../-thread-object/thread-serial-number.md) Note: the corresponding thread is sometimes not found, see: https://issuetracker.google.com/issues/122713143 |
