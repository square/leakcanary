[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [referenceMatchers](./reference-matchers.md)

# referenceMatchers

`val referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>`

Known patterns of references in the heap, added here either to ignore them
([IgnoredReferenceMatcher](#)) or to mark them as library leaks ([LibraryLeakReferenceMatcher](#)).

When adding your own custom [LibraryLeakReferenceMatcher](#) instances, you'll most
likely want to set [LibraryLeakReferenceMatcher.patternApplies](#) with a filter that checks
for the Android OS version and manufacturer. The build information can be obtained by calling
[shark.AndroidBuildMirror.fromHeapGraph](#).

Defaults to [AndroidReferenceMatchers.appDefaults](#)

