[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [CONNECTIVITY_MANAGER](./index.md)

# CONNECTIVITY_MANAGER

`CONNECTIVITY_MANAGER`

ConnectivityManager has a sInstance field that is set when the first ConnectivityManager instance is created.
ConnectivityManager has a mContext field.
When calling activity.getSystemService(Context.CONNECTIVITY_SERVICE) , the first ConnectivityManager instance
is created with the activity context and stored in sInstance.
That activity context then leaks forever.

This fix makes sure the connectivity manager is created with the application context.

Tracked here: https://code.google.com/p/android/issues/detail?id=198852
Introduced here: https://github.com/android/platform_frameworks_base/commit/e0bef71662d81caaaa0d7214fb0bef5d39996a69

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
