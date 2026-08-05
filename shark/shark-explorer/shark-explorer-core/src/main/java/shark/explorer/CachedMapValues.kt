package shark.explorer

import androidx.collection.LongSet
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.SharkLog
import shark.ValueHolder

/**
 * Which map entries of a heap dump hold a value a cache holds, so that the value reads as
 * [ReachabilityStrength.CACHE] while the map, its entries and its keys stay strongly held by the cache.
 *
 * The caches [ReferenceStrengthReader] already knows about wrap each cached value in a class of their own
 * — Coil's `RealStrongMemoryCache$InternalValue` — so naming that class and the field is the whole of it.
 * The ones here keep their values in a `java.util.HashMap`, where the only thing between the cache and
 * what it caches is a `HashMap$Node`, a class every map of the heap dump shares. Weakening its `value`
 * field by class name would weaken every map there is, so which entries are a cache's is read off the
 * heap dump instead: this walks the map of each cache and remembers what its entries point at.
 *
 * Cutting there rather than at the map keeps the cache's own bookkeeping where [ReferenceStrengthReader]
 * keeps it — strongly held, its bytes attributed to the cache — since weakening the map would take the
 * table, the entries and the keys down with it.
 *
 * Two references to drop rather than one, though: [DataStructureReferenceReader] reads a map as the
 * entries you put in it, so the map points straight at each value as well as through the entry holding it.
 * Both go, and only the entry's is handed back as the weakening one, so that a cached value is held one
 * way and weakly.
 */
internal class CachedMapValues(private val graph: HeapGraph) {

  /**
   * The cached values of the heap dump, by what points at them.
   *
   * Built on first use rather than in a constructor, and as a whole object assigned at once, so that a
   * read given up on half way is retried rather than remembered — the rule for every index here, see the
   * shark-explorer AGENTS guide. Empty for a heap dump with none of these caches in it, which is most.
   */
  private val cached: CachedValues by lazy { readCacheMaps() }

  /**
   * The value [source] holds as an entry of a cache, or [ValueHolder.NULL_REFERENCE] for anything else —
   * which is nearly every object of a heap dump, and every object of one with no such cache in it.
   */
  fun cachedValueIdOf(source: HeapObject): Long = if (source is HeapInstance) {
    cached.cachedValueIdByEntryId.getOrDefault(source.objectId, ValueHolder.NULL_REFERENCE)
  } else {
    ValueHolder.NULL_REFERENCE
  }

  /**
   * Which of the objects [source] points at read as cached, and so as references that don't retain: the
   * one value an entry holds, and every value of a cache's map.
   *
   * The map is in here because it points straight at each of its values as well —
   * [DataStructureReferenceReader] reads a map as the entries you put in it, which is a second reference
   * to the same value from a second object. Dropping only the entry's would leave the value strongly held
   * by the map, which is the cache holding it after all.
   */
  fun cachedValueIdsOf(source: HeapObject): LongSet = if (source is HeapInstance) {
    cached.cachedValueIdsBySourceId[source.objectId] ?: NOTHING_CACHED
  } else {
    NOTHING_CACHED
  }

  /**
   * Walks the map of every cache of [CACHE_MAP_FIELDS_BY_CLASS_NAME], which takes one pass over the
   * classes of the heap dump, one over its instances, and a read per entry of the caches it found.
   */
  private fun readCacheMaps(): CachedValues {
    val cachedValues = CachedValues(MutableLongLongMap(), MutableLongObjectMap())
    val mapFieldNameByClassId = mapFieldNameByCacheClassId()
    if (mapFieldNameByClassId.isEmpty()) {
      return cachedValues
    }
    graph.instances.forEach { instance ->
      mapFieldNameByClassId[instance.instanceClassId]?.let { mapFieldName ->
        readEntriesOf(instance, mapFieldName, cachedValues)
      }
    }
    return cachedValues
  }

  /**
   * Which field holds the map of a cache, by the class object id of every class that is one — the listed
   * classes and their subclasses, since `LruResourceCache` is how Glide's `LruCache` is used.
   *
   * Reads no object record: a class's name and its superclass both come from the heap dump index.
   */
  private fun mapFieldNameByCacheClassId(): MutableLongObjectMap<String> {
    val mapFieldNameByClassId = MutableLongObjectMap<String>()
    graph.classes.forEach { heapClass ->
      // Most derived class first, so that a subclass listed with a field of its own wins over the one it
      // inherits.
      heapClass.classHierarchy
        .firstNotNullOfOrNull { CACHE_MAP_FIELDS_BY_CLASS_NAME[it.name] }
        ?.let { mapFieldName -> mapFieldNameByClassId[heapClass.objectId] = mapFieldName }
    }
    return mapFieldNameByClassId
  }

