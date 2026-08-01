package shark.explorer

import androidx.collection.MutableLongObjectMap
import shark.ActualMatchingReferenceReaderFactory
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferenceLocationType.STATIC_FIELD
import shark.ReferenceReader
import shark.ValueHolder
import shark.explorer.ReachabilityStrength.STRONG

/**
 * A reference that doesn't keep its target alive, and the [ReachabilityStrength] a path through it
 * comes out at.
 */
internal class WeakeningReference(
  val strength: ReachabilityStrength,
  val valueObjectId: Long,
  val fieldName: String,
  private val locationClassObjectId: Long
) {

  fun toReference(): Reference = Reference(
    valueObjectId = valueObjectId,
    isLowPriority = false,
    lazyDetailsResolver = {
      LazyDetails(
        name = fieldName,
        locationClassObjectId = locationClassObjectId,
        locationType = INSTANCE_FIELD,
        matchedLibraryLeak = null,
        isVirtual = false
      )
    }
  )
}

/**
 * Splits an object's outgoing references in two: the ones that keep their target alive, and the ones
 * that don't — what a `java.lang.ref.Reference` holds its referent with, and what a cache holds its
 * entries with.
 *
 * Reference strength lives entirely in Shark's ignored reference matchers, and those only say what
 * not to follow, never why. So this reads both halves out of one list: a matched [ReferenceReader] built
 * from [ExplorerRules.weakeningReferenceMatchers] for everything that retains, and
 * [ExplorerRules.strengthByFieldNameByClassName] for everything that doesn't, with the strength coming from
 * which class declares the field.
 *
 * Deliberately uses the plain field and array readers rather than
 * [shark.AndroidReferenceReaderFactory]: the Android readers present data structures the way you
 * think about them instead of the way they're built, which is right for a leak trace but means some
 * objects only ever appear as flattened children of the structure holding them. An explorer needs
 * every object of the heap dump to be a node of the graph exactly once, so that reachability and the
 * unreachable byte count are exact.
 *
 * Where a structure is worth presenting the way you think about it anyway, the reference is **added**
 * rather than swapped in: [ViewChildReferenceReader] gives a `ViewGroup` a reference to each of its
 * children, and the `View[]` they really live in is still reached through `mChildren` and still a node of
 * its own.
 */
internal class ReferenceStrengthReader(
  private val graph: HeapGraph,
  private val rules: ExplorerRules
) {

  private val retainingReader: ReferenceReader<HeapObject> =
    ActualMatchingReferenceReaderFactory(rules.weakeningReferenceMatchers).createFor(graph)

  private val viewChildReader = ViewChildReferenceReader(graph)

  /**
   * Which fields of a class hold their value without retaining it, by class object id. Cached because
   * it takes a class hierarchy walk to work out and a heap dump has far more instances than classes.
   */
  private val weakeningFieldsByClassId = MutableLongObjectMap<WeakeningFields>()

  /**
   * The class ids of the classes whose instances have something folded into them, looked up once:
   * [HeapGraph.findClassByName] scans every string of the heap dump, so calling it per object would
   * cost minutes.
   */
  private val stringClassId: Long by lazy {
    graph.findClassByName(STRING_CLASS_NAME)?.objectId ?: NO_CLASS_ID
  }

  /** The references from [source] that keep their target alive. */
  fun retainingReferencesOf(source: HeapObject): Sequence<Reference> =
    retainingReader.read(source) + classMetadataReferencesOf(source) +
      viewChildReader.childReferencesOf(source)

  /**
   * The arrays ART hangs off a class object to hold what it embeds — its method tables in
   * `$classOverhead`, its static field storage in `$staticOverhead`.
   *
   * [shark.ClassReferenceReader] skips both, since neither can explain a leak, and no other object of
   * the heap dump points at them. On a real app dump that leaves 66 K arrays and 10.7 MB of class
   * metadata reading as uncollected garbage, which was 88% of everything the explorer called garbage.
   */
  private fun classMetadataReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapClass) {
      return emptySequence()
    }
    return source.readStaticFields()
      .filter { it.name in CLASS_METADATA_FIELD_NAMES }
      .mapNotNull { field ->
        field.value.asNonNullObjectId?.let { valueObjectId ->
          Reference(
            valueObjectId = valueObjectId,
            isLowPriority = true,
            lazyDetailsResolver = {
              LazyDetails(
                name = field.name,
                locationClassObjectId = source.objectId,
                locationType = STATIC_FIELD,
                matchedLibraryLeak = null,
                isVirtual = false
              )
            }
          )
        }
      }
  }

  /**
   * The objects whose bytes [shark.ObjectSizeCalculator] counts inside [source], because the reference
   * readers don't surface them: a string's characters, and the boxed primitives of a wrapper array.
   *
   * They're objects of the heap dump, and something does point at them, but they're no nodes of a graph
   * walked with [retainingReferencesOf] — which is why their size is added back to the object holding
   * them. So a walk has to mark them as accounted for, or they read as unreferenced garbage while their
   * bytes are counted somewhere else. Empty for anything else, which is nearly every object.
   */
  fun foldedObjectIdsOf(source: HeapObject): List<Long> = when {
    source is HeapInstance && source.instanceClassId == stringClassId ->
      listOfNotNull(source[STRING_CLASS_NAME, "value"]?.value?.asNonNullObjectId)
    source is HeapObjectArray && source.arrayClassName in WRAPPER_ARRAY_CLASS_NAMES ->
      source.readRecord().elementIds.filter { it != ValueHolder.NULL_REFERENCE }
    else -> emptyList()
  }

  /**
   * The references from [source] that don't keep their target alive: a `java.lang.ref.Reference`'s
   * `referent`, the `zombie` a `FinalizerReference` holds an object in while it's being finalized, a
   * thread's own storage, and the entries of a cache — every [WeakeningFieldRule] of [ExplorerRules], that
   * is. Empty for anything else, which is nearly every object of a heap dump.
   */
  fun weakeningReferencesOf(source: HeapObject): List<WeakeningReference> {
    if (source !is HeapInstance) {
      return emptyList()
    }
    val weakening = weakeningFieldsOf(source.instanceClass)
    if (weakening === NOTHING_WEAKENING) {
      return emptyList()
    }
    val locationClassObjectId = source.instanceClass.objectId
    return source.readFields()
      .mapNotNull { field ->
        val strength = weakening.strengthByFieldName[field.name]
        val valueObjectId = field.value.asNonNullObjectId
        if (strength == null || valueObjectId == null || !graph.objectExists(valueObjectId)) {
          null
        } else {
          WeakeningReference(strength, valueObjectId, field.name, locationClassObjectId)
        }
      }
      .toList()
  }

  private fun weakeningFieldsOf(instanceClass: HeapClass): WeakeningFields =
    weakeningFieldsByClassId.getOrPut(instanceClass.objectId) {
      // Superclass first, so that a subclass's entry for the same field name wins: a FinalizerReference
      // reads as a finalizer reference rather than as the reference class it extends. A class can weaken
      // more than one field at more than one strength, which is how a ThreadLocalMap entry holds its key
      // weakly and its value for as long as the thread lives.
      val strengthByFieldName = instanceClass.classHierarchy
        .toList()
        .asReversed()
        .fold(emptyMap<String, ReachabilityStrength>()) { inherited, heapClass ->
          rules.strengthByFieldNameByClassName[heapClass.name]?.let { inherited + it } ?: inherited
        }
      if (strengthByFieldName.isEmpty()) NOTHING_WEAKENING else WeakeningFields(strengthByFieldName)
    }

  /** Which of a class's fields hold their value without retaining it, and how weakly each does. */
  private class WeakeningFields(val strengthByFieldName: Map<String, ReachabilityStrength>)

  companion object {
    private const val STRING_CLASS_NAME = "java.lang.String"

    /** No heap object has id 0, which is what a null reference is. */
    private const val NO_CLASS_ID = ValueHolder.NULL_REFERENCE

    /** See [classMetadataReferencesOf]. Named with a `$` so that no Java class can declare them. */
    private val CLASS_METADATA_FIELD_NAMES = setOf("\$classOverhead", "\$staticOverhead")

    /**
     * The arrays whose elements [shark.ObjectArrayReferenceReader] skips, and whose size
     * [shark.ObjectSizeCalculator] therefore folds into the array. Shark keeps the same list to itself.
     */
    private val WRAPPER_ARRAY_CLASS_NAMES = setOf(
      "java.lang.Boolean[]",
      "java.lang.Byte[]",
      "java.lang.Character[]",
      "java.lang.Short[]",
      "java.lang.Integer[]",
      "java.lang.Long[]",
      "java.lang.Float[]",
      "java.lang.Double[]"
    )

    /** For the classes that retain everything they point at, cached like the rest. */
    private val NOTHING_WEAKENING = WeakeningFields(emptyMap())
  }
}

