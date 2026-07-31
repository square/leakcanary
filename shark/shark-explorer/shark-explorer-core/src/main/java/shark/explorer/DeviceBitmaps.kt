package shark.explorer

import java.io.File
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * Fetches the pixels of a live process's bitmaps off the device that wrote a heap dump.
 *
 * This exists because from API 26 a bitmap keeps its pixels in native memory, which a Java heap dump
 * does not cover: the dump has the width, the height and the address, and nothing to draw. The pixels
 * are still in the process that wrote the dump, and the one supported way to get at them is to ask that
 * process to compress them — `am dumpheap -b png`, which writes a second heap dump with a PNG of every
 * live bitmap in it, keyed by the native pointer of the bitmap. See [HeapBitmaps] for the other side of
 * that, and [MIN_BITMAP_DUMP_SDK_INT] for the version it needs.
 *
 * The steps are separate on purpose, because each of them is a question the person at the window has to
 * answer: which of the connected devices is the one the dump came from, which of its processes is the
 * one that wrote it, and only then the dump itself, which takes seconds and tens of megabytes.
 *
 * Every step shells out to `adb` and blocks, so this belongs on a background thread.
 */
class DeviceBitmaps(private val adb: Adb = CommandLineAdb()) {

  /** Every device `adb` is connected to, each asked what it is so it can be matched to a dump. */
  fun connectedDevices(): List<AndroidDevice> = adb.connectedDevices()

  /**
   * The processes of [device] that could have written a dump [origin] came from: the one whose name
   * matches exactly first, then the other processes of the same app.
   *
   * An app can run in more than one process — a `:remote` service, LeakCanary's own — and they all
   * have the app's package as the first half of their name, so the others are worth offering: the
   * exact one may have died and been restarted under a different pid.
   */
  fun matchingProcesses(
    device: AndroidDevice,
    origin: HeapDumpOrigin
  ): List<DeviceProcess> {
    val processName = origin.processName ?: return emptyList()
    val packageName = processName.substringBefore(PROCESS_NAME_SEPARATOR)
    return runningProcesses(device)
      .filter { it.name == processName || it.name.substringBefore(PROCESS_NAME_SEPARATOR) == packageName }
      .sortedBy { if (it.name == processName) 0 else 1 }
  }

  /** Every process running on [device], as `ps` lists them. */
  fun runningProcesses(device: AndroidDevice): List<DeviceProcess> = parseProcessLines(
    adb.run("-s", device.serialNumber, "shell", "ps", "-A", "-o", "PID,NAME")
      .orFail("list the processes of ${device.description}")
  )

  /**
   * Dumps the heap of [process] with its bitmaps compressed into it, pulls it, and reads the images
   * back out. [onProgress] is called with a description of each step, which takes seconds.
   *
   * @throws AdbFailureException when the device can't do it, which is an Android version below
   * [MIN_BITMAP_DUMP_SDK_INT] or a process that isn't debuggable.
   */
  fun fetchBitmaps(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit = {}
  ): NativeBitmapPixels {
    val sdkInt = device.sdkInt
    if (sdkInt != null && sdkInt < MIN_BITMAP_DUMP_SDK_INT) {
      throw AdbFailureException(
        "${device.description} runs API $sdkInt, and `am dumpheap -b`, which is the only way to ask a " +
          "process for the pixels of its bitmaps, arrived in Android 15 (API " +
          "$MIN_BITMAP_DUMP_SDK_INT). Nothing short of attaching a debugger reads a native bitmap off " +
          "an older device."
      )
    }
    // Named after the pid and the time, because a fetch that failed to clean up must not be the file
    // the next one reads.
    val remotePath = "$REMOTE_DIRECTORY/shark-explorer-${process.processId}-${System.nanoTime()}.hprof"
    val localFile = File.createTempFile("shark-explorer-bitmaps", ".hprof")
    try {
      onProgress("Dumping the heap of ${process.name} with its bitmaps")
      adb.run(
        "-s", device.serialNumber, "shell", "am", "dumpheap",
        "-b", BITMAP_FORMAT, process.processId.toString(), remotePath
      ).orFail("dump the heap of ${process.name} on ${device.description}")
      val byteCount = awaitDumpWritten(device, remotePath, onProgress)
      onProgress("Pulling ${formatByteSize(byteCount)}")
      adb.run("-s", device.serialNumber, "pull", remotePath, localFile.absolutePath)
        .orFail("pull the heap dump from ${device.description}")
      onProgress("Reading the bitmaps out of it")
      return localFile.readNativeBitmapPixels()
    } finally {
      localFile.delete()
      // Best effort: the dump is tens of megabytes of someone's device, but a failure to delete it is
      // not worth losing the images that were fetched over.
      adb.run("-s", device.serialNumber, "shell", "rm", "-f", remotePath)
    }
  }

