[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapInstance](index.md) / [instanceOf](./instance-of.md)

# instanceOf

`infix fun instanceOf(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if this is an instance of the class named [className](instance-of.md#shark.HeapObject.HeapInstance$instanceOf(kotlin.String)/className) or an instance of a
subclass of that class.

`infix fun instanceOf(expectedClass: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<*>): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)
`infix fun instanceOf(expectedClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if this is an instance of [expectedClass](instance-of.md#shark.HeapObject.HeapInstance$instanceOf(kotlin.reflect.KClass((kotlin.Any)))/expectedClass) or an instance of a subclass of that
class.

