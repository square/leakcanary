[shark-hprof](../../index.md) / [shark](../index.md) / [HprofRecordTag](./index.md)

# HprofRecordTag

`enum class HprofRecordTag`

### Enum Values

| Name | Summary |
|---|---|
| [STRING_IN_UTF8](-s-t-r-i-n-g_-i-n_-u-t-f8.md) |  |
| [LOAD_CLASS](-l-o-a-d_-c-l-a-s-s.md) |  |
| [UNLOAD_CLASS](-u-n-l-o-a-d_-c-l-a-s-s.md) |  |
| [STACK_FRAME](-s-t-a-c-k_-f-r-a-m-e.md) |  |
| [STACK_TRACE](-s-t-a-c-k_-t-r-a-c-e.md) |  |
| [ALLOC_SITES](-a-l-l-o-c_-s-i-t-e-s.md) |  |
| [HEAP_SUMMARY](-h-e-a-p_-s-u-m-m-a-r-y.md) |  |
| [START_THREAD](-s-t-a-r-t_-t-h-r-e-a-d.md) |  |
| [END_THREAD](-e-n-d_-t-h-r-e-a-d.md) |  |
| [HEAP_DUMP](-h-e-a-p_-d-u-m-p.md) |  |
| [HEAP_DUMP_SEGMENT](-h-e-a-p_-d-u-m-p_-s-e-g-m-e-n-t.md) |  |
| [HEAP_DUMP_END](-h-e-a-p_-d-u-m-p_-e-n-d.md) |  |
| [CPU_SAMPLES](-c-p-u_-s-a-m-p-l-e-s.md) |  |
| [CONTROL_SETTINGS](-c-o-n-t-r-o-l_-s-e-t-t-i-n-g-s.md) |  |
| [ROOT_UNKNOWN](-r-o-o-t_-u-n-k-n-o-w-n.md) |  |
| [ROOT_JNI_GLOBAL](-r-o-o-t_-j-n-i_-g-l-o-b-a-l.md) |  |
| [ROOT_JNI_LOCAL](-r-o-o-t_-j-n-i_-l-o-c-a-l.md) |  |
| [ROOT_JAVA_FRAME](-r-o-o-t_-j-a-v-a_-f-r-a-m-e.md) |  |
| [ROOT_NATIVE_STACK](-r-o-o-t_-n-a-t-i-v-e_-s-t-a-c-k.md) |  |
| [ROOT_STICKY_CLASS](-r-o-o-t_-s-t-i-c-k-y_-c-l-a-s-s.md) |  |
| [ROOT_THREAD_BLOCK](-r-o-o-t_-t-h-r-e-a-d_-b-l-o-c-k.md) |  |
| [ROOT_MONITOR_USED](-r-o-o-t_-m-o-n-i-t-o-r_-u-s-e-d.md) |  |
| [ROOT_THREAD_OBJECT](-r-o-o-t_-t-h-r-e-a-d_-o-b-j-e-c-t.md) |  |
| [HEAP_DUMP_INFO](-h-e-a-p_-d-u-m-p_-i-n-f-o.md) | Android format addition |
| [ROOT_INTERNED_STRING](-r-o-o-t_-i-n-t-e-r-n-e-d_-s-t-r-i-n-g.md) |  |
| [ROOT_FINALIZING](-r-o-o-t_-f-i-n-a-l-i-z-i-n-g.md) |  |
| [ROOT_DEBUGGER](-r-o-o-t_-d-e-b-u-g-g-e-r.md) |  |
| [ROOT_REFERENCE_CLEANUP](-r-o-o-t_-r-e-f-e-r-e-n-c-e_-c-l-e-a-n-u-p.md) |  |
| [ROOT_VM_INTERNAL](-r-o-o-t_-v-m_-i-n-t-e-r-n-a-l.md) |  |
| [ROOT_JNI_MONITOR](-r-o-o-t_-j-n-i_-m-o-n-i-t-o-r.md) |  |
| [ROOT_UNREACHABLE](-r-o-o-t_-u-n-r-e-a-c-h-a-b-l-e.md) |  |
| [PRIMITIVE_ARRAY_NODATA](-p-r-i-m-i-t-i-v-e_-a-r-r-a-y_-n-o-d-a-t-a.md) |  |
| [CLASS_DUMP](-c-l-a-s-s_-d-u-m-p.md) |  |
| [INSTANCE_DUMP](-i-n-s-t-a-n-c-e_-d-u-m-p.md) |  |
| [OBJECT_ARRAY_DUMP](-o-b-j-e-c-t_-a-r-r-a-y_-d-u-m-p.md) |  |
| [PRIMITIVE_ARRAY_DUMP](-p-r-i-m-i-t-i-v-e_-a-r-r-a-y_-d-u-m-p.md) |  |

### Properties

| Name | Summary |
|---|---|
| [tag](tag.md) | `val tag: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |

### Companion Object Properties

| Name | Summary |
|---|---|
| [rootTags](root-tags.md) | `val rootTags: `[`EnumSet`](https://docs.oracle.com/javase/6/docs/api/java/util/EnumSet.html)`<`[`HprofRecordTag`](./index.md)`>` |
