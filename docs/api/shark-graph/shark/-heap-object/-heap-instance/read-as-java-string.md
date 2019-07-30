[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapInstance](index.md) / [readAsJavaString](./read-as-java-string.md)

# readAsJavaString

`fun readAsJavaString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`

If this [HeapInstance](index.md) is an instance of the [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) class, returns a [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) instance
with content that matches the string in the heap dump. Otherwise returns null.

This may trigger IO reads.

