package shark.benchmark

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Allocates [args]\[1] reachable objects in a real JVM and dumps its heap to [args]\[0] via the JVM
 * heap dump API, producing a genuine hprof (not a hand-crafted one). With enough small objects the
 * resulting instance index exceeds the 2 GB single-array limit, reproducing the
 * NegativeArraySizeException case from issue #2807.
 *
 * Run via the :generateBigHeapDump Gradle task.
 */
fun main(args: Array<String>) {
  val path = args[0]
  val count = args[1].toInt()

  File(path).apply {
    parentFile?.mkdirs()
    if (exists()) delete()
  }

  println("Allocating $count objects...")
  val tAlloc = System.nanoTime()
  val holder = arrayOfNulls<Any>(count)
  var i = 0
  while (i < count) {
    holder[i] = Any()
    i++
  }
  // Touch a sample so the allocation can't be optimized away.
  var sink = 0
  var j = 0
  val stride = (count / 10).coerceAtLeast(1)
  while (j < count) {
    sink += System.identityHashCode(holder[j])
    j += stride
  }
  println("Allocated in ${(System.nanoTime() - tAlloc) / 1_000_000} ms (sink=$sink)")

  val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
  val tDump = System.nanoTime()
  bean.dumpHeap(path, true)
  val sizeMb = File(path).length() / 1024 / 1024
  println("Dumped $sizeMb MB in ${(System.nanoTime() - tDump) / 1_000_000} ms to $path")

  // Keep holder reachable until after the dump.
  if (sink == Int.MIN_VALUE) println(holder.size)
}