  /**
   * Reads the entries of [cache]'s map into [cachedValues], by walking the buckets of the table and the
   * chain each one starts.
   *
   * The table rather than the `head` and `after` of a `LinkedHashMap`, so that the one walk covers a
   * plain `HashMap` too, and the chain rather than the entry count, so that a map caught mid-insertion
   * reads as what it holds rather than as what it is about to.
   *
   * Fields are matched by name against the whole instance instead of by their declaring class, because
   * which class of the hierarchy declares `table` and `value` is exactly the sort of thing that differs
   * between runtimes, and a name that doesn't match here silently caches nothing.
   */
  private fun readEntriesOf(
    cache: HeapInstance,
    mapFieldName: String,
    cachedValues: CachedValues
  ) {
    val map = cache.readFields()
      .firstOrNull { it.name == mapFieldName }
      ?.valueAsInstance
      ?: return
    val tableField = map.readFields().firstOrNull { it.name == TABLE_FIELD_NAME }
    if (tableField == null) {
      SharkLog.d {
        "${cache.instanceClassSimpleName} keeps its entries in ${map.instanceClassSimpleName}, which " +
          "has no $TABLE_FIELD_NAME to read them from, so nothing of it reads as cached"
      }
      return
    }
    // A map allocates its table on the first put, so a null one is a cache holding nothing rather than a
    // shape this can't read, and says nothing worth a line: an idle app's dump is full of them — every
    // `android.util.LruCache` of `compose-nopng.hprof` is an empty `LinkedHashMap`. Saying that couldn't
    // be read would report five defects where there are none.
    val table = tableField.valueAsObjectArray ?: return
    // A heap dump is a file, and a file can say anything: a bucket chain that loops would otherwise be
    // read until it ran out of memory.
    val entryIds = MutableLongSet()
    table.readRecord().elementIds.forEach { bucketId ->
      var entryId = bucketId
      while (entryId != ValueHolder.NULL_REFERENCE && entryIds.add(entryId)) {
        val entry = graph.findObjectByIdOrNull(entryId) as? HeapInstance ?: break
        var valueId = ValueHolder.NULL_REFERENCE
        var nextId = ValueHolder.NULL_REFERENCE
        entry.readFields().forEach { field ->
          when (field.name) {
            VALUE_FIELD_NAME -> valueId = field.value.asNonNullObjectId ?: ValueHolder.NULL_REFERENCE
            NEXT_FIELD_NAME -> nextId = field.value.asNonNullObjectId ?: ValueHolder.NULL_REFERENCE
          }
        }
        if (valueId != ValueHolder.NULL_REFERENCE && graph.objectExists(valueId)) {
          cachedValues.add(entryId = entryId, mapId = map.objectId, valueId = valueId)
        }
        entryId = nextId
      }
    }
  }

  /**
   * What the two questions above are answered from, filled in as the caches are walked and read together
   * once the walk is done.
   */
  private class CachedValues(
    /** What each cache entry points at: the one reference out of it that doesn't retain. */
    val cachedValueIdByEntryId: MutableLongLongMap,
    /** Every cached value each cache entry and each cache map points at. */
    val cachedValueIdsBySourceId: MutableLongObjectMap<MutableLongSet>
  ) {

    fun add(
      entryId: Long,
      mapId: Long,
      valueId: Long
    ) {
      cachedValueIdByEntryId[entryId] = valueId
      cachedValueIdsBySourceId.getOrPut(entryId) { MutableLongSet() } += valueId
      cachedValueIdsBySourceId.getOrPut(mapId) { MutableLongSet() } += valueId
    }
  }

  companion object {
    /**
     * The field a cache keeps its `java.util.HashMap` of entries in, by the name of the class declaring
     * it, subclasses included.
     *
     * Curated to the same bar as the cache field list in [ReferenceStrengthReader], and its other half: a
     * class belongs in one list or the other depending only on whether the cache wraps each value in a
     * class of its own. Add an entry only against a heap dump that has the cache in it.
     */
    private val CACHE_MAP_FIELDS_BY_CLASS_NAME = mapOf(
      // The framework's own LRU, size bounded and evicting on every put, and what an app reaches for to
      // cache its own bitmaps. In here for the app's own caches rather than for a measurement of its
      // own: every one of the fourteen instances of it in `large-dump.hprof` is the framework's or a
      // library's — nine SQLite prepared statement caches, a job cache, a typeface cache, two empty ones
      // — so what it moves on that dump is bookkeeping and not pixels. The bitmaps that dump does hold
      // in a cache are Picasso's below, which is a class of its own and no subclass of this.
      "android.util.LruCache" to "map",
      // Picasso's memory cache of decoded bitmaps, evicted over the maximum size and on `onTrimMemory`.
      // `large-dump.hprof` has one holding four bitmaps, a 760×1262 and three 126×126. Two of them are
      // held by nothing else and move, 3,899,984 B, which sat at the top of the tree because a cache was
      // all there was to blame; the other two stay strong under what shows them.
      "com.squareup.picasso.LruCache" to "map",
      // Glide's, which `LruResourceCache` is: the decoded resources of images nothing is displaying any
      // more, evicted over the maximum size and on `onTrimMemory`. What is displaying one holds it
      // through an `EngineResource` of its own, so an image in use is attributed to the view showing it
      // and only what is idle is left under the cache.
      "com.bumptech.glide.util.LruCache" to "cache"
    )

    /** Read by every object of a heap dump with no cache in it, so one set rather than one per object. */
    private val NOTHING_CACHED = MutableLongSet(initialCapacity = 0)

    /** What `java.util.HashMap` calls the array of buckets, and its nodes their value and their chain. */
    private const val TABLE_FIELD_NAME = "table"

    const val VALUE_FIELD_NAME = "value"

    private const val NEXT_FIELD_NAME = "next"
  }
}
