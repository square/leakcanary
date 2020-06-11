[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [SAMSUNG_CLIPBOARD_MANAGER](./index.md)

# SAMSUNG_CLIPBOARD_MANAGER

`SAMSUNG_CLIPBOARD_MANAGER`

ClipboardUIManager is a static singleton that leaks an activity context.
This fix makes sure the manager is called with an application context.

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
