//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[USER_MANAGER](index.md)

# USER_MANAGER

[androidJvm]\
[USER_MANAGER](index.md)()

Obtaining the UserManager service ends up calling the hidden UserManager.get() method which stores the context in a singleton UserManager instance and then stores that instance in a static field.

We obtain the user manager from an activity context, so if it hasn't been created yet it will leak that activity forever.

This fix makes sure the UserManager is created and holds on to the Application context.

Issue: https://code.google.com/p/android/issues/detail?id=173789

Fixed in https://android.googlesource.com/platform/frameworks/base/+/ 5200e1cb07190a1f6874d72a4561064cad3ee3e0%5E%21/#F0 (Android O)

## Properties

| Name | Summary |
|---|---|
| [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
