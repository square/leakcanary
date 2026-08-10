package shark.explorer

import androidx.collection.MutableLongObjectMap
import shark.ActualMatchingReferenceReaderFactory
import shark.AndroidReferenceMatchers
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.LibraryLeakReferenceMatcher
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferenceLocationType.STATIC_FIELD
import shark.ReferenceMatcher
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField
import shark.ReferenceReader
import shark.SharkLog
import shark.ValueHolder
import shark.ignored
import shark.explorer.ReachabilityStrength.CACHE
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.THREAD_LOCAL
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
 * with the strength coming from which class declares them. Plus the one weakening reference no class name
 * can name, an entry of a cache that keeps its values in a plain map — see [CachedMapValues].
 *
 * Deliberately uses the plain field and array readers rather than
 * [shark.AndroidReferenceReaderFactory]: the Android readers present data structures the way you
 * think about them instead of the way they're built, which is right for a leak trace but means some
 * objects only ever appear as flattened children of the structure holding them. An explorer needs
 * every object of the heap dump to be a node of the graph exactly once, so that reachability and the
 * unreachable byte count are exact.
 *
 * Where a structure is worth presenting the way you think about it anyway, the reference is **added**
 * rather than swapped in: [DataStructureReferenceReader] gives a collection a reference to each entry,
 * [ViewChildReferenceReader] gives a `ViewGroup` one to each of its children,
 * [LayoutNodeChildReferenceReader] does the same for a Compose `LayoutNode` and
 * [RunningActivityReferenceReader] gives an `ActivityThread` one to each activity the app is running — and
 * the table, the `View[]` and the `ArrayMap` they really live in are still reached through their fields and
 * still nodes of their own.
 *
 * [SlotTableReferenceReader] is the one exception, and only for the references *out of* one array per
 * Compose composition: adding there would push what a composable remembers further up the tree rather
 * than down to it, for the reason its KDoc gives. Every object it moves a reference to is still reached
 * exactly once, and the array is still a node of its own.
 */
internal class ReferenceStrengthReader(private val graph: HeapGraph) {

  private val retainingReader: ReferenceReader<HeapObject> =
    ActualMatchingReferenceReaderFactory(WEAKENING_REFERENCE_MATCHERS + libraryLeakMatchers(graph))
      .createFor(graph)

  private val viewChildReader = ViewChildReferenceReader(graph)

  private val layoutNodeChildReader = LayoutNodeChildReferenceReader(graph)

  private val dataStructureReader = DataStructureReferenceReader(graph)

  private val runningActivityReader = RunningActivityReferenceReader(graph)

  private val cachedMapValues = CachedMapValues(graph)

  private val slotTableReader = SlotTableReferenceReader(graph)

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
  fun retainingReferencesOf(source: HeapObject): Sequence<Reference> {
    if (slotTableReader.slotsReadFromTheirGroups(source)) {
      // The one reader here that replaces an object's references rather than adding to them, and the
      // KDoc of [SlotTableReferenceReader] is about why.
      return emptySequence()
    }
    val references = retainingReader.read(source) + classMetadataReferencesOf(source) +
      viewChildReader.childReferencesOf(source) + layoutNodeChildReader.childReferencesOf(source) +
      slotTableReader.groupReferencesOf(source) + dataStructureReader.entryReferencesOf(source) +
      runningActivityReader.activityReferencesOf(source)
    val cachedValueIds = cachedMapValues.cachedValueIdsOf(source)
    return if (cachedValueIds.isEmpty()) {
      references
    } else {
      // Dropped by what they point at rather than by the name of the field they're in, so that no
      // reference has to have its details resolved to be weighed — reading names is what costs here, and
      // a cache entry pointing at its value twice is an entry that retains it neither way. The map holding
      // the entries has its own reference to each value, which [dataStructureReader] reads, and it goes
      // the same way for the same reason — see [CachedMapValues].
      references.filter { !cachedValueIds.contains(it.valueObjectId) }
    }
  }

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
   * thread's own storage, and the entries of a cache — from [CACHE_FIELDS_BY_CLASS_NAME] when the cache
   * wraps each value in a class of its own, and from [CachedMapValues] when it keeps them in a map. Empty
   * for anything else, which is nearly every object of a heap dump.
   */
  fun weakeningReferencesOf(source: HeapObject): List<WeakeningReference> {
    if (source !is HeapInstance) {
      return emptyList()
    }
    val cachedValue = cachedValueReferenceOf(source)
    val weakening = weakeningFieldsOf(source.instanceClass)
    if (weakening === NOTHING_WEAKENING) {
      return listOfNotNull(cachedValue)
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
      .toList() + listOfNotNull(cachedValue)
  }

  /**
   * What [source] holds as an entry of a cache that keeps its values in a map, at [CACHE] and named after
   * the field it really is in — see [CachedMapValues]. Null for anything else.
   *
   * The same reference [retainingReferencesOf] drops, so that a cached value is held one way and weakly,
   * the way [CACHE_FIELDS_BY_CLASS_NAME] holds what a cache entry class wraps.
   */
  private fun cachedValueReferenceOf(source: HeapInstance): WeakeningReference? {
    val cachedValueId = cachedMapValues.cachedValueIdOf(source)
    return if (cachedValueId == ValueHolder.NULL_REFERENCE) {
      null
    } else {
      WeakeningReference(
        strength = CACHE,
        valueObjectId = cachedValueId,
        fieldName = CachedMapValues.VALUE_FIELD_NAME,
        locationClassObjectId = source.instanceClassId
      )
    }
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
          WEAKENING_FIELDS_BY_CLASS_NAME[heapClass.name]?.let { inherited + it } ?: inherited
        }
      if (strengthByFieldName.isEmpty()) NOTHING_WEAKENING else WeakeningFields(strengthByFieldName)
    }

