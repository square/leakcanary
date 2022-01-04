//[shark](../../../index.md)/[shark](../index.md)/[LeakTraceReference](index.md)

# LeakTraceReference

[jvm]\
data class [LeakTraceReference](index.md)(originObject: [LeakTraceObject](../-leak-trace-object/index.md), referenceType: [LeakTraceReference.ReferenceType](-reference-type/index.md), owningClassName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), referenceName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)

A [LeakTraceReference](index.md) represents an origin [LeakTraceObject](../-leak-trace-object/index.md) and either a reference from that object to the [LeakTraceObject](../-leak-trace-object/index.md) in the next [LeakTraceReference](index.md) in [LeakTrace.referencePath](../-leak-trace/reference-path.md), or to [LeakTrace.leakingObject](../-leak-trace/leaking-object.md) if this is the last [LeakTraceReference](index.md) in [LeakTrace.referencePath](../-leak-trace/reference-path.md).

## Constructors

| | |
|---|---|
| [LeakTraceReference](-leak-trace-reference.md) | [jvm]<br>fun [LeakTraceReference](-leak-trace-reference.md)(originObject: [LeakTraceObject](../-leak-trace-object/index.md), referenceType: [LeakTraceReference.ReferenceType](-reference-type/index.md), owningClassName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), referenceName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [ReferenceType](-reference-type/index.md) | [jvm]<br>enum [ReferenceType](-reference-type/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[LeakTraceReference.ReferenceType](-reference-type/index.md)&gt; |

## Properties

| Name | Summary |
|---|---|
| [originObject](origin-object.md) | [jvm]<br>val [originObject](origin-object.md): [LeakTraceObject](../-leak-trace-object/index.md) |
| [owningClassName](owning-class-name.md) | [jvm]<br>val [owningClassName](owning-class-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [owningClassSimpleName](owning-class-simple-name.md) | [jvm]<br>val [owningClassSimpleName](owning-class-simple-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns {@link #className} without the package, ie stripped of any string content before the last period (included). |
| [referenceDisplayName](reference-display-name.md) | [jvm]<br>val [referenceDisplayName](reference-display-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [referenceGenericName](reference-generic-name.md) | [jvm]<br>val [referenceGenericName](reference-generic-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [referenceName](reference-name.md) | [jvm]<br>val [referenceName](reference-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [referenceType](reference-type.md) | [jvm]<br>val [referenceType](reference-type.md): [LeakTraceReference.ReferenceType](-reference-type/index.md) |
