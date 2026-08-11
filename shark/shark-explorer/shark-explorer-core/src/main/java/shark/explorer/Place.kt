package shark.explorer

/**
 * One thing the explorer can show, which is the whole of where a tab is.
 *
 * An object is the place this app is mostly about, and its screen answers three questions at once: what
 * holds it, which is the chain on one side; what it is, which is the panel on the other; and what it
 * holds, which is the dominator tree drawn between them, **rooted at the object itself**. The lists are
 * places too, so that going to one and coming back is the same move as any other.
 *
 * Everything the window draws follows from one of these, which is the point of it being a value: where a
 * tab is and what its panes describe cannot disagree, because they are the same thing. The map's root is
 * [viewRootObjectId] rather than a path stored beside the object — in a dominator tree the way down to a
 * node is unique, so a path is that object said twice.
 *
 * Immutable, and in this module rather than in the UI so that navigation stays unit testable. See [Tabs].
 */
sealed interface Place {

  /**
   * What a tab showing this is called, or null for a place only the heap dump can name.
   *
   * See [HeapDominatorTreemap.titleOf], which is the one that always answers.
   */
  val title: String?

  /**
   * The object the middle view is rooted at, or null for a place that is a list the width of the window.
   */
  val viewRootObjectId: Long?

  /** One object of the heap dump, which is also a class pile and the whole heap dump itself. */
  data class Object(val objectId: Long) : Place {

    // Which class, and which instance of it, is a read of the heap dump.
    override val title: String? get() = null

    override val viewRootObjectId: Long get() = objectId
  }

  /**
   * The children of [parentObjectId] that its rectangle had no room to draw, as the map draws them: one
   * cell standing for all of them.
   *
   * No node of the tree, so there is nothing to root a view at — the map stays on the object they were
   * left out of, which is where they are, and the panel describes the pile. Which is why this is a place
   * of its own rather than an [Object]: the two differ in what is described, not in what is drawn.
   */
  data class SmallerObjects(
    val parentObjectId: Long,
    val nodeCount: Int,
    val byteCount: Long
  ) : Place {

    override val title: String get() = "$nodeCount smaller objects"

    override val viewRootObjectId: Long get() = parentObjectId
  }

  /** Every object of the heap dump as a list, filtered. See [HeapDominatorTreemap.listObjects]. */
  data class Objects(val filter: ObjectListFilter = ObjectListFilter()) : Place {

    // Named after what it is filtered to, so that two of these open at once are two different lists on
    // the tab strip rather than the same word twice.
    override val title: String
      get() = if (filter.query.isEmpty()) OBJECTS_LABEL else "$OBJECTS_LABEL: ${filter.query}"

    override val viewRootObjectId: Long? get() = null
  }

  /** Every leaking object of the heap dump, gathered into leaks. See [HeapDominatorTreemap.findLeaks]. */
  data class Leaks(
    /**
     * Which leaks have been unfolded to show the objects in them, by [LeakGroup.leakFingerprint] and which
     * section the group is in: a leak of one class held two ways is two groups with one title.
     *
     * Plus [ON_THE_WAY_OUT] when the sections nobody has to act on have been unfolded, which is the one
     * thing on the screen that starts folded. In the same set because it is the same kind of thing — what
     * the reader has asked to see — so it is kept in a note and carried by a link like the rest of it.
     */
    val expandedGroups: Set<String> = emptySet()
  ) : Place {

    override val title: String get() = LEAKS_LABEL

    override val viewRootObjectId: Long? get() = null

    companion object {
      /**
       * Its key in [expandedGroups]. No group's is a single word, every one of those being a section and a
       * hash, so this can't be mistaken for one.
       */
      const val ON_THE_WAY_OUT = "ON_THE_WAY_OUT"
    }
  }

  /** The objects starred so far, kept so that two of them can be compared. */
  data object Starred : Place {

    override val title: String get() = STARRED_LABEL

    override val viewRootObjectId: Long? get() = null
  }

  companion object {

    /**
     * Where a click on a cell of a laid out view goes.
     *
     * The one place a click on the map is turned into a move, so that clicking a rectangle, a step of the
     * chain and a row of a list all arrive the same way.
     */
    fun of(cell: LayoutCell<Long>): Place = when (val subject = cell.subject) {
      is CellSubject.Node -> Object(subject.node)
      // Clicking an object's own bytes is clicking that object.
      is CellSubject.Own -> Object(subject.node)
      is CellSubject.Group -> SmallerObjects(
        parentObjectId = subject.parent,
        nodeCount = subject.nodeCount,
        byteCount = cell.weight
      )
    }

    /** The place a window opens on, which is the heap dump as a whole. */
    fun wholeHeapDump(): Object = Object(HeapDominatorTreemap.ROOT_OBJECT_ID)

    /** What the button leading to the list of every object says, on the bar and on a tab. */
    const val OBJECTS_LABEL = "Object list"

    /** And the one leading to the leaks, beside it. */
    const val LEAKS_LABEL = "Leaks"

    /** And to the objects starred so far. */
    const val STARRED_LABEL = "Starred"
  }
}

/**
 * What a tab showing [place] is called, reading the heap dump for the places that need it.
 *
 * An object is named by its class and its address, which is how a tab strip of a dozen instances of one
 * class is still a tab strip you can pick out of. A class pile keeps the name the map draws on it —
 * `42 × Bitmap` — because how many instances it stands for is the whole of what it is.
 */
fun HeapDominatorTreemap.titleOf(place: Place): String = when (place) {
  is Place.Object -> {
    val objectId = place.objectId
    val label = label(objectId)
    // The whole heap dump and the piles have no address to add: one is no object, the others are many.
    if (objectId == HeapDominatorTreemap.ROOT_OBJECT_ID || groupOrNull(objectId) != null) {
      label
    } else {
      "$label ${hexObjectId(objectId)}"
    }
  }
  is Place.SmallerObjects -> place.title
  is Place.Objects -> place.title
  is Place.Leaks -> place.title
  is Place.Starred -> place.title
}

/**
 * What a tab opened at [place] is called, from the name a view already drew there, or null for a place
 * none of these cells stands for.
 *
 * The same answer [titleOf] gives, and not a heap dump read: a cell was named when the view was laid out,
 * so a tab opened by clicking one can be named as it opens instead of a beat later. A beat later is a tab
 * whose title, and with it its width, change in front of whoever opened it — which is a flicker, and every
 * cell of every view is one a click opens a tab at.
 */
fun List<PresentedCell<*>>.titleOf(place: Place): String? =
  firstOrNull { Place.of(it.cell) == place }?.title()

/**
 * What a tab open where this cell leads is called.
 *
 * A cell is labelled with a class name and nothing else, because that is all a rectangle has room for. A
 * tab has room for which instance of it this is, and needs it — see [titleOf].
 */
private fun PresentedCell<*>.title(): String {
  val place = Place.of(cell)
  // Only one object of the heap dump has an address to be named by: a pile of them is named by how many
  // it holds, and the whole heap dump is no object at all.
  return if (
    place is Place.Object &&
    content is CellContent.Object &&
    place.objectId != HeapDominatorTreemap.ROOT_OBJECT_ID
  ) {
    "$label ${hexObjectId(place.objectId)}"
  } else {
    label
  }
}
