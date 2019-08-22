[shark-hprof](../../index.md) / [shark](../index.md) / [PrimitiveType](./index.md)

# PrimitiveType

`enum class PrimitiveType`

A primitive type in the prof.

### Enum Values

| Name | Summary |
|---|---|
| [BOOLEAN](-b-o-o-l-e-a-n.md) |  |
| [CHAR](-c-h-a-r.md) |  |
| [FLOAT](-f-l-o-a-t.md) |  |
| [DOUBLE](-d-o-u-b-l-e.md) |  |
| [BYTE](-b-y-t-e.md) |  |
| [SHORT](-s-h-o-r-t.md) |  |
| [INT](-i-n-t.md) |  |
| [LONG](-l-o-n-g.md) |  |

### Properties

| Name | Summary |
|---|---|
| [byteSize](byte-size.md) | `val byteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The size in bytes for each value of that type. |
| [hprofType](hprof-type.md) | `val hprofType: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The hprof defined "basic type". |

### Companion Object Properties

| Name | Summary |
|---|---|
| [byteSizeByHprofType](byte-size-by-hprof-type.md) | `val byteSizeByHprofType: `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`>` |
| [primitiveTypeByHprofType](primitive-type-by-hprof-type.md) | `val primitiveTypeByHprofType: `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, `[`PrimitiveType`](./index.md)`>` |
| [REFERENCE_HPROF_TYPE](-r-e-f-e-r-e-n-c-e_-h-p-r-o-f_-t-y-p-e.md) | `const val REFERENCE_HPROF_TYPE: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The hprof defined "basic type" for references. |
