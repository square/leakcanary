package shark.explorer.app

import java.io.File
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
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
