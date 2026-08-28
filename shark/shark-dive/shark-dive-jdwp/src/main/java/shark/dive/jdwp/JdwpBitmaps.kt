package shark.dive.jdwp

import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.ClassType
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import java.util.concurrent.TimeUnit
import shark.SharkLog
import shark.dive.Adb
import shark.dive.AdbFailureException
import shark.dive.AndroidDevice
import shark.dive.BitmapDebugger
import shark.dive.CommandLineAdb
import shark.dive.DeviceHeapDumps
import shark.dive.DeviceProcess
import shark.dive.EncodedImageFormat
import shark.dive.NativeBitmapPixels
import shark.dive.formatByteSize

/**
 * The compressed images of a live process's bitmaps, from a process whose heap dumps can't carry any.
 *
 * This is what there is for API 26 to 34: a bitmap's pixels are in native memory, so no Java heap dump
 * holds them, and `am dumpheap -b png`, which asks a process to compress them into its dump, only arrived
 * in API 35 ([DeviceHeapDumps.MIN_BITMAP_DUMP_SDK_INT]). The pixels are still in the process though, and
 * so is `Bitmap.compress`, so the way to them is to make the process compress its own bitmaps: attach over
 * JDWP, list every live `Bitmap`, invoke `compress` on each. `com.sun.jdi` is part of the JDK, so this
 * needs nothing built for a device — no JVMTI agent, no NDK, nothing pushed and attached.
 *
 * It charges the same price `am dumpheap` does, which is **the app has to be debuggable** — see
 * [JdwpSession], which is everything both this and [JdwpGc] need before they can ask anything.
 *
 * What it costs the app: every thread suspended for as long as the reading takes, and the app's own
 * `Bitmap.compress` run once per bitmap on one of its threads while the rest of it waits. That is why a
 * device new enough to be asked through a heap dump is asked that way instead — see
 * [DeviceHeapDumps.fetchBitmaps] — and why the reading here stops at a budget rather than taking as long
 * as it takes.
 *
 * Blocks on `adb` and on the app, so not from the UI thread.
 */
class JdwpBitmaps(private val adb: Adb = CommandLineAdb()) : BitmapDebugger {

  override fun fetchBitmaps(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  ): NativeBitmapPixels {
    onProgress("Attaching a debugger to ${process.name}")
    JdwpSession.attach(adb, device, process).use { session ->
      return NativeBitmapPixels(
        // What `-b` is asked for too, and for the same reason: a PNG says its own size in 24 bytes,
        // which is what tells an image apart from one of a bitmap that has since taken its address.
        format = EncodedImageFormat.PNG,
        bytesByNativePointer = session.compressBitmaps(device, process, onProgress)
      )
    }
  }

  /** The image of every bitmap the process has, by the native pointer of the bitmap it belongs to. */
  private fun JdwpSession.compressBitmaps(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  ): Map<Long, ByteArray> {
    val bitmapClass = virtualMachine.bitmapClass(device, process)
    onProgress("Waiting for ${process.name} to run something")
    val thread = awaitSafePoint()
    val bitmaps = bitmapClass.drawableBitmaps()
    val compressor = BitmapCompressor.of(virtualMachine, bitmapClass, thread) ?: throw AdbFailureException(
      "${process.name} is missing something it takes to compress a bitmap, which an Android process " +
        "shouldn't be: `Bitmap.compress`, `Bitmap.CompressFormat.PNG` or `ByteArrayOutputStream`."
    )
    val images = LinkedHashMap<Long, ByteArray>()
    var byteCount = 0L
    var failedCount = 0
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMPRESS_BUDGET_SECONDS)
    for ((index, bitmap) in bitmaps.withIndex()) {
      if (byteCount >= MAX_TOTAL_BYTE_COUNT || System.nanoTime() >= deadline) {
        // Out loud rather than quietly handing back fewer images than the process has: a treemap drawn
        // from a partial fetch is right about what it shows and says nothing about what it doesn't.
        SharkLog.d {
          "Stopped after ${index + 1} of the ${bitmaps.size} bitmaps of ${process.name}, having read " +
            "${formatByteSize(byteCount)} of them: a suspended app is not somewhere to spend longer."
        }
        break
      }
      onProgress("Compressing bitmap ${index + 1} of ${bitmaps.size}, ${formatByteSize(byteCount)} so far")
      val bytes = compressor.compress(bitmap.reference)
      if (bytes == null) {
        failedCount++
      } else {
        images[bitmap.nativePointer] = bytes
        byteCount += bytes.size
      }
    }
    if (failedCount > 0) {
      SharkLog.d { "$failedCount of the ${bitmaps.size} bitmaps of ${process.name} refused to compress" }
    }
    return images
  }

  /** The `android.graphics.Bitmap` of the process, which is what its bitmaps are asked of. */
  private fun VirtualMachine.bitmapClass(
    device: AndroidDevice,
    process: DeviceProcess
  ): ClassType {
    if (!canGetInstanceInfo()) {
      throw AdbFailureException(
        "The debugger of ${process.name} on ${device.description} can't be asked which objects exist " +
          "(JDWP ${version()}), so there is no way to find its bitmaps."
      )
    }
    return classesByName(BITMAP_CLASS_NAME).filterIsInstance<ClassType>().firstOrNull()
      ?: throw AdbFailureException(
        "${process.name} on ${device.description} has never loaded $BITMAP_CLASS_NAME, so it has no " +
          "bitmaps to read."
      )
  }

  companion object {
    /**
     * How long the app is left suspended reading its bitmaps, and how many bytes of them are worth
     * reading, whichever runs out first.
     *
     * Both are there because this stops an app: the biggest bitmaps are read first, so what a budget
     * cuts is the tail of small ones, and what it buys is that a process with a thousand bitmaps stops
     * being a process frozen for minutes.
     */
    private const val COMPRESS_BUDGET_SECONDS = 60L
    private const val MAX_TOTAL_BYTE_COUNT = 64L * 1024 * 1024
  }
}

