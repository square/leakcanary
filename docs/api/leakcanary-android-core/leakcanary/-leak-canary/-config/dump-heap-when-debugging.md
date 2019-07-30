[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [dumpHeapWhenDebugging](./dump-heap-when-debugging.md)

# dumpHeapWhenDebugging

`val dumpHeapWhenDebugging: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

If [dumpHeapWhenDebugging](./dump-heap-when-debugging.md) is false then LeakCanary will not dump the heap
when the debugger is attached. The debugger can create temporary memory leaks (for instance
if a thread is blocked on a breakpoint).

Defaults to false.

