//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[ACTIVITY_MANAGER](index.md)

# ACTIVITY_MANAGER

[androidJvm]\
[ACTIVITY_MANAGER](index.md)()

Samsung added a static mContext field to ActivityManager, holding a reference to the activity.

This fix clears the field when an activity is destroyed if it refers to this specific activity.

Observed here: https://github.com/square/leakcanary/issues/177

## Properties

| Name | Summary |
|---|---|
| [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