  /**
   * Waits for the heap dump at [remotePath] to stop growing, and returns how big it ended up.
   *
   * `am dumpheap` returns before the process it asked has finished writing, so a pull started when it
   * returns can pull half a file. A size that hasn't changed since the last look is the only signal
   * there is, so that's what this waits for.
   */
  private fun awaitDumpWritten(
    device: AndroidDevice,
    remotePath: String,
    onProgress: (String) -> Unit
  ): Long {
    var previousByteCount = -1L
    repeat(MAX_SIZE_POLLS) {
      val byteCount = adb.run("-s", device.serialNumber, "shell", "stat", "-c", "%s", remotePath)
        .text.trim().toLongOrNull() ?: 0L
      if (byteCount > 0L && byteCount == previousByteCount) {
        return byteCount
      }
      onProgress("Waiting for the dump to finish, ${formatByteSize(byteCount)} so far")
      previousByteCount = byteCount
      Thread.sleep(SIZE_POLL_MILLIS)
    }
    throw AdbFailureException(
      "${device.description} is still writing the heap dump after " +
        "${MAX_SIZE_POLLS * SIZE_POLL_MILLIS / 1000} seconds."
    )
  }

  /** The compressed images of a dump that was taken with `-b`, which this one was asked to be. */
  private fun File.readNativeBitmapPixels(): NativeBitmapPixels = openHeapGraph().use { graph ->
    val dumpData = graph.readDumpDataIndex() ?: throw AdbFailureException(
      "The heap dump the device just wrote has no `Bitmap.dumpData` in it, so the process did not " +
        "compress its bitmaps. Either the device ignored `-b`, or nothing was left to compress."
    )
    NativeBitmapPixels(
      format = dumpData.format,
      // Held rather than read on demand, unlike the images of the dump being explored: this dump is
      // deleted as soon as it has been read.
      bytesByNativePointer = dumpData.bufferIdByPointer.entries.mapNotNull { (pointer, bufferId) ->
        graph.readByteArray(bufferId)?.let { pointer to it }
      }.toMap()
    )
  }

  companion object {

    /**
     * Where `am dumpheap -b` was added, so the first Android version a native bitmap's pixels can be
     * fetched off at all. See [HeapBitmaps].
     */
    const val MIN_BITMAP_DUMP_SDK_INT = 35

    /** What `-b` is asked for. PNG is lossless, and its header is what a pointer match is checked against. */
    private const val BITMAP_FORMAT = "png"

    /** Writable by the shell and readable by an app being dumped, which is what `am dumpheap` needs. */
    private const val REMOTE_DIRECTORY = "/data/local/tmp"

    private const val SIZE_POLL_MILLIS = 500L
    private const val MAX_SIZE_POLLS = 120

    /** What comes between an app's package and the name of one of its extra processes. */
    private const val PROCESS_NAME_SEPARATOR = ':'
  }
}

/** One process running on a device. */
class DeviceProcess(
  val processId: Int,
  val name: String
)

/** The pid and name of each line of `ps -A -o PID,NAME`, skipping its header. */
internal fun parseProcessLines(output: String): List<DeviceProcess> = output.lines()
  .mapNotNull { line ->
    val columns = line.trim().split(PROCESS_COLUMNS, limit = 2)
    val processId = columns.firstOrNull()?.toIntOrNull()
    if (processId == null || columns.size < 2) null else DeviceProcess(processId, columns[1].trim())
  }

private val PROCESS_COLUMNS = Regex("\\s+")
