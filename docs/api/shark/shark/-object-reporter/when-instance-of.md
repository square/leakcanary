//[shark](../../../index.md)/[shark](../index.md)/[ObjectReporter](index.md)/[whenInstanceOf](when-instance-of.md)

# whenInstanceOf

[jvm]\
fun [whenInstanceOf](when-instance-of.md)(expectedClass: [KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;out [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt;, block: [ObjectReporter](index.md).(HeapObject.HeapInstance) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html))

Runs [block](when-instance-of.md) if [ObjectReporter.heapObject](heap-object.md) is an instance of [expectedClass](when-instance-of.md).

[jvm]\
fun [whenInstanceOf](when-instance-of.md)(expectedClassName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), block: [ObjectReporter](index.md).(HeapObject.HeapInstance) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html))

Runs [block](when-instance-of.md) if [ObjectReporter.heapObject](heap-object.md) is an instance of [expectedClassName](when-instance-of.md).
