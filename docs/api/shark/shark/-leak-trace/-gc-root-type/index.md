//[shark](../../../../index.md)/[shark](../../index.md)/[LeakTrace](../index.md)/[GcRootType](index.md)

# GcRootType

[jvm]\
enum [GcRootType](index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[LeakTrace.GcRootType](index.md)&gt;

## Entries

| | |
|---|---|
| [JNI_MONITOR](-j-n-i_-m-o-n-i-t-o-r/index.md) | [jvm]<br>[JNI_MONITOR](-j-n-i_-m-o-n-i-t-o-r/index.md)("Root JNI monitor") |
| [THREAD_OBJECT](-t-h-r-e-a-d_-o-b-j-e-c-t/index.md) | [jvm]<br>[THREAD_OBJECT](-t-h-r-e-a-d_-o-b-j-e-c-t/index.md)("Thread object") |
| [MONITOR_USED](-m-o-n-i-t-o-r_-u-s-e-d/index.md) | [jvm]<br>[MONITOR_USED](-m-o-n-i-t-o-r_-u-s-e-d/index.md)("Monitor (anything that called the wait() or notify() methods, or that is synchronized.)") |
| [THREAD_BLOCK](-t-h-r-e-a-d_-b-l-o-c-k/index.md) | [jvm]<br>[THREAD_BLOCK](-t-h-r-e-a-d_-b-l-o-c-k/index.md)("Thread block") |
| [STICKY_CLASS](-s-t-i-c-k-y_-c-l-a-s-s/index.md) | [jvm]<br>[STICKY_CLASS](-s-t-i-c-k-y_-c-l-a-s-s/index.md)("System class") |
| [NATIVE_STACK](-n-a-t-i-v-e_-s-t-a-c-k/index.md) | [jvm]<br>[NATIVE_STACK](-n-a-t-i-v-e_-s-t-a-c-k/index.md)("Input or output parameters in native code") |
| [JAVA_FRAME](-j-a-v-a_-f-r-a-m-e/index.md) | [jvm]<br>[JAVA_FRAME](-j-a-v-a_-f-r-a-m-e/index.md)("Java local variable") |
| [JNI_LOCAL](-j-n-i_-l-o-c-a-l/index.md) | [jvm]<br>[JNI_LOCAL](-j-n-i_-l-o-c-a-l/index.md)("Local variable in native code") |
| [JNI_GLOBAL](-j-n-i_-g-l-o-b-a-l/index.md) | [jvm]<br>[JNI_GLOBAL](-j-n-i_-g-l-o-b-a-l/index.md)("Global variable in native code") |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [description](description.md) | [jvm]<br>val [description](description.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [name](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-372974862%2FProperties%2F-1562156115) | [jvm]<br>val [name](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-372974862%2FProperties%2F-1562156115): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-739389684%2FProperties%2F-1562156115) | [jvm]<br>val [ordinal](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-739389684%2FProperties%2F-1562156115): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
