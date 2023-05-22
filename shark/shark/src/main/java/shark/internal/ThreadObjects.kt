package shark.internal

import shark.GcRoot.ThreadObject
import shark.HeapGraph

internal object ThreadObjects {

  private fun getThreadObjectsByIdMap(graph: HeapGraph) = graph.context.getOrPut(ThreadObjects::class.java.name) {
    graph.gcRoots.asSequence().filterIsInstance<ThreadObject>().associateBy { it.id }
  }

  fun getThreadObjects(graph: HeapGraph) = getThreadObjectsByIdMap(graph).values

  fun getByThreadObjectId(graph: HeapGraph, objectId: Long): ThreadObject? {
    val threadObjectsById = getThreadObjectsByIdMap(graph)
    return threadObjectsById[objectId]
  }
}
