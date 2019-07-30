[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [retainedVisibleThreshold](./retained-visible-threshold.md)

# retainedVisibleThreshold

`val retainedVisibleThreshold: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

When the app is visible, LeakCanary will wait for at least
[retainedVisibleThreshold](./retained-visible-threshold.md) retained instances before dumping the heap. Dumping the heap
freezes the UI and can be frustrating for developers who are trying to work. This is
especially frustrating as the Android Framework has a number of leaks that cannot easily
be fixed.

When the app becomes invisible, LeakCanary dumps the heap after
[AppWatcher.Config.watchDurationMillis](#) ms.

The app is considered visible if it has at least one activity in started state.

A higher threshold means LeakCanary will dump the heap less often, therefore it won't be
bothering developers as much but it could miss some leaks.

Defaults to 5.