  /** Which of a class's fields hold their value without retaining it, and how weakly each does. */
  private class WeakeningFields(val strengthByFieldName: Map<String, ReachabilityStrength>)

  companion object {
    /**
     * Shark's list of the references known to leak in code an app doesn't control, which **names the
     * references it matches without changing which of them are followed**: a
     * [LibraryLeakReferenceMatcher] sets [Reference.isLowPriority] and [LazyDetails.matchedLibraryLeak] and
     * nothing else. So the tree is the same tree with the known leaks of it named, and a chain through one
     * can say so. The first of those two is why a chain goes through one only when there is no other way to
     * the object, the same as in a leak trace — see [RootPathSearch].
     *
     * Only the library leak matchers of that list. The ignored ones beside them would drop references,
     * and every object of the heap dump has to stay a node of the tree exactly once — see the class
     * comment.
     *
     * Empty for a heap dump that doesn't say which Android build it was taken on, whose references none
     * of these can match anyway: most of them decide whether they apply by reading `android.os.Build`.
     */
    private fun libraryLeakMatchers(graph: HeapGraph): List<ReferenceMatcher> {
      if (!graph.recordsAndroidBuild()) {
        SharkLog.d {
          "The heap dump doesn't record the $ANDROID_BUILD_CLASS_NAME it was taken on, so no chain " +
            "through it can name a known Android library leak"
        }
        return emptyList()
      }
      return AndroidReferenceMatchers.appDefaults.filterIsInstance<LibraryLeakReferenceMatcher>()
    }

    /**
     * Whether the heap dump has the device [shark.AndroidBuildMirror] is a mirror of, which is what
     * nearly every one of Shark's library leak patterns decides whether it applies by.
     *
     * By the three fields it reads rather than by the class, because it reads all three with `!!`: a dump
     * that has `android.os.Build` and not its fields — a synthetic one, an Android runtime that strips
     * them — is a bare NPE from inside the reference reader, which is under everything the explorer
     * reads. What that looks like is a window that never draws a tree.
     */
    private fun HeapGraph.recordsAndroidBuild(): Boolean {
      val buildClass = findClassByName(ANDROID_BUILD_CLASS_NAME) ?: return false
      val versionClass = findClassByName(ANDROID_BUILD_VERSION_CLASS_NAME) ?: return false
      return buildClass["MANUFACTURER"]?.value?.readAsJavaString() != null &&
        buildClass["ID"]?.value?.readAsJavaString() != null &&
        versionClass["SDK_INT"]?.value?.asInt != null
    }

    /** What every Android heap dump has and no other kind does. See [libraryLeakMatchers]. */
    private const val ANDROID_BUILD_CLASS_NAME = "android.os.Build"

    private const val ANDROID_BUILD_VERSION_CLASS_NAME = "android.os.Build\$VERSION"

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
     *
     * Which is also why a cache that wraps its values in nothing is listed in [CachedMapValues] instead:
     * there the class between the cache and its value is a `HashMap$Node` every map of the heap dump
     * shares, so naming it here would weaken every map there is.
     */
    private val CACHE_FIELDS_BY_CLASS_NAME = mapOf(
      // Coil 3's memory cache: a bounded LRU of decoded images, halved on TRIM_MEMORY_RUNNING_LOW and
      // cleared on TRIM_MEMORY_BACKGROUND by AndroidSystemCallbacks.
      "coil3.memory.RealStrongMemoryCache\$InternalValue" to setOf("image"),
      // What Glide keeps to hand out again rather than allocate: the bitmaps of `LruBitmapPool`, and the
      // arrays a decode reads through of `LruArrayPool`. Both are one `GroupedLinkedMap` keyed by the
      // size and config asked for, so this one entry covers the two of them, and both evict on their own
      // — over their maximum size, and down to nothing on `onTrimMemory`. Nothing else holds a pooled
      // object: it is there because it is free, which is what makes it worth telling apart from the
      // bytes an app is using.
      "com.bumptech.glide.load.engine.bitmap_recycle.GroupedLinkedMap\$LinkedEntry" to setOf("values")
    )

    /**
     * Where a thread keeps what it put in a `ThreadLocal`, held for as long as the thread lives.
     *
     * `ThreadLocalMap.Entry` is a `WeakReference` to the `ThreadLocal` itself, so its `referent` is
     * already weakened above; what's added here is the value, which the map holds strongly. Attributing
     * it to the thread is what the tree does otherwise, and a thread of an app's pool retaining
     * everything anything ever left in a thread local of it is a picture of the pool rather than of the
     * app.
     */
    private val THREAD_LOCAL_FIELDS_BY_CLASS_NAME = mapOf(
      "java.lang.ThreadLocal\$ThreadLocalMap\$Entry" to setOf("value")
    )

    /** Every class whose fields don't all retain, and what each holds its values with. */
    private val WEAKENING_FIELDS_BY_CLASS_NAME: Map<String, Map<String, ReachabilityStrength>> =
      STRENGTH_BY_REFERENCE_CLASS_NAME.mapValues { (_, strength) ->
        REFERENT_FIELD_NAMES.associateWith { strength }
      } + CACHE_FIELDS_BY_CLASS_NAME.mapValues { (_, fieldNames) ->
        fieldNames.associateWith { CACHE }
      } + THREAD_LOCAL_FIELDS_BY_CLASS_NAME.mapValues { (_, fieldNames) ->
        fieldNames.associateWith { THREAD_LOCAL }
      }

    /** For the classes that retain everything they point at, cached like the rest. */
    private val NOTHING_WEAKENING = WeakeningFields(emptyMap())

    /**
     * The matchers that stop the retaining reader from following a reference that doesn't retain: every
     * field of [WEAKENING_FIELDS_BY_CLASS_NAME] and nothing else, read off the same map that gives them
     * their strength, so that the two halves of this class can't disagree about a reference.
     *
     * **Deliberately not [shark.JdkReferenceMatchers.REFERENCES]**, which is a leak trace's list rather
     * than an explorer's. Besides the referents, it drops the `prev`, `next` and `element` links of the
     * lists a runtime keeps its `FinalizerReference`s, `Finalizer`s and `Cleaner`s on, so that a leak trace
     * can't run through the queue of objects waiting to be finalized. Those links retain what they point
     * at, and on Android they are the only thing that does: the list hangs off one static field, so
     * dropping them left every entry but the head of it reading as uncollected garbage, and with them
     * every object waiting to be finalized or cleaned. Measured on `large-dump.hprof`: 4773 of its 4774
     * `FinalizerReference`s and 3392 of its 3553 `Cleaner`s, a fifth of everything the explorer called
     * garbage.
     */
    val WEAKENING_REFERENCE_MATCHERS: List<ReferenceMatcher> =
      WEAKENING_FIELDS_BY_CLASS_NAME.flatMap { (className, strengthByFieldName) ->
        strengthByFieldName.keys.map {
          instanceField(className, it).ignored(patternApplies = ALWAYS)
        }
      }
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
