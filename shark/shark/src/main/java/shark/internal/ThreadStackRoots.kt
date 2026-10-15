package shark.internal

import shark.GcRoot
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniLocal
import shark.GcRoot.NativeStack
import shark.HeapGraph

/**
 * The GC roots on the stack of a thread: a [JavaFrame] for what Java code it runs holds, a
 * [JniLocal] or a [NativeStack] for what native code holds. Each names its thread by serial number.
 */
internal object ThreadStackRoots {

  /** The serial number of the thread [gcRoot] is on the stack of, or null for any other root. */
  fun threadSerialNumberOf(gcRoot: GcRoot): Int? = when (gcRoot) {
    is JavaFrame -> gcRoot.threadSerialNumber
    is JniLocal -> gcRoot.threadSerialNumber
    is NativeStack -> gcRoot.threadSerialNumber
    else -> null
  }

  private fun getStackRootsByThreadSerialNumber(graph: HeapGraph): Map<Int, List<GcRoot>> =
    graph.context.getOrPut(ThreadStackRoots::class.java.name) {
      val stackRootsByThreadSerialNumber = mutableMapOf<Int, MutableList<GcRoot>>()
      graph.gcRoots.forEach { gcRoot ->
        val threadSerialNumber = threadSerialNumberOf(gcRoot)
        if (threadSerialNumber != null) {
          stackRootsByThreadSerialNumber.getOrPut(threadSerialNumber) { mutableListOf() } += gcRoot
        }
      }
      stackRootsByThreadSerialNumber
    }

  fun getByThreadObjectId(graph: HeapGraph, threadObjectId: Long): List<GcRoot>? {
    val threadObject = ThreadObjects.getByThreadObjectId(graph, threadObjectId) ?: return null
    val stackRootsByThreadSerialNumber = getStackRootsByThreadSerialNumber(graph)
    return stackRootsByThreadSerialNumber[threadObject.threadSerialNumber]
  }
}
