package shark.explorer.app

import java.io.File
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.ReachabilityStrength

/**
 * The heap dumps [ExplorerAppTest] drives a window with, each shaped so that what it is about is what the
 * treemap draws biggest — a press in the middle of the view lands on it without the test knowing where the
 * rectangles ended up.
 *
 * Built by the `dump { }` DSL rather than checked in, and one per shape rather than one big dump, so that
 * what a test relies on is in the dump it opens.
 */

/** The length of the array a heap dump here is mostly made of. */
internal const val PAYLOAD_LENGTH = 4096

internal const val WEAKLY_REACHABLE_DUMP_NAME = "weakly-reachable.hprof"
internal const val WEAK_PAYLOAD_LENGTH = 32768
internal const val WEAK_PAYLOAD_BYTE_SIZE = WEAK_PAYLOAD_LENGTH * 4L
internal const val GARBAGE_PAYLOAD_LENGTH = 32768
internal const val GARBAGE_PAYLOAD_BYTE_SIZE = GARBAGE_PAYLOAD_LENGTH * 4L

/** Twice what a node draws one by one, so half the siblings end up in one rectangle. */
internal const val SIBLING_COUNT = 400
internal const val SIBLING_CLASS_NAME = "com.example.Sibling"
internal const val SIBLING_PAYLOAD_LENGTH = 16

/**
 * An instance holding an array, like the dump most tests open, plus a much larger array that only a
 * `WeakReference` points at, so that the weakly reachable part of the tree is what a blind press in the
 * middle of the treemap lands on.
 */
internal fun TemporaryFolder.weaklyReachablePayloadHeapDump(): File {
  val file = newFile(WEAKLY_REACHABLE_DUMP_NAME)
  file.dump {
    val holder = "com.example.Holder" instance {
      field["payload"] =
        ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
    }
    val weakReference = "java.lang.ref.WeakReference" instance {
      field["referent"] = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(WEAK_PAYLOAD_LENGTH))
      )
    }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 1))
  }
  return file
}

/**
 * A heap dump most of which is garbage: a large array nothing points at and no GC root reaches, which
 * a collection would have taken had one run before the dump was written.
 */
internal fun TemporaryFolder.uncollectedGarbageHeapDump(): File {
  val file = newFile("uncollected-garbage.hprof")
  file.dump {
    objectArray(arrayClass("java.lang.Object"), LongArray(GARBAGE_PAYLOAD_LENGTH))
    val holder = "com.example.Holder" instance {
      field["payload"] =
        ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
    }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump shaped like the one [ReachabilityStrength.CACHE] came from, built of the real class and
 * field names Coil's memory cache is made of, because that is what the explorer matches on. With
 * [alsoShownByATile] a tile showing the image holds it too, so the cache isn't what keeps it around.
 */
internal fun TemporaryFolder.coilCachedImageHeapDump(alsoShownByATile: Boolean): File {
  val file = newFile("coil-cached-image-$alsoShownByATile.hprof")
  file.dump {
    val pixels =
      ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
    val image = "coil3.BitmapImage" instance { field["bitmap"] = pixels }
    val cacheEntry =
      "coil3.memory.RealStrongMemoryCache\$InternalValue" instance { field["image"] = image }
    val cache = "coil3.memory.RealStrongMemoryCache" instance { field["cache"] = cacheEntry }
    gcRoot(JniGlobal(id = cache.value, jniGlobalRefId = 0))
    if (alsoShownByATile) {
      val tile = "com.example.Tile" instance {
        field["view"] = "com.example.View" instance { field["drawable"] = pixels }
        field["result"] = "coil3.request.SuccessResult" instance { field["image"] = image }
      }
      gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 1))
    }
  }
  return file
}

/**
 * A heap dump shaped like the one the paths section was built for: a cache and the view showing an
 * image both hold it, and the view holds it twice, so the paths that hold it meet only at the root.
 */
internal fun TemporaryFolder.cachedPayloadHeapDump(): File {
  val file = newFile("cached-payload.hprof")
  file.dump {
    val payload =
      ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
    val wrapper = "com.example.Wrapper" instance { field["payload"] = payload }
    val view = "com.example.View" instance { field["drawable"] = payload }
    val tile = "com.example.Tile" instance {
      field["result"] = wrapper
      field["view"] = view
    }
    val cache = "com.example.Cache" instance { field["entry"] = wrapper }
    gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 0))
    gcRoot(JniGlobal(id = cache.value, jniGlobalRefId = 1))
  }
  return file
}

/** How many objects hold each other in [longChainHeapDump], which is more than one pane can draw. */
internal const val CHAIN_LINK_COUNT = 12

/**
 * A heap dump where the payload is held at the end of a chain of [CHAIN_LINK_COUNT] objects, so that the
 * chain beside the map is taller than the pane it is drawn in.
 */
