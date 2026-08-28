package shark.dive

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.ValueHolder

/**
 * A Compose `LayoutNode`'s children, as references from the parent straight to each child — the same
 * thing [ViewChildReferenceReader] does for a `ViewGroup`, and for the same reasons.
 *
 * A node keeps its children in a growable vector with a mutation callback around it, so the parent to
 * child link of a Compose UI really goes `LayoutNode` → `MutableVectorWithMutationTracking` →
 * `MutableVector` → `LayoutNode[]` → child: four objects, three of them unnamed bookkeeping, between
 * every two levels of a UI. Read that way a Compose hierarchy comes out four times as deep as it is, and
 * it's an array that gets to own each node rather than its parent — see [OwnerReferences].
 *
 * So this adds one virtual reference per child, in the shape Shark gives the collections it flattens.
 * **Additive**: the vector and its array are still reached through `_foldedChildren` and are still nodes
 * holding their own bytes, and both ways to a child now start at the parent, so the parent dominates it.
 */
internal class LayoutNodeChildReferenceReader(private val graph: HeapGraph) {

  /**
   * `LayoutNode` is final, so this is an id to compare against rather than a hierarchy to walk.
   * [HeapGraph.findClassByName] scans every string of the heap dump, hence once.
   */
  private val layoutNodeClassId: Long by lazy {
    graph.findClassByName(LAYOUT_NODE_CLASS_NAME)?.objectId ?: ValueHolder.NULL_REFERENCE
  }

  /** The children of [source] when it's a `LayoutNode`, and nothing at all for anything else. */
  fun childReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapInstance || source.instanceClassId != layoutNodeClassId) {
      return emptySequence()
    }
    // Fields read by name against the whole instance rather than by their declaring class: which class
    // holds the children has changed with Compose versions, and the vector was once the field's own value
    // rather than something wrapped in a tracker, which reads here as a tracker without a vector in it.
    val children = source.fieldNamed(FOLDED_CHILDREN_FIELD_NAME)?.valueAsInstance
      ?: return emptySequence()
    val vector = children.fieldNamed(VECTOR_FIELD_NAME)?.valueAsInstance ?: children
    val content = vector.fieldNamed(CONTENT_FIELD_NAME)?.valueAsObjectArray ?: return emptySequence()
    val elementIds = content.readRecord().elementIds
    // Bounded by the size the vector keeps rather than by the length of the array it grows in powers of
    // two, for the reason [ViewChildReferenceReader] gives: a slot past the size is not a child, and
    // calling it one attributes a node its parent let go of to that parent.
    val childCount = vector.fieldNamed(SIZE_FIELD_NAME)?.value?.asInt
      ?.coerceIn(0, elementIds.size)
      ?: elementIds.size
    val parentClassId = source.instanceClassId
    return sequence {
      for (index in 0 until childCount) {
        val childObjectId = elementIds[index]
        if (childObjectId != ValueHolder.NULL_REFERENCE && graph.objectExists(childObjectId)) {
          yield(
            Reference(
              valueObjectId = childObjectId,
              isLowPriority = false,
              lazyDetailsResolver = {
                LazyDetails(
                  name = "$index",
                  locationClassObjectId = parentClassId,
                  locationType = ARRAY_ENTRY,
                  isVirtual = true,
                  matchedLibraryLeak = null
                )
              }
            )
          )
        }
      }
    }
  }

  companion object {
    /** Also what an [OwnerRule] names to say that a parent owns the children read here. */
    const val LAYOUT_NODE_CLASS_NAME = "androidx.compose.ui.node.LayoutNode"

    private const val FOLDED_CHILDREN_FIELD_NAME = "_foldedChildren"

    private const val VECTOR_FIELD_NAME = "vector"

    private const val CONTENT_FIELD_NAME = "content"

    private const val SIZE_FIELD_NAME = "size"

    private fun HeapInstance.fieldNamed(fieldName: String) =
      readFields().firstOrNull { it.name == fieldName }
  }
}
