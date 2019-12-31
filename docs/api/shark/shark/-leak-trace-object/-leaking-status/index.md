[shark](../../../index.md) / [shark](../../index.md) / [LeakTraceObject](../index.md) / [LeakingStatus](./index.md)

# LeakingStatus

`enum class LeakingStatus`

### Enum Values

| Name | Summary |
|---|---|
| [NOT_LEAKING](-n-o-t_-l-e-a-k-i-n-g.md) | The object was needed and therefore expected to be reachable. |
| [LEAKING](-l-e-a-k-i-n-g.md) | The object was no longer needed and therefore expected to be unreachable. |
| [UNKNOWN](-u-n-k-n-o-w-n.md) | No decision can be made about the provided object. |
