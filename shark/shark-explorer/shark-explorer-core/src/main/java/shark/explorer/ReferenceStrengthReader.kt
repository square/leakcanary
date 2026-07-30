package shark.explorer

import androidx.collection.MutableLongObjectMap
import java.util.EnumSet
import shark.ActualMatchingReferenceReaderFactory
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.JdkReferenceMatchers
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferenceLocationType.STATIC_FIELD
import shark.ReferenceMatcher
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField
import shark.ReferenceReader
import shark.ValueHolder
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
    retainingReader.read(source) + classMetadataReferencesOf(source)

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
 * thing reaching the object.
 *
 * That's what puts a weakly reachable object in the tree, dominated by the weak reference itself, and a
 * cached one under its cache entry: every object of the heap dump is a node, and every one of them is
 * held by whatever the garbage collector would have to let go of first.
 *
 * **The target's strength decides, not the reference's.** Following a weak reference to an object that
 * something else holds strongly wouldn't reveal anything — the object is already in the tree — but it
 * would add an edge, which moves the object's retained size up to whatever dominates both paths and
 * attributes it to neither. Which is exactly what a weak reference isn't: it holds nothing.
 */
internal class WeakeningAwareReferenceReader(
  private val strengthReader: ReferenceStrengthReader,
  private val reachability: HeapReachability
) : ReferenceReader<HeapObject> {

  override fun read(source: HeapObject): Sequence<Reference> {
    val retaining = strengthReader.retainingReferencesOf(source)
    val followed = strengthReader.weakeningReferencesOf(source)
      .filter { reachability.strengthOf(it.valueObjectId) != STRONG }
    return if (followed.isEmpty()) {
      retaining
    } else {
      retaining + followed.asSequence().map { it.toReference() }
    }
  }
}
