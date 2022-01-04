//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[IMM_FOCUSED_VIEW](index.md)

# IMM_FOCUSED_VIEW

[androidJvm]\
[IMM_FOCUSED_VIEW](index.md)()

Fix for https://code.google.com/p/android/issues/detail?id=171190 .

When a view that has focus gets detached, we wait for the main thread to be idle and then check if the InputMethodManager is leaking a view. If yes, we tell it that the decor view got focus, which is what happens if you press home and come back from recent apps. This replaces the reference to the detached view with a reference to the decor view.

## Properties

| Name | Summary |
|---|---|
| [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
