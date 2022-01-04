//[shark](../../../../index.md)/[shark](../../index.md)/[LeakTraceObject](../index.md)/[LeakingStatus](index.md)

# LeakingStatus

[jvm]\
enum [LeakingStatus](index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[LeakTraceObject.LeakingStatus](index.md)&gt;

## Entries

| | |
|---|---|
| [UNKNOWN](-u-n-k-n-o-w-n/index.md) | [jvm]<br>[UNKNOWN](-u-n-k-n-o-w-n/index.md)()<br>No decision can be made about the provided object. |
| [LEAKING](-l-e-a-k-i-n-g/index.md) | [jvm]<br>[LEAKING](-l-e-a-k-i-n-g/index.md)()<br>The object was no longer needed and therefore expected to be unreachable. |
| [NOT_LEAKING](-n-o-t_-l-e-a-k-i-n-g/index.md) | [jvm]<br>[NOT_LEAKING](-n-o-t_-l-e-a-k-i-n-g/index.md)()<br>The object was needed and therefore expected to be reachable. |

## Properties

| Name | Summary |
|---|---|
| [name](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-372974862%2FProperties%2F-1562156115) | [jvm]<br>val [name](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-372974862%2FProperties%2F-1562156115): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-739389684%2FProperties%2F-1562156115) | [jvm]<br>val [ordinal](../../-on-analysis-progress-listener/-step/-p-a-r-s-i-n-g_-h-e-a-p_-d-u-m-p/index.md#-739389684%2FProperties%2F-1562156115): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
