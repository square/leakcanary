[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ObjectWatcher](index.md) / [watch](./watch.md)

# watch

`@Synchronized fun watch(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Identical to [watch](./watch.md) with an empty string reference name.

`@Synchronized fun watch(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`, name: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Watches the provided [watchedObject](watch.md#leakcanary.ObjectWatcher$watch(kotlin.Any, kotlin.String)/watchedObject).

### Parameters

`name` - A logical identifier for the watched object.