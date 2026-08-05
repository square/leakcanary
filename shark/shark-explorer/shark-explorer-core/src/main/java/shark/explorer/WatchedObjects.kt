package shark.explorer

import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.SharkLog
import shark.ValueHolder.Companion.NULL_REFERENCE

/**
 * What LeakCanary's `ObjectWatcher` recorded about an object it was watching, read out of the
 * `KeyedWeakReference` it watched it with.
 *
 * An app tells LeakCanary an object should be gone — a destroyed activity, a detached fragment, a view
 * model that was cleared — by handing it to the watcher, which points a weak reference at it and writes
 * down why. So a watched object that is still reachable when the dump is written is a leak the app itself
 * said was one, which is the strongest evidence a heap dump carries.
 */
data class WatchedObject(
  /**
   * The `KeyedWeakReference` itself, so that the record can be opened on the map like any other object:
   * it is where the key and the durations are read from, and it is also what points at the leaking object
   * weakly, which is why that object shows up under it in the tree.
   */
  val weakReferenceObjectId: Long,
  /** The object being watched, which is the weak reference's referent. */
  val referentObjectId: Long,
  /** What the watcher knows the object by, which is also what it logged about it while the app ran. */
  val key: String,
  /** Why it was watched, e.g. `MainActivity received Activity#onDestroy() callback`. */
  val description: String,
  /** How long before the dump the object was handed over. Null in heap dumps from before 2.0 alpha 3. */
  val watchDurationMillis: Long?,
  /**
   * How long it had been retained — watched, garbage collected for, and still there — when the dump was
   * written. Null in dumps from before 2.0 alpha 3, and [NOT_RETAINED] for an object that isn't retained.
   */
  val retainedDurationMillis: Long?
) {

  /**
   * Whether the object was still there after a garbage collection, which is what makes it a leak rather
   * than an object the watcher happened to be holding a cleared reference to.
   */
  val isRetained: Boolean
    get() = retainedDurationMillis == null || retainedDurationMillis != NOT_RETAINED

  companion object {
    /** What `retainedUptimeMillis` reads for an object the watcher hasn't found to be retained. */
    const val NOT_RETAINED = -1L
  }
}

/**
 * Reads every `KeyedWeakReference` of a heap dump, by the id of the object it was watching.
 *
 * Shark reads these too, for the inspector that says an object was being watched, but through an internal
 * mirror that keeps the fields and drops the weak reference's own id — and the id is what a screen listing
 * leaks needs, because a row that can't be clicked is a dead end. So this is that read again rather than a
 * new public function on shark's side. The fields are the ones LeakCanary writes, and both the current
 * class name and the pre 2.0 one, because a heap dump can be older than the app reading it.
 */
internal object WatchedObjects {

  fun readFrom(graph: HeapGraph): Map<Long, WatchedObject> {
    val classIds = CLASS_NAMES.mapNotNull { graph.findClassByName(it)?.objectId }.toSet()
    if (classIds.isEmpty()) {
      SharkLog.d { "No object of this heap dump was watched: it holds no $KEYED_WEAK_REFERENCE" }
      return emptyMap()
    }
    // Written by LeakCanary as the dump is taken, so that a duration can be worked out from an uptime
    // recorded while the app ran. Absent from dumps written before 2.0 alpha 3, which then have none.
    val heapDumpUptimeMillis = graph.findClassByName(KEYED_WEAK_REFERENCE)
      ?.get("heapDumpUptimeMillis")?.value?.asLong
    val watched = LinkedHashMap<Long, WatchedObject>()
    graph.instances
      .filter { it.instanceClassId in classIds }
      .forEach { instance ->
        val watchedObject = instance.readWatchedObject(heapDumpUptimeMillis)
        if (watchedObject != null) {
          watched[watchedObject.referentObjectId] = watchedObject
        }
      }
    SharkLog.d { "${watched.size} objects of this heap dump were watched by LeakCanary" }
    return watched
  }

  /** Null for a reference whose referent has been cleared, which is an object that was collected. */
  private fun HeapInstance.readWatchedObject(heapDumpUptimeMillis: Long?): WatchedObject? {
    val referentObjectId = this[REFERENCE_CLASS_NAME, "referent"]?.value?.asObjectId
    if (referentObjectId == null || referentObjectId == NULL_REFERENCE) {
      return null
    }
    val key = this[instanceClassName, "key"]?.value?.readAsJavaString()
    if (key == null) {
      // Which is a class named KeyedWeakReference that is not LeakCanary's, so it says nothing about a
      // leak and reading the rest of its fields would say something wrong.
      SharkLog.d { "Skipping the $instanceClassName at ${hexObjectId(objectId)}: it has no key" }
      return null
    }
    return WatchedObject(
      weakReferenceObjectId = objectId,
      referentObjectId = referentObjectId,
      key = key,
      // Called name before 2.0, and neither in the dumps before that, which then say nothing about why.
      description = (this[instanceClassName, "description"] ?: this[instanceClassName, "name"])
        ?.value?.readAsJavaString() ?: "",
      watchDurationMillis = heapDumpUptimeMillis?.let {
        it - (this[instanceClassName, "watchUptimeMillis"]?.value?.asLong ?: return@let null)
      },
      retainedDurationMillis = heapDumpUptimeMillis?.let {
        val retainedUptimeMillis = this[instanceClassName, "retainedUptimeMillis"]?.value?.asLong
          ?: return@let null
        if (retainedUptimeMillis == WatchedObject.NOT_RETAINED) {
          WatchedObject.NOT_RETAINED
        } else {
          it - retainedUptimeMillis
        }
      }
    )
  }

  private const val KEYED_WEAK_REFERENCE = "leakcanary.KeyedWeakReference"

  /** The current class, and the one LeakCanary 1 wrote, which a dump taken back then still has. */
  private val CLASS_NAMES = listOf(KEYED_WEAK_REFERENCE, "com.squareup.leakcanary.KeyedWeakReference")

  private const val REFERENCE_CLASS_NAME = "java.lang.ref.Reference"
}
