//[shark-android](../../index.md)/[shark](index.md)

# Package shark

## Types

| Name | Summary |
|---|---|
| [AndroidBuildMirror](-android-build-mirror/index.md) | [jvm]<br>class [AndroidBuildMirror](-android-build-mirror/index.md)(manufacturer: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), sdkInt: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html))<br>Caches values from the android.os.Build class in the heap dump. Retrieve a cached instances via [fromHeapGraph](-android-build-mirror/-companion/from-heap-graph.md). |
| [AndroidMetadataExtractor](-android-metadata-extractor/index.md) | [jvm]<br>object [AndroidMetadataExtractor](-android-metadata-extractor/index.md) : MetadataExtractor |
| [AndroidObjectInspectors](-android-object-inspectors/index.md) | [jvm]<br>enum [AndroidObjectInspectors](-android-object-inspectors/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[AndroidObjectInspectors](-android-object-inspectors/index.md)&gt; , ObjectInspector<br>A set of default ObjectInspectors that knows about common AOSP and library classes. |
| [AndroidReferenceMatchers](-android-reference-matchers/index.md) | [jvm]<br>enum [AndroidReferenceMatchers](-android-reference-matchers/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[AndroidReferenceMatchers](-android-reference-matchers/index.md)&gt; <br>[AndroidReferenceMatchers](-android-reference-matchers/index.md) values add ReferenceMatcher instances to a global list via their add method. A ReferenceMatcher is either a IgnoredReferenceMatcher or a LibraryLeakReferenceMatcher. |
| [AndroidResourceIdNames](-android-resource-id-names/index.md) | [jvm]<br>class [AndroidResourceIdNames](-android-resource-id-names/index.md) |
| [AndroidServices](-android-services/index.md) | [jvm]<br>object [AndroidServices](-android-services/index.md) |
