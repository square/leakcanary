[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [ACTIVITY_MANAGER](./index.md)

# ACTIVITY_MANAGER

`ACTIVITY_MANAGER`

Samsung added a static mContext field to ActivityManager, holding a reference to the activity.

This fix clears the field when an activity is destroyed if it refers to this specific activity.

Observed here: https://github.com/square/leakcanary/issues/177

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
