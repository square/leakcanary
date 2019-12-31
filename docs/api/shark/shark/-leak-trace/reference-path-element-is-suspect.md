[shark](../../index.md) / [shark](../index.md) / [LeakTrace](index.md) / [referencePathElementIsSuspect](./reference-path-element-is-suspect.md)

# referencePathElementIsSuspect

`fun referencePathElementIsSuspect(index: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if the [referencePath](reference-path.md) element at the provided [index](reference-path-element-is-suspect.md#shark.LeakTrace$referencePathElementIsSuspect(kotlin.Int)/index) contains a reference
that is suspected to cause the leak, ie if [index](reference-path-element-is-suspect.md#shark.LeakTrace$referencePathElementIsSuspect(kotlin.Int)/index) is greater than or equal to the index
of the [LeakTraceReference](../-leak-trace-reference/index.md) of the last non leaking object and strictly lower than the index
of the [LeakTraceReference](../-leak-trace-reference/index.md) of the first leaking object.