internal fun TemporaryFolder.longChainHeapDump(): File {
  val file = newFile("long-chain.hprof")
  file.dump {
    var held =
      ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
    // A class per link, numbered from the payload out, so that a step says how far along the chain it is.
    repeat(CHAIN_LINK_COUNT) { index ->
      held = "com.example.Link$index" instance { field["next"] = held }
    }
    gcRoot(JniGlobal(id = held.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump with more equally sized rooted instances than one node draws one by one, so that the
 * treemap has a rectangle standing for the ones it left out.
 */
internal fun TemporaryFolder.manySiblingsHeapDump(): File {
  val file = newFile("many-siblings.hprof")
  file.dump {
    val siblingClassId = clazz(
      className = "com.example.Sibling",
      fields = listOf("payload" to ReferenceHolder::class)
    )
    val objectArrayClassId = arrayClass("java.lang.Object")
    val siblingIds = LongArray(SIBLING_COUNT) { _ ->
      val payload = objectArray(objectArrayClassId, LongArray(SIBLING_PAYLOAD_LENGTH))
      instance(siblingClassId, fields = listOf(ReferenceHolder(payload))).value
    }
    // Held by an array rather than each being a GC root: the root's children are gathered by class,
    // so the crowd a leftover cell stands for has to sit under a node that isn't the root.
    val siblings = objectArray(objectArrayClassId, siblingIds)
    gcRoot(JniGlobal(id = siblings, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump with both kinds of leak in it: two destroyed activities held the same way, which is one leak
 * with two objects in it, and one object the app handed to LeakCanary's watcher, which is a leak of its own.
 *
 * The watched object holds the payload, so it is the largest thing in the dump and its leak is the first
 * row of the list.
 */
internal fun TemporaryFolder.leakyHeapDump(): LeakyHeapDump {
  val file = newFile("leaky.hprof")
  var watchedObjectId = 0L
  var weakReferenceObjectId = 0L
  var activityObjectIds = emptyList<Long>()
  file.dump {
    val activityClassId = clazz(
      className = LEAKING_ACTIVITY_CLASS_NAME,
      // Field values are written most derived class first, and the subclass declares none, so an instance
      // of it is written with the one field it inherits.
      superclassId = clazz(
        className = "android.app.Activity",
        fields = listOf("mDestroyed" to BooleanHolder::class)
      )
    )
    val holderClassId = clazz(
      className = "com.example.Holder",
      fields = listOf("activity" to ReferenceHolder::class)
    )
    activityObjectIds = (0..1).map { index ->
      val activity = instance(activityClassId, fields = listOf(BooleanHolder(true)))
      val holder = instance(holderClassId, fields = listOf(activity))
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = index.toLong()))
      activity.value
    }
    val watched = LEAKING_PRESENTER_CLASS_NAME instance {
      field["payload"] =
        ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
    }
    watchedObjectId = watched.value
    weakReferenceObjectId = keyedWeakReference(watched).value
    val presenters = "com.example.Presenters" instance { field["presenter"] = watched }
    gcRoot(JniGlobal(id = presenters.value, jniGlobalRefId = 2))
  }
  return LeakyHeapDump(file, watchedObjectId, weakReferenceObjectId, activityObjectIds)
}

/** A [leakyHeapDump] and the objects a test asks the window about. */
internal class LeakyHeapDump(
  val file: File,
  /** The object LeakCanary was watching, which is the leak the list leads with. */
  val watchedObjectId: Long,
  /** The `KeyedWeakReference` it was watched with, which the row about it leads to. */
  val weakReferenceObjectId: Long,
  /** The two destroyed activities, which are one leak the list folds them into. */
  val activityObjectIds: List<Long>
)

internal const val LEAKING_ACTIVITY_CLASS_NAME = "com.example.MainActivity"

internal const val LEAKING_PRESENTER_CLASS_NAME = "com.example.LeakingPresenter"

/**
 * What the list calls the leak the two destroyed activities are instances of: the reference that shouldn't
 * be holding them, which is what a leak is, rather than the class of what it holds.
 */
internal const val ACTIVITY_LEAK_NAME = "Holder.activity"

/** And what it calls the leak the watched object is the one instance of. */
internal const val PRESENTER_LEAK_NAME = "Presenters.presenter"

/** A heap dump with more objects of one class directly under the root than a view can draw. */
internal fun TemporaryFolder.crowdedRootHeapDump(): File {
  val file = newFile("crowded-root.hprof")
  file.dump {
    val siblingClassId = clazz(
      className = "com.example.Sibling",
      fields = listOf("payload" to ReferenceHolder::class)
    )
    val objectArrayClassId = arrayClass("java.lang.Object")
    repeat(SIBLING_COUNT) { index ->
      val payload = objectArray(objectArrayClassId, LongArray(SIBLING_PAYLOAD_LENGTH))
      val sibling = instance(siblingClassId, fields = listOf(ReferenceHolder(payload)))
      gcRoot(JniGlobal(id = sibling.value, jniGlobalRefId = index.toLong()))
    }
  }
  return file
}
