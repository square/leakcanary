[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [LAST_HOVERED_VIEW](./index.md)

# LAST_HOVERED_VIEW

`LAST_HOVERED_VIEW`

mLastHoveredView is a static field in TextView that leaks the last hovered view.

This fix clears it when the activity is destroyed.

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
