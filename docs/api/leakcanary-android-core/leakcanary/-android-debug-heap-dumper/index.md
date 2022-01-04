//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[AndroidDebugHeapDumper](index.md)

# AndroidDebugHeapDumper

[androidJvm]\
object [AndroidDebugHeapDumper](index.md) : [HeapDumper](../-heap-dumper/index.md)

Dumps the Android heap using [Debug.dumpHprofData](https://developer.android.com/reference/kotlin/android/os/Debug.html#dumphprofdata).

Note: despite being part of the Debug class, [Debug.dumpHprofData](https://developer.android.com/reference/kotlin/android/os/Debug.html#dumphprofdata) can be called from non debuggable non profileable builds.

## Functions

| Name | Summary |
|---|---|
| [dumpHeap](dump-heap.md) | [androidJvm]<br>open override fun [dumpHeap](dump-heap.md)(heapDumpFile: [File](https://developer.android.com/reference/kotlin/java/io/File.html))<br>Dumps the heap. The implementation is expected to be blocking until the heap is dumped or heap dumping failed. |
