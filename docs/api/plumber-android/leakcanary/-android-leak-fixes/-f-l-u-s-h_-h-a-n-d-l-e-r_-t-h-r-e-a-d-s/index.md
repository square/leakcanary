[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [FLUSH_HANDLER_THREADS](./index.md)

# FLUSH_HANDLER_THREADS

`FLUSH_HANDLER_THREADS`

HandlerThread instances keep local reference to their last handled message after recycling it.
That message is obtained by a dialog which sets on an OnClickListener on it and then never
recycles it, expecting it to be garbage collected but it ends up being held by the
HandlerThread.

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