/** One bitmap of the live process: what to invoke on, and what a heap dump knows it by. */
private class LiveBitmap(
  val reference: ObjectReference,
  val nativePointer: Long,
  val pixelCount: Long
)

/**
 * Every bitmap of the process that has pixels to read, the biggest first.
 *
 * Biggest first because reading them stops at a budget: the big ones are both the ones worth looking at
 * and the big rectangles of a treemap, so a fetch that only got those is most of the value of one that
 * got everything.
 *
 * A recycled bitmap is left out because `compress` throws for one, and so is anything of no size, which
 * nothing would draw anyway.
 */
private fun ClassType.drawableBitmaps(): List<LiveBitmap> {
  val pointerField = fieldByName("mNativePtr") ?: return emptyList()
  val widthField = fieldByName("mWidth") ?: return emptyList()
  val heightField = fieldByName("mHeight") ?: return emptyList()
  val recycledField = fieldByName("mRecycled")
  val fields = listOfNotNull(pointerField, widthField, heightField, recycledField)
  return instances(ALL_INSTANCES)
    .mapNotNull { bitmap ->
      // One round trip for every field of a bitmap rather than one each: this runs for every bitmap the
      // process has, and the process is suspended for all of it.
      val values = bitmap.getValues(fields)
      val width = (values[widthField] as? IntegerValue)?.value() ?: 0
      val height = (values[heightField] as? IntegerValue)?.value() ?: 0
      val pointer = (values[pointerField] as? LongValue)?.value() ?: 0L
      val recycled = recycledField?.let { (values[it] as? BooleanValue)?.value() } ?: false
      // No pixels means nothing to compress, and no address means nothing to key an image by, which is
      // what a bitmap still being constructed looks like.
      val pixelCount = width.toLong() * height
      if (recycled || pixelCount <= 0L || pointer == 0L) {
        null
      } else {
        LiveBitmap(bitmap, pointer, pixelCount)
      }
    }
    .sortedByDescending { it.pixelCount }
}

/**
 * What it takes to turn a bitmap of the target process into a PNG, and the thread of that process it is
 * done on.
 *
 * Three calls into the app per bitmap — make a `ByteArrayOutputStream`, `compress` into it, read the bytes
 * back — because that is the shortest path from a `Bitmap` to bytes the framework has. The PNG never
 * reaches the device's filesystem, so there is nothing left behind in someone's app and no `run-as` to
 * pull it back out with.
 */
