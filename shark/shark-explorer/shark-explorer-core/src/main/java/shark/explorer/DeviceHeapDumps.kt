package shark.explorer

import java.io.File
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.SharkLog

/**
 * Takes heap dumps off a connected device, and fetches the pixels of a live process's bitmaps.
 *
 * Taking a dump and fetching bitmaps off API 35 and up are the same three `adb` commands — dump, wait,
 * pull — because of where a bitmap's pixels are: from API 26 they're in native memory, which a Java heap
 * dump does not cover, and the supported way to get them is to ask the process to compress them into the
 * dump before it's written. That's `am dumpheap -b png`, and it's API 35 and up
 * ([MIN_BITMAP_DUMP_SDK_INT]).
 *
 * So a dump taken through [dumpHeap] arrives with its bitmaps already in it wherever the device can
 * manage that, and [fetchBitmaps] is the same dump taken of the process a *previous* heap dump came from,
 * kept only for its images — or, on a device too old for `-b`, the same question asked of the process
 * through a [bitmapDebugger]. See [HeapBitmaps] for the reading end of all of it.
 *
 * A dump taken to be explored also has the garbage collected first, because **nothing else collects it**:
 * `am dumpheap` on its own writes whatever is in the heap, and ART's hprof dumper suspends the runtime
 * rather than collecting. Without it an explorer opens on a heap that is mostly garbage nobody can act
 * on. Two ways of asking, the same shape as the two ways of asking for bitmaps: `am dumpheap -g` from API
 * 27 ([MIN_GC_BEFORE_DUMP_SDK_INT]), which is `System.gc()`, `System.runFinalization()` and `System.gc()`
 * run on the dumped process's main thread by `ActivityThread.handleDumpHeap`, and below that a
 * [gcDebugger], which runs the same sequence in the process over JDWP. Measured on one API 29 process
 * dumped all three ways: 18.05 MB of it unreachable with nothing collecting, 0.13 MB with `-g`, 0.80 MB
 * with the debugger. See `notes/decisions.md`.
 *
 * The steps are separate on purpose, because each of them is a question the person at the window has to
 * answer: which connected device, and which of its processes. Every step shells out to `adb` and blocks,
 * so this belongs on a background thread.
 */
