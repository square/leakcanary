//[shark-android](../../../index.md)/[shark](../index.md)/[AndroidObjectInspectors](index.md)

# AndroidObjectInspectors

[jvm]\
enum [AndroidObjectInspectors](index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[AndroidObjectInspectors](index.md)&gt; , ObjectInspector

A set of default ObjectInspectors that knows about common AOSP and library classes.

These are heuristics based on our experience and knowledge of AOSP and various library internals. We only make a decision if we're reasonably sure the state of an object is unlikely to be the result of a programmer mistake.

For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy will not be influenced by those mistakes.

Most developers should use the entire set of default ObjectInspector by calling [appDefaults](-companion/app-defaults.md), unless there's a bug and you temporarily want to remove an inspector.

## Entries

| | |
|---|---|
| [OBJECT_ANIMATOR](-o-b-j-e-c-t_-a-n-i-m-a-t-o-r/index.md) | [jvm]<br>[OBJECT_ANIMATOR](-o-b-j-e-c-t_-a-n-i-m-a-t-o-r/index.md)() |
| [ANIMATOR](-a-n-i-m-a-t-o-r/index.md) | [jvm]<br>[ANIMATOR](-a-n-i-m-a-t-o-r/index.md)() |
| [COMPOSITION_IMPL](-c-o-m-p-o-s-i-t-i-o-n_-i-m-p-l/index.md) | [jvm]<br>[COMPOSITION_IMPL](-c-o-m-p-o-s-i-t-i-o-n_-i-m-p-l/index.md)() |
| [RECOMPOSER](-r-e-c-o-m-p-o-s-e-r/index.md) | [jvm]<br>[RECOMPOSER](-r-e-c-o-m-p-o-s-e-r/index.md)() |
| [TOAST](-t-o-a-s-t/index.md) | [jvm]<br>[TOAST](-t-o-a-s-t/index.md)() |
| [MESSAGE](-m-e-s-s-a-g-e/index.md) | [jvm]<br>[MESSAGE](-m-e-s-s-a-g-e/index.md)() |
| [WINDOW](-w-i-n-d-o-w/index.md) | [jvm]<br>[WINDOW](-w-i-n-d-o-w/index.md)() |
| [VIEW_ROOT_IMPL](-v-i-e-w_-r-o-o-t_-i-m-p-l/index.md) | [jvm]<br>[VIEW_ROOT_IMPL](-v-i-e-w_-r-o-o-t_-i-m-p-l/index.md)() |
| [MAIN_THREAD](-m-a-i-n_-t-h-r-e-a-d/index.md) | [jvm]<br>[MAIN_THREAD](-m-a-i-n_-t-h-r-e-a-d/index.md)() |
| [COORDINATOR](-c-o-o-r-d-i-n-a-t-o-r/index.md) | [jvm]<br>[COORDINATOR](-c-o-o-r-d-i-n-a-t-o-r/index.md)() |
| [MORTAR_SCOPE](-m-o-r-t-a-r_-s-c-o-p-e/index.md) | [jvm]<br>[MORTAR_SCOPE](-m-o-r-t-a-r_-s-c-o-p-e/index.md)() |
| [MORTAR_PRESENTER](-m-o-r-t-a-r_-p-r-e-s-e-n-t-e-r/index.md) | [jvm]<br>[MORTAR_PRESENTER](-m-o-r-t-a-r_-p-r-e-s-e-n-t-e-r/index.md)() |
| [LOADED_APK](-l-o-a-d-e-d_-a-p-k/index.md) | [jvm]<br>[LOADED_APK](-l-o-a-d-e-d_-a-p-k/index.md)() |
| [MESSAGE_QUEUE](-m-e-s-s-a-g-e_-q-u-e-u-e/index.md) | [jvm]<br>[MESSAGE_QUEUE](-m-e-s-s-a-g-e_-q-u-e-u-e/index.md)() |
| [ANDROIDX_FRAGMENT](-a-n-d-r-o-i-d-x_-f-r-a-g-m-e-n-t/index.md) | [jvm]<br>[ANDROIDX_FRAGMENT](-a-n-d-r-o-i-d-x_-f-r-a-g-m-e-n-t/index.md)() |
| [SUPPORT_FRAGMENT](-s-u-p-p-o-r-t_-f-r-a-g-m-e-n-t/index.md) | [jvm]<br>[SUPPORT_FRAGMENT](-s-u-p-p-o-r-t_-f-r-a-g-m-e-n-t/index.md)() |
| [FRAGMENT](-f-r-a-g-m-e-n-t/index.md) | [jvm]<br>[FRAGMENT](-f-r-a-g-m-e-n-t/index.md)() |
| [INPUT_METHOD_MANAGER](-i-n-p-u-t_-m-e-t-h-o-d_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[INPUT_METHOD_MANAGER](-i-n-p-u-t_-m-e-t-h-o-d_-m-a-n-a-g-e-r/index.md)() |
| [APPLICATION](-a-p-p-l-i-c-a-t-i-o-n/index.md) | [jvm]<br>[APPLICATION](-a-p-p-l-i-c-a-t-i-o-n/index.md)() |
| [DIALOG](-d-i-a-l-o-g/index.md) | [jvm]<br>[DIALOG](-d-i-a-l-o-g/index.md)() |
| [CONTEXT_IMPL](-c-o-n-t-e-x-t_-i-m-p-l/index.md) | [jvm]<br>[CONTEXT_IMPL](-c-o-n-t-e-x-t_-i-m-p-l/index.md)() |
| [APPLICATION_PACKAGE_MANAGER](-a-p-p-l-i-c-a-t-i-o-n_-p-a-c-k-a-g-e_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[APPLICATION_PACKAGE_MANAGER](-a-p-p-l-i-c-a-t-i-o-n_-p-a-c-k-a-g-e_-m-a-n-a-g-e-r/index.md)() |
| [CONTEXT_WRAPPER](-c-o-n-t-e-x-t_-w-r-a-p-p-e-r/index.md) | [jvm]<br>[CONTEXT_WRAPPER](-c-o-n-t-e-x-t_-w-r-a-p-p-e-r/index.md)() |
| [CONTEXT_FIELD](-c-o-n-t-e-x-t_-f-i-e-l-d/index.md) | [jvm]<br>[CONTEXT_FIELD](-c-o-n-t-e-x-t_-f-i-e-l-d/index.md)() |
| [SERVICE](-s-e-r-v-i-c-e/index.md) | [jvm]<br>[SERVICE](-s-e-r-v-i-c-e/index.md)() |
| [ACTIVITY](-a-c-t-i-v-i-t-y/index.md) | [jvm]<br>[ACTIVITY](-a-c-t-i-v-i-t-y/index.md)() |
| [EDITOR](-e-d-i-t-o-r/index.md) | [jvm]<br>[EDITOR](-e-d-i-t-o-r/index.md)() |
| [VIEW](-v-i-e-w/index.md) | [jvm]<br>[VIEW](-v-i-e-w/index.md)() |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [inspect](index.md#-1117593919%2FFunctions%2F980726859) | [jvm]<br>abstract fun [inspect](index.md#-1117593919%2FFunctions%2F980726859)(reporter: ObjectReporter) |

## Properties

| Name | Summary |
|---|---|
| [name](../-android-reference-matchers/-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-372974862%2FProperties%2F980726859) | [jvm]<br>val [name](../-android-reference-matchers/-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-372974862%2FProperties%2F980726859): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-android-reference-matchers/-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-739389684%2FProperties%2F980726859) | [jvm]<br>val [ordinal](../-android-reference-matchers/-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-739389684%2FProperties%2F980726859): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
