[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [BUBBLE_POPUP](./index.md)

# BUBBLE_POPUP

`BUBBLE_POPUP`

A static helper for EditText bubble popups leaks a reference to the latest focused view.

This fix clears it when the activity is destroyed.

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
