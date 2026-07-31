package shark

/**
 * Where the reference that a [ShortestPathObjectNode] was reached through is declared.
 *
 * [ShortestPathObjectNode.name] already spells this out, but as a display string that also folds in
 * the class name of the referenced object and drops the package of the owning class. Anything that
 * needs to act on the reference rather than print it — set a field watchpoint, install a JVMTI field
 * modification watch, look the field up by reflection, jump to the declaration in an IDE — needs the
 * owning class and the field name as data, which is what this carries.
 */
data class ReferenceLocation(
  val locationType: ReferenceLocationType,

  /**
   * Fully qualified name of the class that declares the reference, e.g. `com.example.Retainer`.
   * This is the class the reference is declared in, which for an inherited field is a superclass of
   * the class of the object the reference was read from.
   */
  val owningClassName: String,

  /**
   * The field name, for [ReferenceLocationType.INSTANCE_FIELD] and
   * [ReferenceLocationType.STATIC_FIELD].
   *
   * A node groups together every reference that reaches it the same way, so for
   * [ReferenceLocationType.ARRAY_ENTRY] this is the name of one of the grouped entries — an array
   * index, or a map key for the virtual references that expand a collection — and not a name that
   * identifies the node. For [ReferenceLocationType.LOCAL] this is whatever the reference reader
   * that produced the reference named the local.
   */
  val referenceName: String,
)