class DeviceHeapDumps(
  private val adb: Adb = CommandLineAdb(),
  /**
   * How the pixels of a bitmap are fetched off a device older than [MIN_BITMAP_DUMP_SDK_INT], whose heap
   * dumps can't carry them however they're taken. Null refuses instead, which is what a caller with no
   * debugger to offer gets. `JdwpBitmaps` in `shark-explorer-jdwp` is the one there is.
   */
  private val bitmapDebugger: BitmapDebugger? = null,
  /**
   * How the garbage of a device older than [MIN_GC_BEFORE_DUMP_SDK_INT] is collected before its heap is
   * dumped, since such a device has no `am dumpheap -g` to ask with. Null dumps without collecting, which
   * is a dump with garbage in it rather than no dump. `JdwpGc` in `shark-explorer-jdwp` is the one there
   * is.
   */
  private val gcDebugger: GcDebugger? = null
) {

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

  /**
   * The processes of [device] that belong to an installed app, the ones that aren't the system's first.
   *
   * `ps` lists kernel threads and native services too, and none of those has a Java heap to dump. Going
   * by the name doesn't separate them — `media.extractor` and `android.hardware.audio.service` read
   * exactly like packages — so the packages the device says are installed are what does.
   *
   * Every app process is offered rather than only the ones that can actually be dumped, because whether
   * a process is debuggable is something only `am dumpheap` knows, and it says so clearly when it isn't.
   * [AndroidDevice.dumpsAnyProcess] is as close as anything gets in advance, and it is about the build
   * rather than about the app.
   */
  fun appProcesses(device: AndroidDevice): List<DeviceProcess> {
    val packages = installedPackages(device)
    return runningProcesses(device)
      .filter { it.name.substringBefore(PROCESS_NAME_SEPARATOR) in packages }
      // The app being worked on first, because it is the one being looked for and there are thirty of the
      // system's own behind it — none of which is dumpable at all unless the build is debuggable.
      .sortedWith(compareBy({ if (it.isSystemApp) 1 else 0 }, { it.name }))
  }

  /** Every package installed on [device], as `pm` lists them. */
  private fun installedPackages(device: AndroidDevice): Set<String> =
    adb.run("-s", device.serialNumber, "shell", "pm", "list", "packages")
      .orFail("list the packages installed on ${device.description}")
      .lines()
      .mapNotNull { line ->
        val trimmed = line.trim()
        trimmed.removePrefix(PACKAGE_LINE_PREFIX).takeIf { it != trimmed && it.isNotEmpty() }
      }
      .toSet()

  /** Every process running on [device], as `ps` lists them. */
  fun runningProcesses(device: AndroidDevice): List<DeviceProcess> = parseProcessLines(
    adb.run("-s", device.serialNumber, "shell", "ps", "-A", "-o", "PID,NAME")
      .orFail("list the processes of ${device.description}")
  )

  /**
   * Dumps the heap of [process], pulls it, and returns the local file, with the pixels of its bitmaps in
   * it where the device is new enough to compress them.
   *
   * The caller owns the file and it is deliberately not deleted: the explorer reads a heap dump lazily
   * and for as long as it is open, and a dump that took a minute to take is worth being able to reopen.
   *
   * [onProgress] is called with a description of each step, which takes seconds.
   */
  fun dumpHeap(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit = {}
  ): File {
    // Named after the process, because this one is kept and its name is what the window shows.
    val localFile = File.createTempFile("${process.name}-${process.processId}-", ".hprof")
    if (!device.canCollectGarbageBeforeDump) {
      collectGarbageWithDebugger(device, process, onProgress)
    }
    try {
      pullHeapDump(
        device = device,
        process = process,
        withBitmaps = device.canDumpBitmaps,
        withGc = device.canCollectGarbageBeforeDump,
        localFile = localFile,
        onProgress = onProgress
      )
    } catch (throwable: Throwable) {
      localFile.delete()
      throw throwable
    }
    return localFile
  }

  /**
   * The compressed images of the bitmaps [process] has live right now.
   *
   * Two ways of asking, because a device that can put them in a heap dump should be asked that way: it
   * costs the process nothing but the dump it was going to take anyway, where a debugger has to stop it
   * and run code in it. So API 35 and up is dumped again with `-b png` and read; anything older is handed
   * to [bitmapDebugger].
   *
   * @throws AdbFailureException when the process can't be asked at all, which is a process that isn't
   * debuggable — the requirement both ways round — or a device older than [MIN_BITMAP_DUMP_SDK_INT] with
   * no [bitmapDebugger] to fall back to.
   */
  fun fetchBitmaps(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit = {}
  ): NativeBitmapPixels {
    if (!device.canDumpBitmaps) {
      return bitmapDebugger?.fetchBitmaps(device, process, onProgress) ?: throw AdbFailureException(
        "${device.description} runs API ${device.sdkInt}, and `am dumpheap -b`, which is what puts the " +
          "pixels of a bitmap in a heap dump, arrived in Android 15 (API $MIN_BITMAP_DUMP_SDK_INT). " +
          "Reading them off an older device means attaching a debugger to the process, and this window " +
          "was given no way to do that."
      )
    }
    val localFile = File.createTempFile("shark-explorer-bitmaps", ".hprof")
    try {
      pullHeapDump(
        device = device,
        process = process,
        withBitmaps = true,
        // Unlike a dump taken to be explored: this one is read for the pixels of the bitmaps of a dump
        // taken earlier, and collecting first is how a bitmap that is still in that dump stops being in
        // this one. Garbage costs nothing here — nothing but the images is ever read.
        withGc = false,
        localFile = localFile,
        onProgress = onProgress
      )
      onProgress("Reading the bitmaps out of it")
      return localFile.readNativeBitmapPixels()
    } finally {
      // Unlike a dump taken to be explored, this one was only ever its images, and those are now read.
      localFile.delete()
    }
  }

  /**
   * Collects the garbage of [process] through the [gcDebugger], for a device with no `am dumpheap -g` to
   * ask with.
   *
   * **A collection that fails doesn't fail the dump.** The dump is the expensive thing and the one that
   * was asked for, and one with garbage in it is still a heap dump of that process — where a debugger
   * that can't attach, on a device where nothing else can collect, would otherwise mean no dump at all.
   * So this reports and carries on, the same way a failed bitmap fetch does.
   *
   * The app runs between this and the dump, for as long as detaching and one `adb` round trip take, so
   * what comes back is a little less collected than `-g` would be. That gap is what asking from outside
   * the process costs; the sleep this can afford in the middle and `-g` can't buys more back than it.
   */
  private fun collectGarbageWithDebugger(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  ) {
    if (gcDebugger == null) {
      SharkLog.d {
        "${device.description} runs API ${device.sdkInt}, older than $MIN_GC_BEFORE_DUMP_SDK_INT, so " +
          "`am dumpheap -g` can't collect its garbage first, and this window was given no debugger to " +
          "collect it with. The dump will have uncollected garbage in it."
      }
      return
    }
    try {
      gcDebugger.collectGarbage(device, process, onProgress)
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) {
        "Could not collect the garbage of ${process.name} on ${device.description} before dumping its " +
          "heap. Taking the dump anyway; it will have uncollected garbage in it."
      }
    }
  }

  /** Dumps the heap of [process] into [localFile], leaving nothing behind on the device. */
  private fun pullHeapDump(
    device: AndroidDevice,
    process: DeviceProcess,
    withBitmaps: Boolean,
    withGc: Boolean,
    localFile: File,
    onProgress: (String) -> Unit
  ) {
    // Named after the pid and the time, because a dump that failed to clean up must not be the file the
    // next one reads.
    val remotePath = "$REMOTE_DIRECTORY/shark-explorer-${process.processId}-${System.nanoTime()}.hprof"
    val gcArguments = if (withGc) listOf("-g") else emptyList()
    val bitmapArguments = if (withBitmaps) listOf("-b", BITMAP_FORMAT) else emptyList()
    try {
      onProgress(
        (if (withGc) {
          "Collecting the garbage of ${process.name}, then dumping its heap"
        } else {
          "Dumping the heap of ${process.name}"
        }) + if (withBitmaps) {
          " with its bitmaps"
        } else {
          ", which on API ${device.sdkInt} can't include its bitmaps"
        }
      )
      adb.run(
        listOf("-s", device.serialNumber, "shell", "am", "dumpheap") + gcArguments + bitmapArguments +
          listOf(process.processId.toString(), remotePath)
      ).orFailToDump(process, device)
      val byteCount = awaitDumpWritten(device, remotePath, onProgress)
      onProgress("Pulling ${formatByteSize(byteCount)}")
      adb.run("-s", device.serialNumber, "pull", remotePath, localFile.absolutePath)
        .orFail("pull the heap dump from ${device.description}")
    } finally {
      // Best effort: the dump is tens of megabytes of someone's device, but a failure to delete it is
      // not worth losing what was pulled over.
      adb.run("-s", device.serialNumber, "shell", "rm", "-f", remotePath)
    }
  }

  /**
   * Waits for the heap dump at [remotePath] to stop growing, and returns how big it ended up.
   *
   * `am dumpheap` waits for the process it asked on the Android versions that print "Waiting for dump to
   * finish" and returns straight away on the ones that don't, so a pull started when it returns can pull
   * half a file. A size that hasn't changed since the last look is the only signal there is, so that's
   * what this waits for; on a device that did wait it costs one `stat` and one sleep.
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

  /**
   * What `am dumpheap` said, with the one refusal that doesn't explain itself explained.
   *
   * A process that isn't debuggable can't be dumped on a build that isn't, and what the framework
   * answers — a `SecurityException` — reads like something went wrong rather than like the answer being
   * no. Which of the two to fix is worth saying, since either one is enough.
   */
  private fun AdbOutput.orFailToDump(
    process: DeviceProcess,
    device: AndroidDevice
  ): String = try {
    orFail("dump the heap of ${process.name} on ${device.description}")
  } catch (failure: AdbFailureException) {
    val message = failure.message.orEmpty()
    if (NOT_DEBUGGABLE in message) {
      throw AdbFailureException(
        "$message. A heap can only be asked for from an app built debuggable, which a release build " +
          "isn't, or on a device whose build is (`ro.debuggable=1`), which this one's isn't."
      )
    }
    throw failure
  }

  companion object {

    /**
     * Where `am dumpheap -b` was added, so the first Android version a native bitmap's pixels can be
     * fetched off at all. See [HeapBitmaps].
     */
    const val MIN_BITMAP_DUMP_SDK_INT = 35

    /**
     * Where `am dumpheap -g` was added, so the first Android version whose garbage can be collected
     * before the dump is written. An older one refuses the whole command with `Error: Unknown option`,
     * which is why this is asked before it's passed.
     */
    const val MIN_GC_BEFORE_DUMP_SDK_INT = 27

    /** What `-b` is asked for. PNG is lossless, and its header is what a pointer match is checked against. */
    private const val BITMAP_FORMAT = "png"

    /** Writable by the shell and readable by an app being dumped, which is what `am dumpheap` needs. */
    private const val REMOTE_DIRECTORY = "/data/local/tmp"

    private const val SIZE_POLL_MILLIS = 500L
    private const val MAX_SIZE_POLLS = 120

    /** What comes between an app's package and the name of one of its extra processes. */
    private const val PROCESS_NAME_SEPARATOR = ':'

    /** How the framework says no to dumping a process that isn't debuggable. */
    private const val NOT_DEBUGGABLE = "not debuggable"

    /** What `pm list packages` puts in front of each package name. */
    private const val PACKAGE_LINE_PREFIX = "package:"
  }
}

