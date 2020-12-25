[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [IMM_CUR_ROOT_VIEW](./index.md)

# IMM_CUR_ROOT_VIEW

`IMM_CUR_ROOT_VIEW`

When an activity is destroyed, the corresponding ViewRootImpl instance is released and ready to
be garbage collected.
Some time after that, ViewRootImpl#W receives a windowfocusChanged() callback, which it
normally delegates to ViewRootImpl which in turn calls
InputMethodManager#onPreWindowFocus which clears InputMethodManager#mCurRootView.

Unfortunately, since the ViewRootImpl instance is garbage collectable it may be garbage
collected before that happens.
ViewRootImpl#W has a weak reference on ViewRootImpl, so that weak reference will then return
null and the windowfocusChanged() callback will be ignored, leading to
InputMethodManager#mCurRootView not being cleared.

Filed here: https://issuetracker.google.com/u/0/issues/116078227
Fixed here: https://android.googlesource.com/platform/frameworks/base/+/dff365ef4dc61239fac70953b631e92972a9f41f%5E%21/#F0
InputMethodManager.mCurRootView is part of the unrestricted grey list on Android 9:
https://android.googlesource.com/platform/frameworks/base/+/pie-release/config/hiddenapi-light-greylist.txt#6057

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
