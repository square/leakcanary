@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.internal.hppc.LongScatterSet

class FlatteningFiniteTraversalReferenceReader(
  private val graph: HeapGraph,
  private val virtualInstanceReader: VirtualInstanceReferenceReader,
  private val instanceReferenceReader: ReferenceReader<HeapInstance>,
  private val objectArrayReferenceReader: ReferenceReader<HeapObjectArray>,
) : VirtualInstanceReferenceReader {

  private val visited = LongScatterSet()

  override fun matches(instance: HeapInstance) = virtualInstanceReader.matches(instance)

  override fun read(source: HeapInstance): Sequence<Reference> {
    visited.clear()
    val toVisit = mutableListOf<Reference>()
    visited += source.objectId

    val sourceTrackingSequence = virtualInstanceReader.read(source).map { reference ->
      visited += reference.valueObjectId
      reference
    }
    var startedTraversing = false

    val traversingSequence = generateSequence {
      if (!startedTraversing) {
        startedTraversing = true
        toVisit.enqueueNewReferenceVisit(instanceReferenceReader.read(source), visited)
      }
      val nextReference = toVisit.removeFirstOrNull() ?: return@generateSequence null

      val childReferences =
        when (val nextObject = graph.findObjectById(nextReference.valueObjectId)) {
          is HeapInstance -> instanceReferenceReader.read(nextObject)
          is HeapObjectArray -> objectArrayReferenceReader.read(nextObject)
          // We're assuming that classes should be reached through other nodes. Reaching a class
          // here first would be bad as it opens us up to traversing the entire graph, vs the local
          // finite traversal we want. This should be fine on Android, but could be different on
          // JVMs.
          is HeapClass -> emptySequence()
          is HeapPrimitiveArray -> emptySequence()
        }
      // TODO Can we change the name to capture the parent relationship as well?
      toVisit.enqueueNewReferenceVisit(childReferences, visited)
      nextReference.copy(isLeafObject = true)
    }
    return sourceTrackingSequence + traversingSequence
  }

  private fun MutableList<Reference>.enqueueNewReferenceVisit(
    references: Sequence<Reference>,
    visited: LongScatterSet
  ) {
    references.forEach { reference ->
      val added = visited.add(reference.valueObjectId)
      if (added) {
        this += reference
      }
    }
  }
}
