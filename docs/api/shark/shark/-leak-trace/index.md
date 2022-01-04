//[shark](../../../index.md)/[shark](../index.md)/[LeakTrace](index.md)

# LeakTrace

[jvm]\
data class [LeakTrace](index.md)(gcRootType: [LeakTrace.GcRootType](-gc-root-type/index.md), referencePath: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTraceReference](../-leak-trace-reference/index.md)&gt;, leakingObject: [LeakTraceObject](../-leak-trace-object/index.md)) : [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)

The best strong reference path from a GC root to the leaking object. "Best" here means the shortest prioritized path. A large number of distinct paths can generally be found leading to a leaking object. Shark prioritizes paths that don't go through known [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) (because those are known to create leaks so it's more interesting to find other paths causing leaks), then it prioritize paths that don't go through java local gc roots (because those are harder to reason about). Taking those priorities into account, finding the shortest path means there are less [LeakTraceReference](../-leak-trace-reference/index.md) that can be suspected to cause the leak.

## Constructors

| | |
|---|---|
| [LeakTrace](-leak-trace.md) | [jvm]<br>fun [LeakTrace](-leak-trace.md)(gcRootType: [LeakTrace.GcRootType](-gc-root-type/index.md), referencePath: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTraceReference](../-leak-trace-reference/index.md)&gt;, leakingObject: [LeakTraceObject](../-leak-trace-object/index.md)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [GcRootType](-gc-root-type/index.md) | [jvm]<br>enum [GcRootType](-gc-root-type/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[LeakTrace.GcRootType](-gc-root-type/index.md)&gt; |

## Functions

| Name | Summary |
|---|---|
| [referencePathElementIsSuspect](reference-path-element-is-suspect.md) | [jvm]<br>fun [referencePathElementIsSuspect](reference-path-element-is-suspect.md)(index: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the [referencePath](reference-path.md) element at the provided [index](reference-path-element-is-suspect.md) contains a reference that is suspected to cause the leak, ie if [index](reference-path-element-is-suspect.md) is greater than or equal to the index of the [LeakTraceReference](../-leak-trace-reference/index.md) of the last non leaking object and strictly lower than the index of the [LeakTraceReference](../-leak-trace-reference/index.md) of the first leaking object. |
| [toSimplePathString](to-simple-path-string.md) | [jvm]<br>fun [toSimplePathString](to-simple-path-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [gcRootType](gc-root-type.md) | [jvm]<br>val [gcRootType](gc-root-type.md): [LeakTrace.GcRootType](-gc-root-type/index.md)<br>The Garbage Collection root that references the [LeakTraceReference.originObject](../-leak-trace-reference/origin-object.md) in the first [LeakTraceReference](../-leak-trace-reference/index.md) of [referencePath](reference-path.md). |
| [leakingObject](leaking-object.md) | [jvm]<br>val [leakingObject](leaking-object.md): [LeakTraceObject](../-leak-trace-object/index.md) |
| [referencePath](reference-path.md) | [jvm]<br>val [referencePath](reference-path.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTraceReference](../-leak-trace-reference/index.md)&gt; |
| [retainedHeapByteSize](retained-heap-byte-size.md) | [jvm]<br>val [retainedHeapByteSize](retained-heap-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>The minimum number of bytes which would be freed if the leak was fixed. Null if the retained heap size was not computed. |
| [retainedObjectCount](retained-object-count.md) | [jvm]<br>val [retainedObjectCount](retained-object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>The minimum number of objects which would be unreachable if the leak was fixed. Null if the retained heap size was not computed. |
| [signature](signature.md) | [jvm]<br>val [signature](signature.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A SHA1 hash that represents this leak trace. This can be useful to group together similar leak traces. |
| [suspectReferenceSubpath](suspect-reference-subpath.md) | [jvm]<br>val [suspectReferenceSubpath](suspect-reference-subpath.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[LeakTraceReference](../-leak-trace-reference/index.md)&gt;<br>A part of [referencePath](reference-path.md) that contains the references suspected to cause the leak. Starts at the last non leaking object and ends before the first leaking object. |
