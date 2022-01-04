//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[ACCESSIBILITY_NODE_INFO](index.md)

# ACCESSIBILITY_NODE_INFO

[androidJvm]\
[ACCESSIBILITY_NODE_INFO](index.md)()

Until API 28, AccessibilityNodeInfo has a mOriginalText field that was not properly cleared when instance were put back in the pool. Leak introduced here: https://android.googlesource.com/platform/frameworks/base/+ /193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/android/view/accessibility /AccessibilityNodeInfo.java

Fixed here: https://android.googlesource.com/platform/frameworks/base/+ /6f8ec1fd8c159b09d617ed6d9132658051443c0c

## Properties

| Name | Summary |
|---|---|
| [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
