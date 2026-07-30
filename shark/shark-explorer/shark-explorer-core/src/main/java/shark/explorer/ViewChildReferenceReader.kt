package shark.explorer

import androidx.collection.LongSet
import androidx.collection.MutableLongSet
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.ValueHolder

/**
 * A `ViewGroup`'s children, as references from the parent straight to each child, the way the framework's
 * API hands them out — `getChildAt(i)` — rather than the way it stores them.
 *
 * It stores them in a `View[]` it grows in chunks, so every parent to child link of every hierarchy in a
 * heap dump really goes through an array, and two things follow from reading it that way. A path spells
 * out `DecorView.mChildren → View[] → [0] → LinearLayout`, four objects to say what `[0]` says on its
 * own, and a view hierarchy comes out twice as deep as it is. And it's the array that gets to own a view
 * rather than its parent — see [OwnerReferences] — which puts an unnamed `View[]` between every two
 * levels of the tree, and can't tell a `ViewGroup`'s children from an app's own `View[]` of views it
 * merely points at.
 *
 * So this adds one virtual reference per child, in the shape Shark gives the collections it flattens:
 * named by index, an array entry, marked virtual. **Additive** — the array is still reached through
 * `mChildren` and is still a node holding its own bytes, because the explorer needs every object of a
 * heap dump to be a node exactly once (see [ReferenceStrengthReader]). What takes the array back out of
 * the middle of the tree is the dominator tree rather than this: both ways to a child now start at the
 * parent, so the parent dominates it and the array is left retaining nothing but itself.
 */
internal class ViewChildReferenceReader(private val graph: HeapGraph) {

  /**
   * The class ids of `ViewGroup` and of every subclass of it, resolved once. An instance's class comes
   * from the heap dump index, so this turns "is this a ViewGroup" into a hash lookup — where a class
   * hierarchy walk per object of a heap dump costs minutes, and reading a field to find out costs the
   * object's record.
   */
  private val viewGroupClassIds: LongSet by lazy {
    val classIds = MutableLongSet()
    graph.findClassByName(VIEW_GROUP_CLASS_NAME)?.let { viewGroupClass ->
      classIds += viewGroupClass.objectId
      viewGroupClass.subclasses.forEach { classIds += it.objectId }
    }
    classIds
  }

  /** The children of [source] when it's a `ViewGroup`, and nothing at all for anything else. */
  fun childReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapInstance || !viewGroupClassIds.contains(source.instanceClassId)) {
      return emptySequence()
    }
    val children = source[VIEW_GROUP_CLASS_NAME, CHILDREN_FIELD_NAME]?.valueAsObjectArray
      ?: return emptySequence()
    val elementIds = children.readRecord().elementIds
    // Bounded by the count the framework keeps rather than by the length of the array, which is a
    // capacity it grows in chunks of twelve. `ViewGroup.removeFromArray` nulls the slot it gives up, so
    // the tail is null anyway and the bound looks like belt and braces — but a slot the framework doesn't
    // count is not a child, and calling it one attributes a view its parent removed to that parent, which
    // is exactly the leak you'd be looking for. A heap dump also catches threads mid-method, and
    // `addViewInner` fills the slot before it counts it.
    //
    // ViewGroup declares both fields, so the count is there whenever the array is; a dump that somehow
    // says otherwise falls back on the length, since the tail is nulled.
    val childCount = source[VIEW_GROUP_CLASS_NAME, CHILD_COUNT_FIELD_NAME]?.value?.asInt
      ?.coerceIn(0, elementIds.size)
      ?: elementIds.size
    val parentClassId = source.instanceClassId
    return elementIds.asSequence()
      .take(childCount)
      .withIndex()
      .mapNotNull { (index, childObjectId) ->
        if (childObjectId == ValueHolder.NULL_REFERENCE) {
          null
        } else {
          Reference(
            valueObjectId = childObjectId,
            isLowPriority = false,
            lazyDetailsResolver = {
              LazyDetails(
                name = "$index",
                // The parent's own class, so that a path reads CheckoutGridTile[0]: there is no field
                // here whose declaring class could stand in for it.
                locationClassObjectId = parentClassId,
                locationType = ARRAY_ENTRY,
                isVirtual = true,
                matchedLibraryLeak = null
              )
            }
          )
        }
      }
  }

  companion object {
    /** Also what an [OwnerRule] names to say that a parent owns the children read here. */
    const val VIEW_GROUP_CLASS_NAME = "android.view.ViewGroup"

    private const val CHILDREN_FIELD_NAME = "mChildren"

    private const val CHILD_COUNT_FIELD_NAME = "mChildrenCount"
  }
}
