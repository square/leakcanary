//[shark-android](../../../../index.md)/[shark](../../index.md)/[AndroidReferenceMatchers](../index.md)/[Companion](index.md)/[staticFieldLeak](static-field-leak.md)

# staticFieldLeak

[jvm]\

@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)

fun [staticFieldLeak](static-field-leak.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = "", patternApplies: [AndroidBuildMirror](../../-android-build-mirror/index.md).() -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = ALWAYS): LibraryLeakReferenceMatcher

Creates a LibraryLeakReferenceMatcher that matches a StaticFieldPattern. [description](static-field-leak.md) should convey what we know about this library leak.
