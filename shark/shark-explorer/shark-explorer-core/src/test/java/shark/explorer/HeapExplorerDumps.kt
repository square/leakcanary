package shark.explorer

import java.io.File
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * The heap dumps [HeapExplorerTest] opens, each shaped for one thing it asks of a tree.
 *
 * Out here rather than in that class because a test file is what it asserts, and these are the fixtures it
 * asserts against: written with the `dump { }` DSL rather than checked in as binaries, so what a dump has in
 * it is readable next to the test that reads it. Also what keeps that class under detekt's size limit, which
 * a dump per question walks into.
 */

/**
 * A heap dump where one instance is the only path to a large object array, so that the dominator
 * tree has an object retaining well more than its shallow size.
 */
internal fun TemporaryFolder.openTestHeapDump(onProgress: (String) -> Unit = {}): HeapExplorer {
  val file = newFile("heap.hprof")
  file.dump {
    val payload = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(256)))
    val holder = "com.example.Holder" instance {
      field["payload"] = payload
      field["name"] = string("Kept alive by the holder")
    }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return HeapExplorer.open(file, onProgress)
}

/** A heap dump where a large object array is only reachable through a `WeakReference`. */
internal fun TemporaryFolder.weaklyReachablePayloadHeapDump(): File {
  val file = newFile("weakly-reachable.hprof")
  file.dump {
    val classes = referenceClasses()
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    val weakReference = reference(classes.weakId, payload)
    gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump where an owner holds three objects a last resort holder also holds — a stack frame, a
 * thread local and a finalizer queue — plus one object only the stack frame holds.
 */
internal fun TemporaryFolder.lastResortHoldersHeapDump(): File {
  val file = newFile("last-resort-holders.hprof")
  file.dump {
    val classes = referenceClasses()
    val onStack = "com.example.OnStack" instance { }
    val inThreadLocal = "com.example.InThreadLocal" instance { }
    val finalized = "com.example.Finalized" instance { }
    val onlyOnStack = "com.example.OnlyOnStack" instance { }
    val holder = "com.example.Holder" instance {
      field["onStack"] = onStack
      field["inThreadLocal"] = inThreadLocal
      field["finalized"] = finalized
    }
    // What a thread keeps a ThreadLocal's value in, held by the thread for as long as it lives.
    val worker = "com.example.Worker" instance {
      field["locals"] = "java.lang.ThreadLocal\$ThreadLocalMap\$Entry" instance {
        field["value"] = inThreadLocal
      }
    }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    gcRoot(JniGlobal(id = worker.value, jniGlobalRefId = 1))
    gcRoot(
      JniGlobal(
        id = finalizerReference(classes, referent = finalized).value,
        jniGlobalRefId = 2
      )
    )
    gcRoot(JavaFrame(id = onStack.value, threadSerialNumber = 1, frameNumber = 0))
    gcRoot(JavaFrame(id = onlyOnStack.value, threadSerialNumber = 1, frameNumber = 1))
  }
  return file
}

/** A heap dump with a bitmap in it, whose pixels live in native memory rather than in its fields. */
internal fun TemporaryFolder.bitmapHeapDump(): File {
  val file = newFile("bitmap.hprof")
  file.dump {
    val bitmap = "android.graphics.Bitmap" instance {
      field["mWidth"] = IntHolder(420)
      field["mHeight"] = IntHolder(467)
      field["mRecycled"] = BooleanHolder(false)
    }
    gcRoot(JniGlobal(id = bitmap.value, jniGlobalRefId = 0))
  }
  return file
}

/** A heap dump written before API 26, so one whose bitmap keeps its pixels in a field. */
internal fun TemporaryFolder.pixelBitmapHeapDump(): File {
  val file = newFile("pixel-bitmap.hprof")
  file.dump {
    val bitmap = bitmapInstance(
      bitmapClassId = bitmapClass(),
      width = 1,
      height = 1,
      // One red pixel, in the RGBA byte order the framework stores ARGB_8888 in.
      pixels = byteArrayOf(0xff.toByte(), 0x00, 0x00, 0xff.toByte())
    )
    gcRoot(JniGlobal(id = bitmap.value, jniGlobalRefId = 0))
  }
  return file
}

/** A heap dump where two unrelated instances both hold the same object array. */
internal fun TemporaryFolder.sharedPayloadHeapDump(): File {
  val file = newFile("shared-payload.hprof")
  file.dump {
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    val holder = "com.example.Holder" instance { field["payload"] = payload }
    val otherHolder = "com.example.OtherHolder" instance { field["payload"] = payload }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    gcRoot(JniGlobal(id = otherHolder.value, jniGlobalRefId = 1))
  }
  return file
}

/** A heap dump where an object array is held by an instance and pointed at by a `WeakReference`. */
internal fun TemporaryFolder.stronglyAndWeaklyReachablePayloadHeapDump(): File {
  val file = newFile("strongly-and-weakly-reachable.hprof")
  file.dump {
    val classes = referenceClasses()
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    val holder = "com.example.Holder" instance {
      field["payload"] = payload
    }
    val weakReference = reference(classes.weakId, payload)
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 1))
  }
  return file
}

