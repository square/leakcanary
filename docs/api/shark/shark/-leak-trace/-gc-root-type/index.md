[shark](../../../index.md) / [shark](../../index.md) / [LeakTrace](../index.md) / [GcRootType](./index.md)

# GcRootType

`enum class GcRootType`

### Enum Values

| Name | Summary |
|---|---|
| [JNI_GLOBAL](-j-n-i_-g-l-o-b-a-l.md) |  |
| [JNI_LOCAL](-j-n-i_-l-o-c-a-l.md) |  |
| [JAVA_FRAME](-j-a-v-a_-f-r-a-m-e.md) |  |
| [NATIVE_STACK](-n-a-t-i-v-e_-s-t-a-c-k.md) |  |
| [STICKY_CLASS](-s-t-i-c-k-y_-c-l-a-s-s.md) |  |
| [THREAD_BLOCK](-t-h-r-e-a-d_-b-l-o-c-k.md) |  |
| [MONITOR_USED](-m-o-n-i-t-o-r_-u-s-e-d.md) |  |
| [THREAD_OBJECT](-t-h-r-e-a-d_-o-b-j-e-c-t.md) |  |
| [JNI_MONITOR](-j-n-i_-m-o-n-i-t-o-r.md) |  |

### Properties

| Name | Summary |
|---|---|
| [description](description.md) | `val description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [fromGcRoot](from-gc-root.md) | `fun fromGcRoot(gcRoot: GcRoot): `[`LeakTrace.GcRootType`](./index.md) |
