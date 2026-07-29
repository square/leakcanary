package shark.explorer

import androidx.collection.MutableLongIntMap
import java.util.EnumSet
import shark.ActualMatchingReferenceReaderFactory
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.JdkReferenceMatchers
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferenceMatcher
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField
import shark.ReferenceReader
import shark.ignored
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.WEAK

/**
 * A reference that doesn't keep its target alive, and the [ReachabilityStrength] a path through it
 * comes out at.
 */
internal class WeakeningReference(
  val strength: ReachabilityStrength,
  val valueObjectId: Long,
  private val fieldName: String,
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
 * a `java.lang.ref.Reference` holds its referent with, which don't.
 *
 * Reference strength lives entirely in Shark's ignored reference matchers, and those only say what
 * not to follow, never why. So this reads both halves: a matched [ReferenceReader] for everything
 * that retains, and the `referent` field of each `java.lang.ref.Reference` subclass for everything
 * that doesn't, with the strength coming from which subclass it is.
 *
 * Deliberately uses the plain field and array readers rather than
 * [shark.AndroidReferenceReaderFactory]: the Android readers present data structures the way you
 * think about them instead of the way they're built, which is right for a leak trace but means some
 * objects only ever appear as flattened children of the structure holding them. An explorer needs
 * every object of the heap dump to be a node of the graph exactly once, so that reachability and the
 * unreachable byte count are exact.
 */
internal class ReferenceStrengthReader(private val graph: HeapGraph) {

  private val retainingReader: ReferenceReader<HeapObject> =
    ActualMatchingReferenceReaderFactory(WEAKENING_REFERENCE_MATCHERS).createFor(graph)

  /**
   * Strength ordinal of the referent held by instances of a class, by class object id, or
   * [NOT_A_REFERENCE]. Cached because it takes a class hierarchy walk to work out and a heap dump has
   * far more instances than classes.
   */
  private val referentStrengthOrdinalByClassId = MutableLongIntMap()

  /** The references from [source] that keep their target alive. */
  fun retainingReferencesOf(source: HeapObject): Sequence<Reference> = retainingReader.read(source)

  /**
   * The references from [source] that don't keep their target alive: at most its `referent`, plus the
   * `zombie` a `FinalizerReference` holds an object in while it's being finalized. Empty for anything
   * that isn't a `java.lang.ref.Reference`.
   */
  fun weakeningReferencesOf(source: HeapObject): List<WeakeningReference> {
    if (source !is HeapInstance) {
      return emptyList()
    }
    val strength = referentStrengthOf(source.instanceClass) ?: return emptyList()
    val locationClassObjectId = source.instanceClass.objectId
    return source.readFields()
      .filter { it.name in REFERENT_FIELD_NAMES }
      .mapNotNull { field ->
        val valueObjectId = field.value.asNonNullObjectId
        if (valueObjectId == null || !graph.objectExists(valueObjectId)) {
          null
        } else {
          WeakeningReference(strength, valueObjectId, field.name, locationClassObjectId)
        }
      }
      .toList()
  }

  private fun referentStrengthOf(instanceClass: HeapClass): ReachabilityStrength? {
    val cached = referentStrengthOrdinalByClassId.getOrDefault(instanceClass.objectId, UNKNOWN)
    if (cached != UNKNOWN) {
      return if (cached == NOT_A_REFERENCE) null else STRENGTHS[cached]
    }
    // Subclass first, which is what makes a FinalizerReference read as a finalizer reference rather
    // than as the phantom reference it extends.
    val strength = instanceClass.classHierarchy
      .mapNotNull { STRENGTH_BY_REFERENCE_CLASS_NAME[it.name] }
      .firstOrNull()
    referentStrengthOrdinalByClassId[instanceClass.objectId] = strength?.ordinal ?: NOT_A_REFERENCE
    return strength
  }

  companion object {
    private val STRENGTHS = ReachabilityStrength.values()

    private const val UNKNOWN = -2
    private const val NOT_A_REFERENCE = -1

    /** The fields a `java.lang.ref.Reference` holds its referent in. */
    private val REFERENT_FIELD_NAMES = setOf("referent", "zombie")

    /**
     * The `java.lang.ref.Reference` subclasses whose referent isn't retained, most derived first.
     *
     * `FinalizerReference` is Android's, `FinalReference` is the JVM's — `java.lang.ref.Finalizer`
     * extends it, and it's package private so nothing else can.
     */
    private val STRENGTH_BY_REFERENCE_CLASS_NAME = mapOf(
      "java.lang.ref.FinalizerReference" to FINALIZER,
      "java.lang.ref.FinalReference" to FINALIZER,
      "java.lang.ref.PhantomReference" to PHANTOM,
      "java.lang.ref.WeakReference" to WEAK,
      "java.lang.ref.SoftReference" to SOFT
    )

    /**
     * The matchers that stop the retaining reader from following a reference that doesn't retain.
     *
     * [JdkReferenceMatchers.REFERENCES] covers weak, soft and phantom referents and the finalizer
     * list links. The two added here are the ones it misses because LeakCanary doesn't need them:
     * a JVM `FinalReference`'s referent, and the `zombie` an Android `FinalizerReference` moves its
     * referent into while `finalize()` runs.
     */
    val WEAKENING_REFERENCE_MATCHERS: List<ReferenceMatcher> =
      ReferenceMatcher.fromListBuilders(EnumSet.of(JdkReferenceMatchers.REFERENCES)) + listOf(
        instanceField("java.lang.ref.FinalReference", "referent").ignored(patternApplies = ALWAYS),
        instanceField("java.lang.ref.FinalizerReference", "zombie").ignored(patternApplies = ALWAYS)
      )
  }
}

/**
 * Reads the references that retain their target, plus the referents held by references of the
 * strengths in [followedStrengths].
 *
 * With an empty [followedStrengths] the graph is the strongly reachable heap. Adding [WEAK] to it
 * adds the objects a weak reference is the only path to, dominated by the weak reference itself,
 * which is what puts a weakly reachable rectangle inside a strongly reachable one.
 */
internal class StrengthFilteringReferenceReader(
  private val strengthReader: ReferenceStrengthReader,
  private val followedStrengths: Set<ReachabilityStrength>
) : ReferenceReader<HeapObject> {

  override fun read(source: HeapObject): Sequence<Reference> {
    val retaining = strengthReader.retainingReferencesOf(source)
    if (followedStrengths.isEmpty()) {
      return retaining
    }
    val followed = strengthReader.weakeningReferencesOf(source)
      .filter { it.strength in followedStrengths }
    return if (followed.isEmpty()) {
      retaining
    } else {
      retaining + followed.asSequence().map { it.toReference() }
    }
  }
}
