//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[HeapDumper](index.md)

# HeapDumper

[androidJvm]\
fun interface [HeapDumper](index.md)

## Functions

| Name | Summary |
|---|---|
| [dumpHeap](dump-heap.md) | [androidJvm]<br>abstract fun [dumpHeap](dump-heap.md)(heapDumpFile: [File](https://developer.android.com/reference/kotlin/java/io/File.html))<br>Dumps the heap. The implementation is expected to be blocking until the heap is dumped or heap dumping failed. |

## Inheritors

| Name |
|---|
| [AndroidDebugHeapDumper](../-android-debug-heap-dumper/index.md) |
