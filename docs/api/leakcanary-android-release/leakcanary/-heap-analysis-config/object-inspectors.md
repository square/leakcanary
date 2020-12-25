[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisConfig](index.md) / [objectInspectors](./object-inspectors.md)

# objectInspectors

`val objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector>`

List of [ObjectInspector](#) that provide LeakCanary with insights about objects found in the
heap. You can create your own [ObjectInspector](#) implementations, and also add
a [shark.AppSingletonInspector](#) instance created with the list of internal singletons.

Defaults to [AndroidObjectInspectors.appDefaults](#)

