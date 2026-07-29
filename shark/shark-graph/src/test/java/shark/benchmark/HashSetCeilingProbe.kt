package shark.benchmark

import shark.internal.hppc.LongScatterSet

/**
 * Fills a [LongScatterSet] the way `PrioritizingShortestPathFinder` fills its visited set — sized
 * from half the instance count, then one distinct object id per visited object — to find the point
 * at which it stops accepting ids.
 *
 * Analysis scaffolding for issue #2777: the reporter's heap dump holds more objects than this.
 */
fun main(args: Array<String>) {
  val expectedElements = args.getOrElse(0) { "459305079" }.toInt()
  println("LongScatterSet(expectedElements = $expectedElements)")
  val set = LongScatterSet(expectedElements)

  var id = 1L
  var added = 0L
  val start = System.nanoTime()
  while (true) {
    try {
      set.add(id)
    } catch (e: RuntimeException) {
      println("threw after $added adds (set.size() = ${set.size()})")
      println("${e::class.java.name}: ${e.message}")
      println("elapsed ${(System.nanoTime() - start) / 1_000_000} ms")
      return
    }
    added++
    // Object ids in a heap dump are addresses: distinct and 8 byte aligned.
    id += 8
    if (added % 100_000_000L == 0L) {
      println("  $added adds, ${(System.nanoTime() - start) / 1_000_000} ms")
    }
  }
}
