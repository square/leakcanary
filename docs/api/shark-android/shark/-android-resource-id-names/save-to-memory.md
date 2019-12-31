[shark-android](../../index.md) / [shark](../index.md) / [AndroidResourceIdNames](index.md) / [saveToMemory](./save-to-memory.md)

# saveToMemory

`@Synchronized fun saveToMemory(getResourceTypeName: (`[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`) -> `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?, getResourceEntryName: (`[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`) -> `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

### Parameters

`getResourceTypeName` - a function that delegates to Android
Resources.getResourceTypeName but returns null when the name isn't found instead of
throwing an exception.

`getResourceEntryName` - a function that delegates to Android
Resources.getResourceEntryName but returns null when the name isn't found instead of
throwing an exception.