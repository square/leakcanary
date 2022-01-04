//[shark-android](../../../../index.md)/[shark](../../index.md)/[AndroidResourceIdNames](../index.md)/[Companion](index.md)/[saveToMemory](save-to-memory.md)

# saveToMemory

[jvm]\

@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)

fun [saveToMemory](save-to-memory.md)(getResourceTypeName: ([Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) -&gt; [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)?, getResourceEntryName: ([Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) -&gt; [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)?)

## Parameters

jvm

| | |
|---|---|
| getResourceTypeName | a function that delegates to Android Resources.getResourceTypeName but returns null when the name isn't found instead of throwing an exception. |
| getResourceEntryName | a function that delegates to Android Resources.getResourceEntryName but returns null when the name isn't found instead of throwing an exception. |
