//[shark](../../../index.md)/[shark](../index.md)/[LeakTrace](index.md)/[referencePathElementIsSuspect](reference-path-element-is-suspect.md)

# referencePathElementIsSuspect

[jvm]\
fun [referencePathElementIsSuspect](reference-path-element-is-suspect.md)(index: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if the [referencePath](reference-path.md) element at the provided [index](reference-path-element-is-suspect.md) contains a reference that is suspected to cause the leak, ie if [index](reference-path-element-is-suspect.md) is greater than or equal to the index of the [LeakTraceReference](../-leak-trace-reference/index.md) of the last non leaking object and strictly lower than the index of the [LeakTraceReference](../-leak-trace-reference/index.md) of the first leaking object.
