//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[ObjectWatcher](index.md)/[retainedObjectCount](retained-object-count.md)

# retainedObjectCount

[jvm]\

@get:[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)

val [retainedObjectCount](retained-object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

Returns the number of retained objects, ie the number of watched objects that aren't weakly reachable, and have been watched for long enough to be considered retained.
