[shark-android](../../index.md) / [shark](../index.md) / [AndroidObjectInspectors](./index.md)

# AndroidObjectInspectors

`enum class AndroidObjectInspectors : ObjectInspector`

A set of default [ObjectInspector](#)s that knows about common AOSP and library
classes.

These are heuristics based on our experience and knowledge of AOSP and various library
internals. We only make a decision if we're reasonably sure the state of an object is
unlikely to be the result of a programmer mistake.

For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy
will not be influenced by those mistakes.

Most developers should use the entire set of default [ObjectInspector](#) by calling [appDefaults](app-defaults.md),
unless there's a bug and you temporarily want to remove an inspector.

### Enum Values

| Name | Summary |
|---|---|
| [VIEW](-v-i-e-w/index.md) |  |
| [EDITOR](-e-d-i-t-o-r/index.md) |  |
| [ACTIVITY](-a-c-t-i-v-i-t-y/index.md) |  |
| [CONTEXT_FIELD](-c-o-n-t-e-x-t_-f-i-e-l-d/index.md) |  |
| [CONTEXT_WRAPPER](-c-o-n-t-e-x-t_-w-r-a-p-p-e-r/index.md) |  |
| [APPLICATION_PACKAGE_MANAGER](-a-p-p-l-i-c-a-t-i-o-n_-p-a-c-k-a-g-e_-m-a-n-a-g-e-r/index.md) |  |
| [CONTEXT_IMPL](-c-o-n-t-e-x-t_-i-m-p-l/index.md) |  |
| [DIALOG](-d-i-a-l-o-g/index.md) |  |
| [APPLICATION](-a-p-p-l-i-c-a-t-i-o-n/index.md) |  |
| [INPUT_METHOD_MANAGER](-i-n-p-u-t_-m-e-t-h-o-d_-m-a-n-a-g-e-r/index.md) |  |
| [FRAGMENT](-f-r-a-g-m-e-n-t/index.md) |  |
| [SUPPORT_FRAGMENT](-s-u-p-p-o-r-t_-f-r-a-g-m-e-n-t/index.md) |  |
| [ANDROIDX_FRAGMENT](-a-n-d-r-o-i-d-x_-f-r-a-g-m-e-n-t/index.md) |  |
| [MESSAGE_QUEUE](-m-e-s-s-a-g-e_-q-u-e-u-e/index.md) |  |
| [MORTAR_PRESENTER](-m-o-r-t-a-r_-p-r-e-s-e-n-t-e-r/index.md) |  |
| [MORTAR_SCOPE](-m-o-r-t-a-r_-s-c-o-p-e/index.md) |  |
| [COORDINATOR](-c-o-o-r-d-i-n-a-t-o-r/index.md) |  |
| [MAIN_THREAD](-m-a-i-n_-t-h-r-e-a-d/index.md) |  |
| [VIEW_ROOT_IMPL](-v-i-e-w_-r-o-o-t_-i-m-p-l/index.md) |  |
| [WINDOW](-w-i-n-d-o-w/index.md) |  |
| [MESSAGE](-m-e-s-s-a-g-e/index.md) |  |
| [TOAST](-t-o-a-s-t/index.md) |  |

### Companion Object Properties

| Name | Summary |
|---|---|
| [appDefaults](app-defaults.md) | `val appDefaults: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector>` |
| [appLeakingObjectFilters](app-leaking-object-filters.md) | `val appLeakingObjectFilters: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<LeakingObjectFilter>`<br>Returns a list of [LeakingObjectFilter](#) suitable for apps. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [createLeakingObjectFilters](create-leaking-object-filters.md) | `fun createLeakingObjectFilters(inspectors: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`AndroidObjectInspectors`](./index.md)`>): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<LeakingObjectFilter>`<br>Creates a list of [LeakingObjectFilter](#) based on the passed in [AndroidObjectInspectors](./index.md). |
