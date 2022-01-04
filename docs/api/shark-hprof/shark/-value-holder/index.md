//[shark-hprof](../../../index.md)/[shark](../index.md)/[ValueHolder](index.md)

# ValueHolder

[jvm]\
sealed class [ValueHolder](index.md)

A value in the heap dump, which can be a [ReferenceHolder](-reference-holder/index.md) or a primitive type.

## Types

| Name | Summary |
|---|---|
| [BooleanHolder](-boolean-holder/index.md) | [jvm]<br>data class [BooleanHolder](-boolean-holder/index.md)(value: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) : [ValueHolder](index.md) |
| [ByteHolder](-byte-holder/index.md) | [jvm]<br>data class [ByteHolder](-byte-holder/index.md)(value: [Byte](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html)) : [ValueHolder](index.md) |
| [CharHolder](-char-holder/index.md) | [jvm]<br>data class [CharHolder](-char-holder/index.md)(value: [Char](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html)) : [ValueHolder](index.md) |
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [DoubleHolder](-double-holder/index.md) | [jvm]<br>data class [DoubleHolder](-double-holder/index.md)(value: [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html)) : [ValueHolder](index.md) |
| [FloatHolder](-float-holder/index.md) | [jvm]<br>data class [FloatHolder](-float-holder/index.md)(value: [Float](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html)) : [ValueHolder](index.md) |
| [IntHolder](-int-holder/index.md) | [jvm]<br>data class [IntHolder](-int-holder/index.md)(value: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [ValueHolder](index.md) |
| [LongHolder](-long-holder/index.md) | [jvm]<br>data class [LongHolder](-long-holder/index.md)(value: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [ValueHolder](index.md) |
| [ReferenceHolder](-reference-holder/index.md) | [jvm]<br>data class [ReferenceHolder](-reference-holder/index.md)(value: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [ValueHolder](index.md) |
| [ShortHolder](-short-holder/index.md) | [jvm]<br>data class [ShortHolder](-short-holder/index.md)(value: [Short](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html)) : [ValueHolder](index.md) |

## Inheritors

| Name |
|---|
| [ReferenceHolder](-reference-holder/index.md) |
| [BooleanHolder](-boolean-holder/index.md) |
| [CharHolder](-char-holder/index.md) |
| [FloatHolder](-float-holder/index.md) |
| [DoubleHolder](-double-holder/index.md) |
| [ByteHolder](-byte-holder/index.md) |
| [ShortHolder](-short-holder/index.md) |
| [IntHolder](-int-holder/index.md) |
| [LongHolder](-long-holder/index.md) |
