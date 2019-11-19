[shark-android](../index.md) / [shark](./index.md)

## Package shark

### Types

| Name | Summary |
|---|---|
| [AndroidBuildMirror](-android-build-mirror/index.md) | `class AndroidBuildMirror`<br>Caches values from the android.os.Build class in the heap dump. Retrieve a cached instances via [fromHeapGraph](-android-build-mirror/from-heap-graph.md). |
| [AndroidMetadataExtractor](-android-metadata-extractor/index.md) | `object AndroidMetadataExtractor : MetadataExtractor` |
| [AndroidObjectInspectors](-android-object-inspectors/index.md) | `enum class AndroidObjectInspectors : ObjectInspector`<br>A set of default [ObjectInspector](#)s that knows about common AOSP and library classes. |
| [AndroidReferenceMatchers](-android-reference-matchers/index.md) | `enum class AndroidReferenceMatchers`<br>[AndroidReferenceMatchers](-android-reference-matchers/index.md) values add [ReferenceMatcher](#) instances to a global list via their [add](#) method. A [ReferenceMatcher](#) is either a [IgnoredReferenceMatcher](#) or a [LibraryLeakReferenceMatcher](#). |

### Functions

| Name | Summary |
|---|---|
| [unwrapActivityContext](unwrap-activity-context.md) | `fun HeapInstance.unwrapActivityContext(): HeapInstance?`<br>Recursively unwraps `this` [HeapInstance](#) as a ContextWrapper until an Activity is found in which case it is returned. Returns null if no activity was found. |
