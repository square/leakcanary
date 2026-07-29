package shark.explorer

import androidx.collection.MutableLongObjectMap
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
import shark.explorer.ReachabilityStrength.CACHE
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK

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
 * not to follow, never why. So this reads both halves: a matched [ReferenceReader] for everything
 * that retains, and the fields listed in [WEAKENING_FIELDS_BY_CLASS_NAME] for everything that doesn't,
 * with the strength coming from which class declares them.
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
   * Which fields of a class hold their value without retaining it, by class object id. Cached because
   * it takes a class hierarchy walk to work out and a heap dump has far more instances than classes.
   */
  private val weakeningFieldsByClassId = MutableLongObjectMap<WeakeningFields>()

  /** The references from [source] that keep their target alive. */
  fun retainingReferencesOf(source: HeapObject): Sequence<Reference> = retainingReader.read(source)

  /**
   * The references from [source] that don't keep their target alive: a `java.lang.ref.Reference`'s
   * `referent`, the `zombie` a `FinalizerReference` holds an object in while it's being finalized, and
   * the entries of a cache from [CACHE_FIELDS_BY_CLASS_NAME]. Empty for anything else, which is nearly
   * every object of a heap dump.
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
      .filter { it.name in weakening.fieldNames }
      .mapNotNull { field ->
        val valueObjectId = field.value.asNonNullObjectId
        if (valueObjectId == null || !graph.objectExists(valueObjectId)) {
          null
        } else {
          WeakeningReference(weakening.strength, valueObjectId, field.name, locationClassObjectId)
        }
      }
      .toList()
  }

  private fun weakeningFieldsOf(instanceClass: HeapClass): WeakeningFields =
    weakeningFieldsByClassId.getOrPut(instanceClass.objectId) {
      // Subclass first, which is what makes a FinalizerReference read as a finalizer reference rather
      // than as the phantom reference it extends.
      instanceClass.classHierarchy
        .firstNotNullOfOrNull { WEAKENING_FIELDS_BY_CLASS_NAME[it.name] }
        ?: NOTHING_WEAKENING
    }

  /** Which of a class's fields hold their value without retaining it, and how weakly. */
  private class WeakeningFields(
    val strength: ReachabilityStrength,
    val fieldNames: Set<String>
  )

  companion object {
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
     * The fields a cache holds its entries in, which the explorer treats as [CACHE] rather than as
     * retaining, so that an object a cache and something else both hold is attributed to the something
     * else. See [CACHE] for why this is a list rather than something read off the heap dump.
     *
     * Curated, and deliberately short. An entry belongs here once two things are true of the class: it
     * is a cache that evicts on its own, and its entries are worth blaming their owner for rather than
     * it. Add one only against a heap dump that has it — the fields are matched by name, so a wrong
     * guess doesn't fail, it silently does nothing. Which is also what happens to a dump obfuscated
     * without a mapping applied.
     *
     * Cutting as low as the value a cache entry wraps, rather than at the cache itself, is what keeps
     * the cache's own bookkeeping — the map, the entries, the sizes — where it belongs: strongly held by
     * the cache, and its bytes attributed to it.
     */
    private val CACHE_FIELDS_BY_CLASS_NAME = mapOf(
      // Coil 3's memory cache: a bounded LRU of decoded images, halved on TRIM_MEMORY_RUNNING_LOW and
      // cleared on TRIM_MEMORY_BACKGROUND by AndroidSystemCallbacks.
      "coil3.memory.RealStrongMemoryCache\$InternalValue" to setOf("image")
    )

    /** Every class whose fields don't all retain, and what each holds its values with. */
    private val WEAKENING_FIELDS_BY_CLASS_NAME: Map<String, WeakeningFields> =
      STRENGTH_BY_REFERENCE_CLASS_NAME.mapValues { (_, strength) ->
        WeakeningFields(strength, REFERENT_FIELD_NAMES)
      } + CACHE_FIELDS_BY_CLASS_NAME.mapValues { (_, fieldNames) ->
        WeakeningFields(CACHE, fieldNames)
      }

    /** For the classes that retain everything they point at, cached like the rest. */
    private val NOTHING_WEAKENING = WeakeningFields(STRONG, emptySet())

    /**
     * The matchers that stop the retaining reader from following a reference that doesn't retain.
     *
     * [JdkReferenceMatchers.REFERENCES] covers weak, soft and phantom referents and the finalizer
     * list links. The two added here are the ones it misses because LeakCanary doesn't need them:
     * a JVM `FinalReference`'s referent, and the `zombie` an Android `FinalizerReference` moves its
     * referent into while `finalize()` runs. Then the cache fields, from the same map that gives them
     * their strength, so that the two halves of this class can't disagree about a reference.
     */
    val WEAKENING_REFERENCE_MATCHERS: List<ReferenceMatcher> =
      ReferenceMatcher.fromListBuilders(EnumSet.of(JdkReferenceMatchers.REFERENCES)) + listOf(
        instanceField("java.lang.ref.FinalReference", "referent").ignored(patternApplies = ALWAYS),
        instanceField("java.lang.ref.FinalizerReference", "zombie").ignored(patternApplies = ALWAYS)
      ) + CACHE_FIELDS_BY_CLASS_NAME.flatMap { (className, fieldNames) ->
        fieldNames.map { instanceField(className, it).ignored(patternApplies = ALWAYS) }
      }
  }
}

/**
 * Reads the references that retain their target, plus the ones that hold an object without retaining it
 * — a `java.lang.ref.Reference`'s referent, a cache's entries — when such a reference is the strongest
 * thing reaching the object and its strength is in [followedStrengths].
 *
 * With an empty [followedStrengths] the graph is the strongly reachable heap. Adding [WEAK] to it adds
 * the objects a weak reference is the only path to, dominated by the weak reference itself, which is
 * what puts a weakly reachable rectangle inside a strongly reachable one. Adding [CACHE] does the same
 * for the objects nothing but a cache holds.
 *
 * **The target's strength decides, not the reference's.** Following a weak reference to an object that
 * something else holds strongly wouldn't reveal anything — the object is already in the tree — but it
 * would add an edge, which moves the object's retained size up to whatever dominates both paths and
 * attributes it to neither. So checking a strength that nothing in the heap dump is reachable at
 * leaves the treemap exactly as it was, which is the honest answer.
 */
internal class StrengthFilteringReferenceReader(
  private val strengthReader: ReferenceStrengthReader,
  private val reachability: HeapReachability,
  private val followedStrengths: Set<ReachabilityStrength>
) : ReferenceReader<HeapObject> {

  override fun read(source: HeapObject): Sequence<Reference> {
    val retaining = strengthReader.retainingReferencesOf(source)
    if (followedStrengths.isEmpty()) {
      return retaining
    }
    val followed = strengthReader.weakeningReferencesOf(source)
      .filter { reachability.strengthOf(it.valueObjectId) in followedStrengths }
    return if (followed.isEmpty()) {
      retaining
    } else {
      retaining + followed.asSequence().map { it.toReference() }
    }
  }
}
