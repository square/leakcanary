//[shark-android](../../../../index.md)/[shark](../../index.md)/[AndroidReferenceMatchers](../index.md)/[Companion](index.md)

# Companion

[jvm]\
object [Companion](index.md)

## Functions

| Name | Summary |
|---|---|
| [buildKnownReferences](build-known-references.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>fun [buildKnownReferences](build-known-references.md)(referenceMatchers: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[AndroidReferenceMatchers](../index.md)&gt;): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;<br>Builds a list of ReferenceMatcher from the [referenceMatchers](build-known-references.md) set of [AndroidReferenceMatchers](../index.md). |
| [ignoredInstanceField](ignored-instance-field.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>fun [ignoredInstanceField](ignored-instance-field.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): IgnoredReferenceMatcher<br>Creates a IgnoredReferenceMatcher that matches a InstanceFieldPattern. |
| [ignoredJavaLocal](ignored-java-local.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>fun [ignoredJavaLocal](ignored-java-local.md)(threadName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): IgnoredReferenceMatcher<br>Creates a IgnoredReferenceMatcher that matches a JavaLocalPattern. |
| [instanceFieldLeak](instance-field-leak.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>fun [instanceFieldLeak](instance-field-leak.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = "", patternApplies: [AndroidBuildMirror](../../-android-build-mirror/index.md).() -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = ALWAYS): LibraryLeakReferenceMatcher<br>Creates a LibraryLeakReferenceMatcher that matches a InstanceFieldPattern. [description](instance-field-leak.md) should convey what we know about this library leak. |
| [nativeGlobalVariableLeak](native-global-variable-leak.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>fun [nativeGlobalVariableLeak](native-global-variable-leak.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = "", patternApplies: [AndroidBuildMirror](../../-android-build-mirror/index.md).() -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = ALWAYS): LibraryLeakReferenceMatcher |
| [staticFieldLeak](static-field-leak.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>fun [staticFieldLeak](static-field-leak.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = "", patternApplies: [AndroidBuildMirror](../../-android-build-mirror/index.md).() -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = ALWAYS): LibraryLeakReferenceMatcher<br>Creates a LibraryLeakReferenceMatcher that matches a StaticFieldPattern. [description](static-field-leak.md) should convey what we know about this library leak. |

## Properties

| Name | Summary |
|---|---|
| [appDefaults](app-defaults.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>val [appDefaults](app-defaults.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt; |
| [HUAWEI](-h-u-a-w-e-i.md) | [jvm]<br>const val [HUAWEI](-h-u-a-w-e-i.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ignoredReferencesOnly](ignored-references-only.md) | [jvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>val [ignoredReferencesOnly](ignored-references-only.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;<br>Returns a list of ReferenceMatcher that only contains IgnoredReferenceMatcher and no LibraryLeakReferenceMatcher. |
| [LENOVO](-l-e-n-o-v-o.md) | [jvm]<br>const val [LENOVO](-l-e-n-o-v-o.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [LG](-l-g.md) | [jvm]<br>const val [LG](-l-g.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [MEIZU](-m-e-i-z-u.md) | [jvm]<br>const val [MEIZU](-m-e-i-z-u.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [MOTOROLA](-m-o-t-o-r-o-l-a.md) | [jvm]<br>const val [MOTOROLA](-m-o-t-o-r-o-l-a.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [NVIDIA](-n-v-i-d-i-a.md) | [jvm]<br>const val [NVIDIA](-n-v-i-d-i-a.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ONE_PLUS](-o-n-e_-p-l-u-s.md) | [jvm]<br>const val [ONE_PLUS](-o-n-e_-p-l-u-s.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [RAZER](-r-a-z-e-r.md) | [jvm]<br>const val [RAZER](-r-a-z-e-r.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [SAMSUNG](-s-a-m-s-u-n-g.md) | [jvm]<br>const val [SAMSUNG](-s-a-m-s-u-n-g.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [SHARP](-s-h-a-r-p.md) | [jvm]<br>const val [SHARP](-s-h-a-r-p.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [VIVO](-v-i-v-o.md) | [jvm]<br>const val [VIVO](-v-i-v-o.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
