//[shark](../../../index.md)/[shark](../index.md)/[LeakTraceObject](index.md)

# LeakTraceObject

[jvm]\
data class [LeakTraceObject](index.md)(type: [LeakTraceObject.ObjectType](-object-type/index.md), className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), labels: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;, leakingStatus: [LeakTraceObject.LeakingStatus](-leaking-status/index.md), leakingStatusReason: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), retainedHeapByteSize: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?, retainedObjectCount: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?) : [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [LeakingStatus](-leaking-status/index.md) | [jvm]<br>enum [LeakingStatus](-leaking-status/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[LeakTraceObject.LeakingStatus](-leaking-status/index.md)&gt; |
| [ObjectType](-object-type/index.md) | [jvm]<br>enum [ObjectType](-object-type/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[LeakTraceObject.ObjectType](-object-type/index.md)&gt; |

## Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | [jvm]<br>val [className](class-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Class name of the object. The class name format is the same as what would be returned by [Class.getName](https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#getName--). |
| [classSimpleName](class-simple-name.md) | [jvm]<br>val [classSimpleName](class-simple-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns {@link #className} without the package, ie stripped of any string content before the last period (included). |
| [labels](labels.md) | [jvm]<br>val [labels](labels.md): [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;<br>Labels that were computed during analysis. A label provides extra information that helps understand the state of the leak trace object. |
| [leakingStatus](leaking-status.md) | [jvm]<br>val [leakingStatus](leaking-status.md): [LeakTraceObject.LeakingStatus](-leaking-status/index.md) |
| [leakingStatusReason](leaking-status-reason.md) | [jvm]<br>val [leakingStatusReason](leaking-status-reason.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [retainedHeapByteSize](retained-heap-byte-size.md) | [jvm]<br>val [retainedHeapByteSize](retained-heap-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>The minimum number of bytes which would be freed if all references to this object were released. Not null only if the retained heap size was computed AND [leakingStatus](leaking-status.md) is equal to [LeakingStatus.UNKNOWN](-leaking-status/-u-n-k-n-o-w-n/index.md) or [LeakingStatus.LEAKING](-leaking-status/-l-e-a-k-i-n-g/index.md). |
| [retainedObjectCount](retained-object-count.md) | [jvm]<br>val [retainedObjectCount](retained-object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>The minimum number of objects which would be unreachable if all references to this object were released. Not null only if the retained heap size was computed AND [leakingStatus](leaking-status.md) is equal to [LeakingStatus.UNKNOWN](-leaking-status/-u-n-k-n-o-w-n/index.md) or [LeakingStatus.LEAKING](-leaking-status/-l-e-a-k-i-n-g/index.md). |
| [type](type.md) | [jvm]<br>val [type](type.md): [LeakTraceObject.ObjectType](-object-type/index.md) |
| [typeName](type-name.md) | [jvm]<br>val [typeName](type-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