/**
 * Asks a live process itself for the compressed images of its bitmaps, which is what it takes on the
 * Android versions whose heap dumps can't carry them ([DeviceHeapDumps.MIN_BITMAP_DUMP_SDK_INT]).
 *
 * An interface here and implemented in `shark-explorer-jdwp` because the one implementation is a JDI
 * client, and `com.sun.jdi` is part of a desktop JDK rather than of Android — where this module has to
 * stay loadable.
 */
fun interface BitmapDebugger {

  /**
   * The images, keyed the same way `am dumpheap -b png` keys them: by the native pointer of the bitmap
   * each shows, so that either source joins onto the bitmaps of a heap dump the same way.
   *
   * @throws AdbFailureException when the process can't be reached or asked.
   */
  fun fetchBitmaps(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  ): NativeBitmapPixels
}

/**
 * Asks a live process itself to collect its garbage, which is what it takes on the Android versions with
 * no `am dumpheap -g` to ask with ([DeviceHeapDumps.MIN_GC_BEFORE_DUMP_SDK_INT]).
 *
 * Separate from [BitmapDebugger] because the two are about different Android versions and different
 * questions — one is every device below API 27, the other every device below API 35 — even though the one
 * implementation of each attaches the same way. An interface here and implemented in
 * `shark-explorer-jdwp` for the same reason as that one: `com.sun.jdi` is part of a desktop JDK rather
 * than of Android, where this module has to stay loadable.
 */
