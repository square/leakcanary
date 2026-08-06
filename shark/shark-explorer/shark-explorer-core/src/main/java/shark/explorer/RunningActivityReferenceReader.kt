package shark.explorer

import androidx.collection.LongSet
import androidx.collection.MutableLongSet
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.SharkLog
import shark.ValueHolder

/**
 * The activities an app is running, as one reference from its `ActivityThread` straight to each of them,
 * under a name the framework has no field for: `activities`.
 *
 * The framework keeps them in `mActivities`, an `ArrayMap` from an activity's token to the
 * `ActivityClientRecord` it runs it from, so the way from the thread to an activity really reads
 * `ActivityThread.mActivities → ArrayMap.mArray → Object[][1] → ActivityClientRecord.activity`: five
 * objects to say the app is running a screen, three of them a map's bookkeeping. That costs the same two
 * things reading a `ViewGroup`'s children out of its `View[]` costs — see [ViewChildReferenceReader] — a
 * path nobody reads all of, and an owner that is a slot of a map rather than the thing running the
 * activity.
 *
 * So this adds one virtual reference per activity, and [OwnerReferences] names this class to say that an
 * `ActivityThread` owns what they point at. **Additive** — the map, its `Object[]` and every record are
 * still reached through `mActivities` and still nodes holding their own bytes, because the explorer needs
 * every object of a heap dump to be a node exactly once (see [ReferenceStrengthReader]). What takes the map
 * back out of the middle of the tree is the dominator tree rather than this: both ways to an activity now
 * start at the `ActivityThread`, so it dominates the activity and the map is left retaining its own
 * entries.
 *
 * Named `activities` rather than `mActivities`, which would be two references of that name out of one
 * `ActivityThread` pointing at different things — the map, and each activity in it — with nothing in a path
 * or a referrer list to tell them apart. A virtual reference is named by what it is rather than by the
 * field it was read out of, the same way a `ViewGroup`'s children are.
 */
internal class RunningActivityReferenceReader(private val graph: HeapGraph) {

  /**
   * The class id of `ActivityThread`, resolved once: [HeapGraph.findClassByName] scans every string of the
   * heap dump, and an instance's class comes from the index, so this turns "is this the activity thread"
   * into a comparison. Never a real object id for a heap dump that has no `ActivityThread` in it, which is
   * every dump of a JVM.
   */
  private val activityThreadClassId: Long by lazy {
    graph.findClassByName(ACTIVITY_THREAD_CLASS_NAME)?.objectId ?: ValueHolder.NULL_REFERENCE
  }

  /**
   * Every activity the app is running, whichever `ActivityThread` runs it, by object id.
   *
   * The same read as [activityReferencesOf] asked of the whole heap dump rather than of one thread, for
   * [ActivityWindowRule], which needs to know whether the framework is still running a screen and has only
   * this to read it off: an activity it has finished with is out of `mActivities` and says so nowhere else
   * in its references.
   */
  fun runningActivityIds(): LongSet {
    val activityIds = MutableLongSet()
    graph.findClassByName(ACTIVITY_THREAD_CLASS_NAME)?.instances?.forEach { activityThread ->
      runningActivityIdsOf(activityThread).forEach { activityIds += it }
    }
    return activityIds
  }

  /** The activities [source] is running when it's the `ActivityThread`, and nothing at all for the rest. */
  fun activityReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapInstance || source.instanceClassId != activityThreadClassId) {
      return emptySequence()
    }
    return runningActivityIdsOf(source).map { activityObjectId ->
      Reference(
        valueObjectId = activityObjectId,
        isLowPriority = false,
        lazyDetailsResolver = {
          LazyDetails(
            name = ACTIVITIES_NAME,
            locationClassObjectId = activityThreadClassId,
            locationType = INSTANCE_FIELD,
            isVirtual = true,
            matchedLibraryLeak = null
          )
        }
      )
    }
  }

  /** The activities the records in `ActivityThread.mActivities` are running, by object id. */
  private fun runningActivityIdsOf(activityThread: HeapInstance): Sequence<Long> {
    val activities = activityThread[ACTIVITY_THREAD_CLASS_NAME, ACTIVITIES_FIELD_NAME]?.valueAsInstance
      ?: return emptySequence()
    val entries = activities[ARRAY_MAP_CLASS_NAME, ARRAY_MAP_ARRAY_FIELD_NAME]?.valueAsObjectArray
    val entryCount = activities[ARRAY_MAP_CLASS_NAME, ARRAY_MAP_SIZE_FIELD_NAME]?.value?.asInt
    if (entries == null || entryCount == null) {
      // An ArrayMap here since Lollipop, seven releases before the oldest one LeakCanary supports, so this
      // is a dump from before that or from a fork that changed the field. Reads as an activity thread
      // running nothing, which leaves every activity of the dump held by whatever points at it.
      SharkLog.d {
        "$ACTIVITY_THREAD_CLASS_NAME.$ACTIVITIES_FIELD_NAME is a ${activities.instanceClassName} rather " +
          "than an $ARRAY_MAP_CLASS_NAME, so no activity is read as one the app is running"
      }
      return emptySequence()
    }
    val elementIds = entries.readRecord().elementIds
    // An ArrayMap holds a key at every even index and its value at the odd one after it, bounded by the
    // count it keeps rather than by the length of the array, which is a capacity it grows and shrinks in
    // chunks. `ArrayMap.removeAt` nulls the pair it gives up, so the tail is null anyway and the bound
    // looks like belt and braces — but a slot the map doesn't count is not an entry, and calling one an
    // activity the app is running attributes an activity the framework has let go of to the framework,
    // which is exactly the leak you'd be looking for. A heap dump also catches threads mid-method, and
    // `ArrayMap.put` fills the pair before it counts it.
    val valueIndexes = 1 until (entryCount * 2).coerceIn(0, elementIds.size) step 2
    return valueIndexes.asSequence()
      .mapNotNull { index -> graph.findObjectByIdOrNull(elementIds[index]) as? HeapInstance }
      .mapNotNull { record ->
        record[ACTIVITY_CLIENT_RECORD_CLASS_NAME, ACTIVITY_FIELD_NAME]?.value?.asNonNullObjectId
      }
  }

  companion object {
    /** Also what an [OwnerRule] names to say that the thread owns the activities read here. */
    const val ACTIVITY_THREAD_CLASS_NAME = "android.app.ActivityThread"

    /** What every reference read here is called, which is no field of `ActivityThread`. */
    private const val ACTIVITIES_NAME = "activities"

    private const val ACTIVITIES_FIELD_NAME = "mActivities"

    private const val ACTIVITY_CLIENT_RECORD_CLASS_NAME =
      "android.app.ActivityThread\$ActivityClientRecord"

    private const val ACTIVITY_FIELD_NAME = "activity"

    private const val ARRAY_MAP_CLASS_NAME = "android.util.ArrayMap"

    private const val ARRAY_MAP_ARRAY_FIELD_NAME = "mArray"

    private const val ARRAY_MAP_SIZE_FIELD_NAME = "mSize"
  }
}
