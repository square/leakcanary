package shark.explorer.jdwp

import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.Bootstrap
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
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.request.EventRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import shark.SharkLog
import shark.explorer.Adb
import shark.explorer.AdbFailureException
import shark.explorer.AndroidDevice
import shark.explorer.BitmapDebugger
import shark.explorer.CommandLineAdb
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceProcess
import shark.explorer.EncodedImageFormat
import shark.explorer.NativeBitmapPixels
import shark.explorer.formatByteSize

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
 * It charges the same price `am dumpheap` does, which is **the app has to be debuggable**: that is what
 * opens a JDWP connection at all. One debugger at a time, too, so an app Android Studio is attached to
 * can't be read.
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
    val port = forwardJdwp(device, process)
    try {
      val virtualMachine = attach(port, device, process)
      try {
        return NativeBitmapPixels(
          // What `-b` is asked for too, and for the same reason: a PNG says its own size in 24 bytes,
          // which is what tells an image apart from one of a bitmap that has since taken its address.
          format = EncodedImageFormat.PNG,
          bytesByNativePointer = virtualMachine.compressBitmaps(device, process, onProgress)
        )
      } finally {
        // Detaching is what resumes the app, so it has to happen whatever went wrong above: a client that
        // walks away from a suspended process leaves it frozen.
        virtualMachine.dispose()
      }
    } finally {
      adb.run("-s", device.serialNumber, "forward", "--remove", "tcp:$port")
    }
  }

  /**
   * A local TCP port `adb` forwards to the JDWP connection of [process].
   *
   * Asks for `tcp:0`, which has `adb` pick a free port and print it, rather than picking one here and
   * racing whatever else on the machine opens sockets. Note that a forward to a process that isn't
   * debuggable is set up just as happily; nothing says so until something connects.
   */
  private fun forwardJdwp(
    device: AndroidDevice,
    process: DeviceProcess
  ): Int {
    val output = adb.run("-s", device.serialNumber, "forward", "tcp:0", "jdwp:${process.processId}")
      .orFail("open a debugger connection to ${process.name} on ${device.description}")
    return output.trim().toIntOrNull() ?: throw AdbFailureException(
      "`adb forward` was asked which local port reaches ${process.name} and answered \"${output.trim()}\""
    )
  }

  /** The process on the other end of [port], attached to as a debugger. */
  private fun attach(
    port: Int,
    device: AndroidDevice,
    process: DeviceProcess
  ): VirtualMachine {
    val connector = Bootstrap.virtualMachineManager().attachingConnectors()
      .first { it.name() == SOCKET_ATTACH_CONNECTOR }
    val arguments = connector.defaultArguments()
    arguments.getValue("hostname").setValue(LOCALHOST)
    arguments.getValue("port").setValue(port.toString())
    arguments["timeout"]?.setValue(ATTACH_TIMEOUT_MILLIS.toString())
    return try {
      connector.attach(arguments)
    } catch (exception: IOException) {
      // The one failure worth wording, and the likely one: `adb forward` succeeds for any pid, and a
      // process that won't talk JDWP only shows up as a connection that goes nowhere.
      throw AdbFailureException(
        "Could not attach a debugger to ${process.name} on ${device.description}: " +
          "${exception.message}. Only a debuggable app lets a debugger in, which a release build of one " +
          "isn't, and only one debugger at a time — so an app Android Studio is debugging is taken."
      )
    }
  }

  /** The image of every bitmap the process has, by the native pointer of the bitmap it belongs to. */
  private fun VirtualMachine.compressBitmaps(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  ): Map<Long, ByteArray> {
    val bitmapClass = bitmapClass(device, process)
    onProgress("Waiting for ${process.name} to run something")
    val thread = awaitSafePoint(device, process)
    val bitmaps = bitmapClass.drawableBitmaps()
    val compressor = BitmapCompressor.of(this, bitmapClass, thread) ?: throw AdbFailureException(
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

  /**
   * A thread of the process stopped somewhere it can be made to run code, with every other thread of it
   * stopped too.
   *
   * Suspending the process is not enough. ART refuses to invoke a method on a thread it stopped wherever
   * that thread happened to be — `IncompatibleThreadStateException` — so what is needed is a thread
   * stopped *by an event*, and the event that says least about the app is the next method entry anywhere
   * in it. A count filter of one means exactly one ever fires, so the app is suspended once and nothing
   * stays instrumented while its bitmaps are read.
   *
   * Then the app has to run something, and an app that is idle or in the background runs nothing at all.
   * `dumpsys meminfo` is the nudge: the framework answers it by calling into the app over binder, so it
   * runs code in there whether or not the app is on screen, and unlike anything driven through the UI it
   * changes nothing about what the app is showing.
   */
  private fun VirtualMachine.awaitSafePoint(
    device: AndroidDevice,
    process: DeviceProcess
  ): ThreadReference {
    val request = eventRequestManager().createMethodEntryRequest().apply {
      setSuspendPolicy(EventRequest.SUSPEND_ALL)
      addCountFilter(1)
      enable()
    }
    adb.run("-s", device.serialNumber, "shell", "dumpsys", "meminfo", process.processId.toString())
    // Expired by its own count filter once it has fired, so there is nothing to disable on the way out.
    return awaitMethodEntry() ?: run {
      request.disable()
      throw AdbFailureException(
        "${process.name} on ${device.description} ran no code for $SAFE_POINT_BUDGET_SECONDS seconds, " +
          "and a process can only be asked to compress a bitmap at a point where it was running. " +
          "Touching the app makes it run something."
      )
    }
  }

  /** The thread of the first method entry event, or null if none arrives in the budget. */
  private fun VirtualMachine.awaitMethodEntry(): ThreadReference? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFE_POINT_BUDGET_SECONDS)
    while (System.nanoTime() < deadline) {
      val events = eventQueue().remove(EVENT_POLL_MILLIS) ?: continue
      val entry = events.filterIsInstance<MethodEntryEvent>().firstOrNull()
      if (entry != null) {
        return entry.thread()
      }
      // Something else the process reported, e.g. a class being prepared. Nothing here asked for it, and
      // leaving it suspended would leave the app stopped for no reason.
      events.resume()
    }
    return null
  }

  companion object {
    private const val SOCKET_ATTACH_CONNECTOR = "com.sun.jdi.SocketAttach"
    private const val LOCALHOST = "localhost"
    private const val ATTACH_TIMEOUT_MILLIS = 10_000

    /** How long the app is given to run something, which is usually the `dumpsys` round trip. */
    private const val SAFE_POINT_BUDGET_SECONDS = 20L
    private const val EVENT_POLL_MILLIS = 500L

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

/** No `INVOKE_SINGLE_THREADED`, which is what lets the app's other threads run during a call. */
private const val RESUME_OTHER_THREADS = 0
