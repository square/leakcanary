[leakcanary-android-core](../index.md) / [leakcanary](./index.md)

## Package leakcanary

### Types

| Name | Summary |
|---|---|
| [DefaultOnHeapAnalyzedListener](-default-on-heap-analyzed-listener/index.md) | `class DefaultOnHeapAnalyzedListener : `[`OnHeapAnalyzedListener`](-on-heap-analyzed-listener/index.md)<br>Default [OnHeapAnalyzedListener](-on-heap-analyzed-listener/index.md) implementation, which will store the analysis to disk and show a notification summarizing the result. |
| [LeakCanary](-leak-canary/index.md) | `object LeakCanary`<br>The entry point API for LeakCanary. LeakCanary builds on top of [AppWatcher](#). AppWatcher notifies LeakCanary of retained instances, which in turns dumps the heap, analyses it and publishes the results. |
| [OnHeapAnalyzedListener](-on-heap-analyzed-listener/index.md) | `interface OnHeapAnalyzedListener` |

### Functions

| Name | Summary |
|---|---|
| [&lt;no name provided&gt;](-no name provided-.md) | `fun <no name provided>(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Listener set in [LeakCanary.Config](-leak-canary/-config/index.md) and called by LeakCanary on a background thread when the heap analysis is complete. |
