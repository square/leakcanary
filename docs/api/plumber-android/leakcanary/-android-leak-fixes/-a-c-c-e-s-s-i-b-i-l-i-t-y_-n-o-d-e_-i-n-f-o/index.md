[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [ACCESSIBILITY_NODE_INFO](./index.md)

# ACCESSIBILITY_NODE_INFO

`ACCESSIBILITY_NODE_INFO`

Until API 28, AccessibilityNodeInfo has a mOriginalText field that was not properly cleared
when instance were put back in the pool.
Leak introduced here: https://android.googlesource.com/platform/frameworks/base/+
/193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/android/view/accessibility
/AccessibilityNodeInfo.java

Fixed here: https://android.googlesource.com/platform/frameworks/base/+
/6f8ec1fd8c159b09d617ed6d9132658051443c0c

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