fun interface GcDebugger {

  /**
   * Collects, and returns once the process has finished collecting rather than once it has been asked.
   * Whatever the process frees stays freed, so the caller can dump its heap straight afterwards.
   *
   * @throws AdbFailureException when the process can't be reached or asked.
   */
  fun collectGarbage(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  )
}

/** One process running on a device. */
class DeviceProcess(
  val processId: Int,
  val name: String
) {

  /**
   * Whether this is one of the system image's own processes rather than an app someone installed.
   *
   * By the package name, which is all `ps` gives: the system's apps and Google's are the ones under
   * these three, and there are thirty of them running to the one being worked on. None of them is built
   * debuggable, so on a device that isn't either they are the ones that will refuse to be dumped.
   */
  val isSystemApp: Boolean get() = SYSTEM_PACKAGE_PREFIXES.any { name.startsWith(it) }

  private companion object {
    /** `com.google.process.gservices` and its like are the system's too, and match none of the others. */
    val SYSTEM_PACKAGE_PREFIXES =
      listOf("android.", "com.android.", "com.google.android.", "com.google.process.")
  }
}

/** The pid and name of each line of `ps -A -o PID,NAME`, skipping its header. */
internal fun parseProcessLines(output: String): List<DeviceProcess> = output.lines()
  .mapNotNull { line ->
    val columns = line.trim().split(PROCESS_COLUMNS, limit = 2)
    val processId = columns.firstOrNull()?.toIntOrNull()
    if (processId == null || columns.size < 2) null else DeviceProcess(processId, columns[1].trim())
  }

private val PROCESS_COLUMNS = Regex("\\s+")
