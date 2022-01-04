//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[MEDIA_SESSION_LEGACY_HELPER](index.md)

# MEDIA_SESSION_LEGACY_HELPER

[androidJvm]\
[MEDIA_SESSION_LEGACY_HELPER](index.md)()

MediaSessionLegacyHelper is a static singleton and did not use the application context. Introduced in android-5.0.1_r1, fixed in Android 5.1.0_r1. https://github.com/android/platform_frameworks_base/commit/ 9b5257c9c99c4cb541d8e8e78fb04f008b1a9091

We fix this leak by invoking MediaSessionLegacyHelper.getHelper() early in the app lifecycle.

## Properties

| Name | Summary |
|---|---|
| [name](index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
