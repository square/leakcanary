[shark-hprof](../../index.md) / [shark](../index.md) / [ProguardMapping](./index.md)

# ProguardMapping

`class ProguardMapping`

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ProguardMapping()` |

### Functions

| Name | Summary |
|---|---|
| [addMapping](add-mapping.md) | `fun addMapping(obfuscatedName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, clearName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Adds entry to the obfuscatedToClearNamesMap map. |
| [deobfuscateClassName](deobfuscate-class-name.md) | `fun deobfuscateClassName(obfuscatedClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns deobfuscated class name or original string if there is no mapping for given obfuscated name. |
| [deobfuscateFieldName](deobfuscate-field-name.md) | `fun deobfuscateFieldName(obfuscatedClass: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, obfuscatedField: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns deobfuscated field name or original string if there is no mapping for given obfuscated name. |