/**
 * A heap dump shaped like the one this feature came from: an image cache and the view showing the
 * image both hold it, and the view holds it twice — once as what it draws and once as the result of
 * the request that loaded it.
 *
 * The payload is what the bitmap stands for. Its two referrers are the wrapper and the view, and the
 * wrapper's own two referrers are the cache and the tile, so the paths only meet at the root even
 * though a tile is what actually keeps the payload in memory.
 */
internal fun TemporaryFolder.cachedPayloadHeapDump(): File {
  val file = newFile("cached-payload.hprof")
  file.dump {
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
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
 * A heap dump shaped like the one [ReachabilityStrength.CACHE] came from: Coil's memory cache holds a
 * decoded image, and when [alsoShownByATile] the tile showing it holds the same image two ways — as
 * what its view draws, and as the result of the request that loaded it.
 *
 * The class and field names the cache is built of are the real ones, because that is what the explorer
 * matches on.
 */
internal fun TemporaryFolder.coilCachedImageHeapDump(alsoShownByATile: Boolean): File {
  val file = newFile("coil-cached-image-$alsoShownByATile.hprof")
  file.dump {
    val pixels = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    val image = "coil3.BitmapImage" instance { field["bitmap"] = pixels }
    val cacheEntry = CACHE_ENTRY_CLASS_NAME instance { field["image"] = image }
    val cache = "coil3.memory.RealStrongMemoryCache" instance { field["cache"] = cacheEntry }
    gcRoot(JniGlobal(id = cache.value, jniGlobalRefId = 0))
    if (alsoShownByATile) {
      val view = "com.example.View" instance { field["drawable"] = pixels }
      val result = "coil3.request.SuccessResult" instance { field["image"] = image }
      val tile = "com.example.Tile" instance {
        field["view"] = view
        field["result"] = result
      }
      gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 1))
    }
  }
  return file
}

/**
 * A heap dump where one GC rooted object holds a payload directly and again through two objects, so
 * that the ways it is held differ in length.
 */
