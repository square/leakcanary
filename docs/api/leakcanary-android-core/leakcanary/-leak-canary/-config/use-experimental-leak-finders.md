[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [useExperimentalLeakFinders](./use-experimental-leak-finders.md)

# useExperimentalLeakFinders

`val useExperimentalLeakFinders: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

When true, [objectInspectors](object-inspectors.md) are used to find leaks instead of only checking instances
tracked by [KeyedWeakReference](#). This leads to finding more leaks and shorter leak traces.
However this also means the same leaking instances will be found in every heap dump for a
given process life.

Defaults to false.

