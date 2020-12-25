[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [IMM_FOCUSED_VIEW](./index.md)

# IMM_FOCUSED_VIEW

`IMM_FOCUSED_VIEW`

Fix for https://code.google.com/p/android/issues/detail?id=171190 .

When a view that has focus gets detached, we wait for the main thread to be idle and then
check if the InputMethodManager is leaking a view. If yes, we tell it that the decor view got
focus, which is what happens if you press home and come back from recent apps. This replaces
the reference to the detached view with a reference to the decor view.

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
