[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [MEDIA_SESSION_LEGACY_HELPER](./index.md)

# MEDIA_SESSION_LEGACY_HELPER

`MEDIA_SESSION_LEGACY_HELPER`

MediaSessionLegacyHelper is a static singleton and did not use the application context.
Introduced in android-5.0.1_r1, fixed in Android 5.1.0_r1.
https://github.com/android/platform_frameworks_base/commit/
9b5257c9c99c4cb541d8e8e78fb04f008b1a9091

We fix this leak by invoking MediaSessionLegacyHelper.getHelper() early in the app lifecycle.

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
