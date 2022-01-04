//[leakcanary-object-watcher](../../../../index.md)/[leakcanary](../../index.md)/[Clock](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: () -&gt; [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [Clock](../index.md)

Utility function to create a [Clock](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : Clock syntax.

Usage:

val clock = Clock {\
\
}
