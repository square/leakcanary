//[shark-hprof](../../../index.md)/[shark](../index.md)/[ProguardMapping](index.md)

# ProguardMapping

[jvm]\
class [ProguardMapping](index.md)

## Functions

| Name | Summary |
|---|---|
| [addMapping](add-mapping.md) | [jvm]<br>fun [addMapping](add-mapping.md)(obfuscatedName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), clearName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html))<br>Adds entry to the obfuscatedToClearNamesMap map. |
| [deobfuscateClassName](deobfuscate-class-name.md) | [jvm]<br>fun [deobfuscateClassName](deobfuscate-class-name.md)(obfuscatedClassName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns deobfuscated class name or original string if there is no mapping for given obfuscated name. |
| [deobfuscateFieldName](deobfuscate-field-name.md) | [jvm]<br>fun [deobfuscateFieldName](deobfuscate-field-name.md)(obfuscatedClass: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), obfuscatedField: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns deobfuscated field name or original string if there is no mapping for given obfuscated name. |
