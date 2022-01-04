//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[HeapDumper](index.md)/[dumpHeap](dump-heap.md)

# dumpHeap

[androidJvm]\
abstract fun [dumpHeap](dump-heap.md)(heapDumpFile: [File](https://developer.android.com/reference/kotlin/java/io/File.html))

Dumps the heap. The implementation is expected to be blocking until the heap is dumped or heap dumping failed.

Implementations can throw a runtime exception if heap dumping failed.
