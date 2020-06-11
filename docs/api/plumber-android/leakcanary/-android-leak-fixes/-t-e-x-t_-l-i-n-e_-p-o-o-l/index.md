[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [TEXT_LINE_POOL](./index.md)

# TEXT_LINE_POOL

`TEXT_LINE_POOL`

This flushes the TextLine pool when an activity is destroyed, to prevent memory leaks.

The first memory leak has been fixed in android-5.1.0_r1
https://github.com/android/platform_frameworks_base/commit/
893d6fe48d37f71e683f722457bea646994a10bf

Second memory leak: https://github.com/android/platform_frameworks_base/commit/
b3a9bc038d3a218b1dbdf7b5668e3d6c12be5ee4

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
