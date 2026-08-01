package shark.explorer

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ValueHolder

/**
 * The activities the process is running, as references from the `ActivityThread` straight to each one, the
 * way you'd describe what the field holds — `mActivities` — rather than the way it's stored.
 *
 * It's stored as an `ArrayMap<IBinder, ActivityClientRecord>`, so every activity of every dump is five
 * objects down from the thread that runs it: `ActivityThread.mActivities → ArrayMap → Object[] → [1] →
 * ActivityClientRecord.activity → MainActivity`. Two things follow from reading it that way. A path spends
 * four steps saying what one says, and it's the record that gets to own the activity — see [OwnerReferences]
 * — which draws every screen of an app under a different unnamed record instead of side by side under the
 * one thread running them.
 *
 * So this adds one virtual reference per running activity, named after the field they're in. **Additive** —
 * the map, its array and each record are still reached through `mActivities` and are still nodes holding
 * their own bytes, because the explorer needs every object of a heap dump to be a node exactly once (see
 * [ReferenceStrengthReader]). What takes them out of the middle of the tree is the dominator tree rather
 * than this: both ways to an activity now start at the `ActivityThread`, so it dominates it and the record
 * is left retaining what it holds besides the activity.
 */
internal class ActivityThreadReferenceReader(private val graph: HeapGraph) {

  /**
   * The class id of `ActivityThread`, resolved once. There is one of these per process, so this turns "is
   * this the activity thread" into a single comparison — where [HeapGraph.findClassByName] scans every
   * string of the heap dump and calling it per object costs minutes.
   */
  private val activityThreadClassId: Long by lazy {
    graph.findClassByName(ACTIVITY_THREAD_CLASS_NAME)?.objectId ?: ValueHolder.NULL_REFERENCE
  }

  /** The running activities when [source] is the `ActivityThread`, and nothing for anything else. */
  fun runningActivityReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapInstance || source.instanceClassId != activityThreadClassId) {
      return emptySequence()
    }
    val activities = source[ACTIVITY_THREAD_CLASS_NAME, ACTIVITIES_FIELD_NAME]?.valueAsInstance
      ?: return emptySequence()
    val entries = activities[ARRAY_MAP_CLASS_NAME, ENTRIES_FIELD_NAME]?.valueAsObjectArray
      ?: return emptySequence()
    val elementIds = entries.readRecord().elementIds
    // An ArrayMap keeps its keys at the even slots and its values at the odd ones, over the first mSize
    // pairs of an array it grows in chunks. Bounded by the count for the same reason ViewGroup's children
    // are: a pair the map doesn't count is not an entry, and an ArrayMap doesn't null the slots it gives up
    // — it leaves them for the next put — so past the count is where a removed activity is still written
    // down. Which is exactly the leak you'd be looking for, and calling it a running activity hides it.
    val entryCount = activities[ARRAY_MAP_CLASS_NAME, SIZE_FIELD_NAME]?.value?.asInt
      ?.coerceIn(0, elementIds.size / 2)
      ?: return emptySequence()
    val activityThreadClassObjectId = source.instanceClassId
    return (0 until entryCount).asSequence()
      .mapNotNull { entryIndex ->
        val recordId = elementIds[entryIndex * 2 + 1]
        if (recordId == ValueHolder.NULL_REFERENCE) {
          null
        } else {
          graph.findObjectByIdOrNull(recordId)?.asInstance
        }
      }
      .mapNotNull { record ->
        record[ACTIVITY_CLIENT_RECORD_CLASS_NAME, ACTIVITY_FIELD_NAME]?.value?.asNonNullObjectId
      }
      .map { activityObjectId ->
        Reference(
          valueObjectId = activityObjectId,
          isLowPriority = false,
          lazyDetailsResolver = {
            LazyDetails(
              name = ACTIVITIES_FIELD_NAME,
              // The field really is declared here, and it really does hold this activity — through four
              // objects this reference stands in for. So a path reads ActivityThread.mActivities rather
              // than naming the record the map happens to keep it in.
              locationClassObjectId = activityThreadClassObjectId,
              locationType = INSTANCE_FIELD,
              isVirtual = true,
              matchedLibraryLeak = null
            )
          }
        )
      }
  }

  companion object {
    /** Also what an [OwnerRule] names to say that the thread owns the activities read here. */
    const val ACTIVITY_THREAD_CLASS_NAME = "android.app.ActivityThread"

    private const val ACTIVITY_CLIENT_RECORD_CLASS_NAME =
      "android.app.ActivityThread\$ActivityClientRecord"

    private const val ARRAY_MAP_CLASS_NAME = "android.util.ArrayMap"

    private const val ACTIVITIES_FIELD_NAME = "mActivities"

    private const val ACTIVITY_FIELD_NAME = "activity"

    private const val ENTRIES_FIELD_NAME = "mArray"

    private const val SIZE_FIELD_NAME = "mSize"
  }
}
