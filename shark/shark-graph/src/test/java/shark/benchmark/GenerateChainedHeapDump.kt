package shark.benchmark

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Allocates [args]\[1] reachable objects in a real JVM, split across a *chain* of arrays of
 * [args]\[2] elements each, and dumps the heap via the JVM heap dump API.
 *
 * The chain shape matters: a breadth first traversal of one wide array holds a path node per
 * element of the widest level, which for a billion objects is tens of GB of nodes on its own. A
 * chain keeps the frontier one array wide, so the memory the traversal needs is dominated by the
 * one structure this probe is about — the set of visited object ids.
 *
 * Analysis scaffolding for issue #2777. Run via the :generateChainedHeapDump Gradle task.
 */
fun main(args: Array<String>) {
  val path = args[0]
  val count = args[1].toLong()
  val chunkSize = args[2].toInt()

  File(path).apply {
    parentFile?.mkdirs()
    if (exists()) delete()
  }

  val chunkCount = (count / chunkSize).toInt()
  println("Allocating ${chunkCount.toLong() * chunkSize} objects in $chunkCount chained arrays " +
    "of $chunkSize...")
  val tAlloc = System.nanoTime()
  var previous: Array<Any?>? = null
  var chunk = 0
  while (chunk < chunkCount) {
    // Slot 0 links to the previous array, the rest hold the leaves of this level.
    val array = arrayOfNulls<Any>(chunkSize + 1)
    array[0] = previous
    var i = 1
    while (i <= chunkSize) {
      array[i] = Any()
      i++
    }
    previous = array
    chunk++
  }
  val root = previous!!
  println("Allocated in ${(System.nanoTime() - tAlloc) / 1_000_000} ms " +
    "(root holds ${System.identityHashCode(root[1])})")

  val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
  val tDump = System.nanoTime()
  bean.dumpHeap(path, true)
  println("Dumped ${File(path).length() / 1024 / 1024} MB in " +
    "${(System.nanoTime() - tDump) / 1_000_000} ms to $path")

  // Keep the chain reachable until after the dump.
  if (root.size == Int.MIN_VALUE) println(root)
}
