//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[ObjectWatcher](index.md)/[hasRetainedObjects](has-retained-objects.md)

# hasRetainedObjects

[jvm]\

@get:[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)

val [hasRetainedObjects](has-retained-objects.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if there are watched objects that aren't weakly reachable, and have been watched for long enough to be considered retained.