private class BitmapCompressor(
  private val thread: ThreadReference,
  private val streamType: ClassType,
  private val newStream: Method,
  private val toByteArray: Method,
  private val compress: Method,
  private val format: Value,
  private val quality: Value
) {

  /**
   * The PNG of [bitmap], or null when the app wouldn't produce one.
   *
   * Every call lets the app's other threads run for its duration, which is what invoking without
   * `INVOKE_SINGLE_THREADED` means and what makes a `Config.HARDWARE` bitmap work: its pixels are on the
   * GPU, `compress` reads them back through the render thread, and a render thread held suspended is a
   * readback that never finishes.
   */
  fun compress(bitmap: ObjectReference): ByteArray? = try {
    val stream = streamType.newInstance(thread, newStream, emptyList(), RESUME_OTHER_THREADS)
    val compressed = bitmap.invokeMethod(thread, compress, listOf(format, quality, stream), RESUME_OTHER_THREADS)
    if ((compressed as? BooleanValue)?.value() != true) {
      null
    } else {
      (stream.invokeMethod(thread, toByteArray, emptyList(), RESUME_OTHER_THREADS) as? ArrayReference)
        ?.readBytes()
    }
  } catch (disconnected: VMDisconnectedException) {
    // Nothing to read for any bitmap once the app is gone, so this one is not the failure to report.
    throw disconnected
  } catch (failure: Exception) {
    // A bitmap listed a moment ago can have been recycled or collected since, and `compress` throws for
    // a recycled one. One image missing is not worth losing the rest.
    SharkLog.d(failure) { "A bitmap of the live process would not compress" }
    null
  }

  companion object {
    /**
     * Everything a compress takes, or null when the process turns out not to have some of it.
     *
     * @param thread only used to load a class the app hasn't loaded yet, which `Bitmap.CompressFormat`
     * can be — see [loadedClass].
     */
    fun of(
      virtualMachine: VirtualMachine,
      bitmapClass: ClassType,
      thread: ThreadReference
    ): BitmapCompressor? {
      val streamType = virtualMachine.loadedClass(STREAM_CLASS_NAME, thread) as? ClassType ?: return null
      val formatType = virtualMachine.loadedClass(FORMAT_CLASS_NAME, thread) ?: return null
      val png = formatType.fieldByName(PNG_FIELD)?.let { formatType.getValue(it) } ?: return null
      return BitmapCompressor(
        thread = thread,
        streamType = streamType,
        newStream = streamType.concreteMethodByName("<init>", "()V") ?: return null,
        toByteArray = streamType.concreteMethodByName("toByteArray", "()[B") ?: return null,
        compress = bitmapClass.concreteMethodByName("compress", COMPRESS_SIGNATURE) ?: return null,
        format = png,
        // PNG is lossless and ignores it, but `compress` takes one.
        quality = virtualMachine.mirrorOf(MAX_QUALITY)
      )
    }
  }
}

/**
 * The class [name] of the process, loaded into it first if it isn't loaded yet.
 *
 * A debugger only sees the classes a process has already loaded, and `Bitmap.CompressFormat` is loaded by
 * an app that has compressed something — which an app whose bitmaps are being fetched has, by definition,
 * not. Most Android builds have it in the boot image, so having to load it is the rare case rather than
 * the path, but an app where it isn't would otherwise have no bitmaps to read.
 */
private fun VirtualMachine.loadedClass(
  name: String,
  thread: ThreadReference
): ReferenceType? {
  classesByName(name).firstOrNull()?.let { return it }
  val classType = classesByName("java.lang.Class").filterIsInstance<ClassType>().firstOrNull()
    ?: return null
  val forName = classType.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;")
    ?: return null
  // The boot class loader, being the one that loaded Class itself, which is where both of the classes
  // this is used for live.
  classType.invokeMethod(thread, forName, listOf(mirrorOf(name)), RESUME_OTHER_THREADS)
  return classesByName(name).firstOrNull()
}

private fun ArrayReference.readBytes(): ByteArray {
  val values = getValues()
  return ByteArray(values.size) { (values[it] as ByteValue).value() }
}

private const val BITMAP_CLASS_NAME = "android.graphics.Bitmap"
private const val FORMAT_CLASS_NAME = "android.graphics.Bitmap\$CompressFormat"
private const val STREAM_CLASS_NAME = "java.io.ByteArrayOutputStream"
private const val COMPRESS_SIGNATURE = "(Landroid/graphics/Bitmap\$CompressFormat;ILjava/io/OutputStream;)Z"
private const val PNG_FIELD = "PNG"
private const val MAX_QUALITY = 100

/** What [ReferenceType.instances] takes for "all of them". */
private const val ALL_INSTANCES = 0L
