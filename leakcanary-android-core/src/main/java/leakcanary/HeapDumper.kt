package leakcanary

import java.io.File

fun interface HeapDumper {

  sealed class DumpLocation {
    object Unspecified : DumpLocation()
    class FileLocation(val file: File) : DumpLocation()
  }

  sealed class Result {
    class Failure(val exception: Throwable) : Result()
    class HeapDump(
      val file: File,
      val durationMillis: Long
    ) : Result()
  }

  /**
   * Dumps the heap. The implementation is expected to be blocking until the heap is dumped
   * or heap dumping failed.
   *
   * The [HeapDumper] interface is designed for delegation.
   *
   * [dumpLocation] may be [DumpLocation.Unspecified] or [DumpLocation.FileLocation], and
   * it's ok for implementations to know how to deal with one or the other (or both) and otherwise
   * return a [Result.Failure].
   *
   * @return [Result.HeapDump] if dumping the heap succeeded, and [Result.Failure] otherwise.
   */
  fun dumpHeap(dumpLocation: DumpLocation): Result
}
