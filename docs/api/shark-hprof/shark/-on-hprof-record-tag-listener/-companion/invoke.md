//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[OnHprofRecordTagListener](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: ([HprofRecordTag](../../-hprof-record-tag/index.md), [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), [HprofRecordReader](../../-hprof-record-reader/index.md)) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)): [OnHprofRecordTagListener](../index.md)

Utility function to create a [OnHprofRecordTagListener](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : OnHprofRecordTagListener syntax.

Usage:

val listener = OnHprofRecordTagListener { tag, length, reader -&gt;\
\
}
