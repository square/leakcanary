[shark-graph](../../index.md) / [shark](../index.md) / [HeapValue](index.md) / [readAsJavaString](./read-as-java-string.md)

# readAsJavaString

`fun readAsJavaString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`

If this [HeapValue](index.md) if it represents a non null object reference to an instance of the
[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) class that exists in the heap dump, returns a [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) instance with content that
matches the string in the heap dump. Otherwise returns null.

This may trigger IO reads.

