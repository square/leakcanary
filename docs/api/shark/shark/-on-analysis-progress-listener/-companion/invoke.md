//[shark](../../../../index.md)/[shark](../../index.md)/[OnAnalysisProgressListener](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: ([OnAnalysisProgressListener.Step](../-step/index.md)) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)): [OnAnalysisProgressListener](../index.md)

Utility function to create a [OnAnalysisProgressListener](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : OnAnalysisProgressListener syntax.

Usage:

val listener = OnAnalysisProgressListener {\
\
}