/**
 * Reads the references that retain their target, plus the ones that hold an object without retaining it
 * — a `java.lang.ref.Reference`'s referent, a cache's entries, a thread local — when such a reference is
 * the strongest thing reaching the object.
 *
 * That's what puts a weakly reachable object in the tree, dominated by the weak reference itself, and a
 * cached one under its cache entry: every object of the heap dump is a node, and every one of them is
 * held by whatever the garbage collector would have to let go of first.
 *
 * **The target's strength decides, not the reference's.** Following a weak reference to an object that
 * something else holds strongly wouldn't reveal anything — the object is already in the tree — but it
 * would add an edge, which moves the object's retained size up to whatever dominates both paths and
 * attributes it to neither. Which is exactly what a weak reference isn't: it holds nothing.
 *
 * **And a path stays as weak as its weakest reference.** An object held only by a finalizer queue, a
 * thread local or a stack frame doesn't hold what something firmer holds either, so every reference out of
 * it is weighed the same way. Without that, a `FinalizerReference` two steps up a chain would be one more
 * way of holding an object that a field holds squarely, which is a way of holding nothing.
 *
 * Drops the references that lose to an owner for the same reason, and it's the same rule read off a
 * different property of the reference: see [OwnerReferences].
 */
internal class WeakeningAwareReferenceReader(
  private val strengthReader: ReferenceStrengthReader,
  private val reachability: HeapReachability,
  private val ownerReferences: OwnerReferences
) : ReferenceReader<HeapObject> {

  override fun read(source: HeapObject): Sequence<Reference> {
    val pathStrength = reachability.strengthOf(source)
    val ownership = ownerReferences.ownershipOf(source)
    val retaining = strengthReader.retainingReferencesOf(source)
      .filter { ownerReferences.isHeldThrough(ownership, it) }
      .let { references ->
        // Nothing to weigh when the path here is strong, which it is for nearly every object of a dump.
        if (pathStrength == STRONG) {
          references
        } else {
          references.filter { reachability.isHeldThrough(it.valueObjectId, pathStrength) }
        }
      }
    val followed = strengthReader.weakeningReferencesOf(source)
      .filter { reachability.isHeldThrough(it.valueObjectId, maxOf(pathStrength, it.strength)) }
    return if (followed.isEmpty()) {
      retaining
    } else {
      retaining + followed.asSequence().map { it.toReference() }
    }
  }
}
