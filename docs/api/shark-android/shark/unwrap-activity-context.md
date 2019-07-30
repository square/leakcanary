[shark-android](../index.md) / [shark](index.md) / [unwrapActivityContext](./unwrap-activity-context.md)

# unwrapActivityContext

`fun HeapInstance.unwrapActivityContext(): HeapInstance?`

Recursively unwraps `this` [HeapInstance](#) as a ContextWrapper until an Activity is found in which case it is
returned. Returns null if no activity was found.

