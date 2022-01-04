//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HeapObject](../index.md)/[HeapInstance](index.md)/[instanceOf](instance-of.md)

# instanceOf

[jvm]\
infix fun [instanceOf](instance-of.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if this is an instance of the class named [className](instance-of.md) or an instance of a subclass of that class.

[jvm]\
infix fun [instanceOf](instance-of.md)(expectedClass: [KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;*&gt;): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

infix fun [instanceOf](instance-of.md)(expectedClass: [HeapObject.HeapClass](../-heap-class/index.md)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if this is an instance of [expectedClass](instance-of.md) or an instance of a subclass of that class.
