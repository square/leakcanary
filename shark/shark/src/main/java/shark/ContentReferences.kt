package shark

/**
 * Whether a traversal follows the references that hold an object's *content*: the array that backs a
 * `java.lang.String`, and the boxed primitives held by a `java.lang.Integer[]` and its equivalents
 * for the other primitive types.
 *
 * A leak trace never needs to name those references, and following them costs a heap dump read per
 * string, so the default is [SKIPPED]. It has to be paired with an [ObjectSizeCalculator] set to the
 * same value: skipping the reference leaves the content out of the graph, so the size calculator is
 * the one that has to account for it.
 */
enum class ContentReferences {
  /**
   * Content references are left out of the traversal, and an [ObjectSizeCalculator] credits the
   * content's size to the object that holds it.
   *
   * That is only exact while each piece of content has a single holder. Content is shared more often
   * than it looks: `new String(String)` copies the reference to the backing array rather than the
   * array, boxed primitives are cached and shared (`Integer.valueOf()` for -128 to 127,
   * `Boolean.valueOf()` always), and before Android Marshmallow `String.substring()` returned a
   * string that shared its parent's array. Shared content is then credited to every holder, so
   * anything summing those sizes counts the same bytes more than once.
   */
  SKIPPED,

  /**
   * Content references are followed like any other reference, so the content is part of the graph
   * and a traversal attributes it to whatever holds it, exactly once. An [ObjectSizeCalculator] set
   * to [FOLLOWED] then reports an object's own size and nothing else.
   */
  FOLLOWED,
}
