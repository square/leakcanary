package shark.internal

import shark.GcRoot
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.LeakTrace
import shark.LeakTrace.GcRootType
import shark.LeakTraceObject
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.LeakTraceObject.ObjectType.ARRAY
import shark.LeakTraceObject.ObjectType.CLASS
import shark.LeakTraceObject.ObjectType.INSTANCE
import shark.LeakTraceReference
import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.LeakTraceReference.ReferenceType.INSTANCE_FIELD
import shark.LeakTraceReference.ReferenceType.LOCAL
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD
import shark.LibraryLeakReferenceMatcher
import shark.Reference.LazyDetails
import shark.ReferenceLocationType
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode

sealed class ReferencePathNode {
  abstract val objectId: Long

  sealed class RootNode : ReferencePathNode() {
    abstract val gcRoot: GcRoot
    override val objectId: Long
      get() = gcRoot.id

    class LibraryLeakRootNode(
      override val gcRoot: GcRoot,
      val matcher: LibraryLeakReferenceMatcher
    ) : RootNode()

    class NormalRootNode(
      override val gcRoot: GcRoot
    ) : RootNode()
  }

  class ChildNode(
    override val objectId: Long,
    /**
     * The reference from the parent to this node
     */
    val parent: ReferencePathNode,
    val lazyDetailsResolver: LazyDetails.Resolver,
  ) : ReferencePathNode()
}

fun HeapGraph.invalidObjectIdErrorMessage(node: ReferencePathNode): String {
  // This should never happen (a heap should only have references to objects that exist)
  // but when it does happen, let's at least display how we got there.
  return when (node) {
    is ChildNode -> {
      val childPath = mutableListOf<ChildNode>()
      var iteratingNode = node
      while (iteratingNode is ChildNode) {
        childPath.add(0, iteratingNode)
        iteratingNode = iteratingNode.parent
      }
      val rootNode = iteratingNode as RootNode
      val childPathWithDetails = childPath.map { it to it.lazyDetailsResolver.resolve() }
      val leakTraceObjects = (listOf(rootNode) + childPath.dropLast(1)).map {
        val heapObject = findObjectById(it.objectId)
        val className = when (heapObject) {
          is HeapClass -> heapObject.name
          is HeapInstance -> heapObject.instanceClassName
          is HeapObjectArray -> heapObject.arrayClassName
          is HeapPrimitiveArray -> heapObject.arrayClassName
        }
        val objectType = when (heapObject) {
          is HeapClass -> CLASS
          is HeapObjectArray, is HeapPrimitiveArray -> ARRAY
          else -> INSTANCE
        }
        LeakTraceObject(
          type = objectType,
          className = className,
          labels = emptySet(),
          leakingStatus = UNKNOWN,
          leakingStatusReason = "",
          retainedHeapByteSize = null,
          retainedObjectCount = null
        )
      } + LeakTraceObject(
        type = INSTANCE,
        className = "UnknownObject${node.objectId}",
        labels = emptySet(),
        leakingStatus = UNKNOWN,
        leakingStatusReason = "",
        retainedHeapByteSize = null,
        retainedObjectCount = null
      )
      val referencePath = childPathWithDetails.mapIndexed { index, (_, details) ->
        LeakTraceReference(
          originObject = leakTraceObjects[index],
          referenceType = when (details.locationType) {
            ReferenceLocationType.INSTANCE_FIELD -> INSTANCE_FIELD
            ReferenceLocationType.STATIC_FIELD -> STATIC_FIELD
            ReferenceLocationType.LOCAL -> LOCAL
            ReferenceLocationType.ARRAY_ENTRY -> ARRAY_ENTRY
          },
          owningClassName = findObjectById(details.locationClassObjectId).asClass!!.name,
          referenceName = details.name
        )
      }
      val leakTrace = LeakTrace(
        gcRootType = GcRootType.fromGcRoot(rootNode.gcRoot),
        referencePath = referencePath,
        leakingObject = leakTraceObjects.last()
      )
      return "Invalid object id reached through path:\n${leakTrace.toSimplePathString()}"
    }

    is RootNode -> {
      val rootType = GcRootType.fromGcRoot(node.gcRoot)
      "Invalid object id for root ${rootType.name}"
    }
  }
}
