[plumber-android](../../../index.md) / [leakcanary](../../index.md) / [AndroidLeakFixes](../index.md) / [USER_MANAGER](./index.md)

# USER_MANAGER

`USER_MANAGER`

Obtaining the UserManager service ends up calling the hidden UserManager.get() method which
stores the context in a singleton UserManager instance and then stores that instance in a
static field.

We obtain the user manager from an activity context, so if it hasn't been created yet it will
leak that activity forever.

This fix makes sure the UserManager is created and holds on to the Application context.

Issue: https://code.google.com/p/android/issues/detail?id=173789

Fixed in https://android.googlesource.com/platform/frameworks/base/+/
5200e1cb07190a1f6874d72a4561064cad3ee3e0%5E%21/#F0 (Android O)

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