internal fun TemporaryFolder.twoWaysToOnePayloadHeapDump(): File {
  val file = newFile("two-ways-to-one-payload.hprof")
  file.dump {
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    val middle = "com.example.Middle" instance { field["payload"] = payload }
    val relay = "com.example.Relay" instance { field["middle"] = middle }
    val holder = "com.example.Holder" instance {
      field["payload"] = payload
      field["relay"] = relay
    }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump where a payload is on a running method's stack as well as two fields from a GC root: the
 * shortest way to it is the stack frame, and the field is the one worth reading.
 *
 * A local variable is a step away from whatever a thread is doing, so a shortest path finds one for a great
 * many objects, and it says nothing you can act on — the object is there because a method is running. The
 * thread has to be a `ThreadObject` GC root with a name for Shark to read its frames as references at all.
 */
internal fun TemporaryFolder.onAStackAndInAFieldHeapDump(): File {
  val file = newFile("on-a-stack-and-in-a-field.hprof")
  file.dump {
    val thread = instance(
      clazz(className = "java.lang.Thread", fields = listOf("name" to ReferenceHolder::class)),
      fields = listOf(string("main"))
    )
    gcRoot(ThreadObject(id = thread.value, threadSerialNumber = 42, stackTraceSerialNumber = 0))
    val payload = objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    gcRoot(JavaFrame(id = payload, threadSerialNumber = 42, frameNumber = 0))
    val holder = "com.example.Holder" instance { field["payload"] = ReferenceHolder(payload) }
    val owner = "com.example.Owner" instance { field["holder"] = holder }
    gcRoot(JniGlobal(id = owner.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump where a payload is held at the end of a chain of [CHAIN_LINK_COUNT] objects, which is
 * longer than a chain is drawn.
 */
internal fun TemporaryFolder.longChainHeapDump(): File {
  val file = newFile("long-chain.hprof")
  file.dump {
    var held = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    // A class per link, numbered from the payload out, so that a step says how far along it is.
    repeat(CHAIN_LINK_COUNT) { index ->
      held = "com.example.Link$index" instance { field["next"] = held }
    }
    gcRoot(JniGlobal(id = held.value, jniGlobalRefId = 0))
  }
  return file
}

/** A heap dump where a payload is held by an object no GC root reaches: garbage, not yet collected. */
internal fun TemporaryFolder.uncollectedGarbageHeapDump(): File {
  val file = newFile("uncollected-garbage.hprof")
  file.dump {
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    "com.example.Forgotten" instance { field["payload"] = payload }
    val holder = "com.example.Holder" instance { }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump where an object is held by its owner and by a helper of its own, the way an
 * `AppCompatImageView` is held by the layout above it and by the helpers it created, which point back
 * at it.
 */
internal fun TemporaryFolder.cyclicHolderHeapDump(): File {
  val file = newFile("cyclic-holder.hprof")
  file.dump {
    val viewClassId = clazz(
      className = "com.example.View",
      fields = listOf("helper" to ReferenceHolder::class, "payload" to ReferenceHolder::class)
    )
    val helperClassId = clazz(
      className = "com.example.Helper",
      fields = listOf("view" to ReferenceHolder::class)
    )
    // The helper points back at the view, so the view's id has to exist before the view is written.
    val viewId = reserveObjectId()
    val helper = instance(helperClassId, listOf(viewId))
    val payload = ReferenceHolder(
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    )
    val view = instance(viewClassId, listOf(helper, payload), objectId = viewId)
    val tile = "com.example.Tile" instance { field["view"] = view }
    gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump with more children under the root than a view can draw one by one: [TILE_COUNT]
 * instances of one class, each a GC root of its own, plus one instance of another class.
 */
internal fun TemporaryFolder.crowdedRootHeapDump(withJavaLangClass: Boolean = false): File {
  val file = newFile("crowded-root${if (withJavaLangClass) "-with-class" else ""}.hprof")
  file.dump {
    if (withJavaLangClass) {
      clazz(className = "java.lang.Class")
    }
    val tileClassId = clazz(
      className = TILE_CLASS_NAME,
      fields = listOf("payload" to ReferenceHolder::class)
    )
    repeat(TILE_COUNT) { index ->
      // A different payload size per tile, so that the instances of one class don't all weigh the same.
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(index + 1))
      )
      val tile = instance(tileClassId, listOf(payload))
      gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = index.toLong()))
    }
    val solo = "com.example.Solo" instance { field["name"] = string("Only one of me") }
    gcRoot(JniGlobal(id = solo.value, jniGlobalRefId = TILE_COUNT.toLong()))
  }
  return file
}

/** How many elements in the object array that stands for something worth retaining. */
internal const val PAYLOAD_ELEMENT_COUNT = 1024

/** The one cache the explorer knows about. What its entries read as on a rectangle is the test's own. */
internal const val CACHE_ENTRY_CLASS_NAME = "coil3.memory.RealStrongMemoryCache\$InternalValue"

/** The one class group of a [crowdedRootHeapDump], which is the tiles. */
internal const val TILE_CLASS_NAME = "com.example.Tile"

/** Past `MIN_CHILDREN_TO_GROUP_BY_CLASS` in [HeapDominatorTreemap], which is 200. */
internal const val TILE_COUNT = 205

/** Enough objects between a GC root and a payload that the chain to it has to be cut. */
internal const val CHAIN_LINK_COUNT = 25

/**
 * A heap dump whose objects sit above the 2 GB mark, which gives every one of them a negative object id.
 *
 * Where the large objects of a real 32 bit Android dump are: an id is 4 bytes there and shark widens those
 * by sign, so a bitmap or a byte array up in that half of the address space is a negative id everywhere
 * below shark's reader. Which is what the tree's own ids have to stay out of the way of.
 */
internal fun TemporaryFolder.highAddressHeapDump(): HighAddressHeapDump {
  val file = newFile("high-address.hprof")
  var payloadObjectId = 0L
  file.dump(firstObjectId = FIRST_HIGH_ADDRESS) {
    payloadObjectId = objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
    val holder = "com.example.Holder" instance {
      field["payload"] = ReferenceHolder(payloadObjectId)
    }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return HighAddressHeapDump(file, payloadObjectId)
}

/** A [highAddressHeapDump] and the object of it a test asks about. */
internal class HighAddressHeapDump(
  val file: File,
  /** The payload array, negative the way every id of this dump is. */
  val payloadObjectId: Long
)

/** 0x82000000 as shark reads a 4 byte id: the first address a 32 bit dump's ids come out negative at. */
private const val FIRST_HIGH_ADDRESS = -0x7E000000L
