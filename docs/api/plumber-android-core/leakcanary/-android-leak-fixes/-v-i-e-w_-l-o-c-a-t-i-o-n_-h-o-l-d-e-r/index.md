//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[VIEW_LOCATION_HOLDER](index.md)

# VIEW_LOCATION_HOLDER

[androidJvm]\
[VIEW_LOCATION_HOLDER](index.md)()

In Android P, ViewLocationHolder has an mRoot field that is not cleared in its clear() method. Introduced in https://github.com/aosp-mirror/platform_frameworks_base/commit /86b326012813f09d8f1de7d6d26c986a909d

This leaks triggers very often when accessibility is on. To fix this leak we need to clear the ViewGroup.ViewLocationHolder.sPool pool. Unfortunately Android P prevents accessing that field through reflection. So instead, we call ViewGroup#addChildrenForAccessibility with a view group that has 32 children (32 being the pool size), which as result fills in the pool with 32 dumb views that reference a dummy context instead of an activity context.

This fix empties the pool on every activity destroy and every AndroidX fragment view destroy. You can support other cases where views get detached by calling directly [ViewLocationHolderLeakFix.clearStaticPool](../../-view-location-holder-leak-fix/clear-static-pool.md).

## Properties

| Name | Summary |
|---|---|
| [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
