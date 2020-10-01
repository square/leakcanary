[shark-android](../../index.md) / [shark](../index.md) / [AndroidReferenceMatchers](index.md) / [staticFieldLeak](./static-field-leak.md)

# staticFieldLeak

`@JvmStatic fun staticFieldLeak(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)` = "", patternApplies: `[`AndroidBuildMirror`](../-android-build-mirror/index.md)`.() -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = ALWAYS): LibraryLeakReferenceMatcher`

Creates a [LibraryLeakReferenceMatcher](#) that matches a [StaticFieldPattern](#).
[description](static-field-leak.md#shark.AndroidReferenceMatchers.Companion$staticFieldLeak(kotlin.String, kotlin.String, kotlin.String, kotlin.Function1((shark.AndroidBuildMirror, kotlin.Boolean)))/description) should convey what we know about this library leak.

