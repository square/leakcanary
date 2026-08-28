package shark.dive

/**
 * Which kind of thing an object is, which is both what a list of objects filters on and what a path
 * prints after a class name.
 */
enum class HeapObjectKind(
  /** Plural, for the checkbox that filters a list down to this kind. */
  val displayName: String,
  /** Singular, for the line naming one object: `com.example.Tile instance`. */
  val typeName: String
) {
  CLASS("Classes", "class"),
  INSTANCE("Instances", "instance"),
  OBJECT_ARRAY("Object arrays", "array"),
  PRIMITIVE_ARRAY("Primitive arrays", "array")
}

/**
 * Which objects of a heap dump a list shows. See [HeapDominatorTreemap.listObjects].
 *
 * The default matches everything: a heap dump is worth scrolling through before it's worth searching.
 */
data class ObjectListFilter(
  /** Matched against the class name. Empty matches every object. */
  val query: String = "",
  /**
   * Whether [query] has to be the whole class name rather than part of it.
   *
   * Off by default, so that a few characters find every class whose name contains them, which is how
   * anyone types a class name they only half remember. On, the query has to be either the whole name or
   * the whole simple name — `android.graphics.Bitmap` or `Bitmap`, and neither `Bit` nor `BitmapDrawable`
   * — so that listing the instances of one class can't turn up another class's.
   */
  val isExactMatch: Boolean = false,
  /** Object kinds to show, which is all of them until one is unchecked. */
  val kinds: Set<HeapObjectKind> = HeapObjectKind.values().toSet()
) {

  fun matches(
    className: String,
    kind: HeapObjectKind
  ): Boolean = kind in kinds && matchesQuery(className)

  private fun matchesQuery(className: String): Boolean = when {
    query.isEmpty() -> true
    isExactMatch -> className.equals(query, ignoreCase = true) ||
      className.substringAfterLast('.').equals(query, ignoreCase = true)
    else -> className.contains(query, ignoreCase = true)
  }
}

/** One row of a list of objects. See [HeapDominatorTreemap.listObjects]. */
data class ObjectListEntry(
  val objectId: Long,
  /** Fully qualified class name, or array type. */
  val className: String,
  val kind: HeapObjectKind,
  /**
   * What the details panel would headline it with — a string's content, a bitmap's size, an array's
   * length — or null for an object that has none. What tells two instances of a class apart in a list of
   * them.
   */
  val headline: String?,
  val shallowSize: Long,
  /** The same number the treemap draws this object's rectangle from. */
  val retainedSize: Long,
  val strength: ReachabilityStrength
)

/**
 * Every object a filter matches, largest first, capped. See [HeapDominatorTreemap.listObjects].
 */
data class ObjectList(
  val filter: ObjectListFilter,
  /** Largest retained size first, at most as many as were asked for. */
  val entries: List<ObjectListEntry>,
  /** How many objects the filter matches, which is more than [entries] holds once it caps. */
  val matchCount: Int,
  /** How many objects the heap dump holds, so that a match count reads as a share of something. */
  val totalCount: Int
) {

  /** Whether the cap left matches out, which a list has to say rather than look complete. */
  val hasMore: Boolean get() = entries.size < matchCount

  companion object {
    /** Nothing listed yet, which is what a screen shows while the pass over the heap dump runs. */
    val EMPTY = ObjectList(
      filter = ObjectListFilter(),
      entries = emptyList(),
      matchCount = 0,
      totalCount = 0
    )
  }
}
