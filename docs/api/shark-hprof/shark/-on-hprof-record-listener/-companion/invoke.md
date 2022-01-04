//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[OnHprofRecordListener](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: ([Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), [HprofRecord](../../-hprof-record/index.md)) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)): [OnHprofRecordListener](../index.md)

Utility function to create a [OnHprofRecordListener](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : OnHprofRecordListener syntax.

Usage:

val listener = OnHprofRecordListener { position, record -&gt;\
\
}
