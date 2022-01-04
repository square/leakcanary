package shark.internal

import shark.GcRoot.JavaFrame
import shark.HeapGraph

internal object JavaFrames {

  private fun getJavaFramesByThreadSerialNumber(graph: HeapGraph) =
    graph.context.getOrPut(JavaFrames::class.java.name) {
      graph.gcRoots.asSequence().filterIsInstance<JavaFrame>().groupBy { javaFrame ->
        javaFrame.threadSerialNumber
      }
    }

  fun getByThreadObjectId(graph: HeapGraph, threadObjectId: Long): List<JavaFrame>? {
    val threadObject = ThreadObjects.getByThreadObjectId(graph, threadObjectId) ?: return null
    val javaFrameByThreadSerial = getJavaFramesByThreadSerialNumber(graph)
    return javaFrameByThreadSerial[threadObject.threadSerialNumber]
  }
}
