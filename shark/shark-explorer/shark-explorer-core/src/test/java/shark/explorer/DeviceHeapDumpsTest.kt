package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.dump

/**
 * Covers going back to the process a heap dump came from: which of its processes to ask, and what comes
 * back from asking.
 *
 * Everything here is driven through a [FakeAdb], so the flow is tested without a device — which is the
 * point of `adb` being an interface. What a real device answers is in `notes/bitmaps.md`.
 */
class DeviceHeapDumpsTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the process the dump named comes before the app's other processes`() {
    val adb = FakeAdb(
      PS_COMMAND to """
        PID NAME
        1200 com.example:remote
        1201 com.example
        1202 com.example:leakcanary
        1300 com.example.other
        1400 system_server
      """.trimIndent()
    )

    val processes = DeviceHeapDumps(adb).matchingProcesses(device(), origin())

    // The app's other processes are offered too: the one that wrote the dump may have died and been
    // restarted, and a `:remote` service holds bitmaps of its own.
    assertThat(processes.map { it.name })
      .containsExactly("com.example", "com.example:remote", "com.example:leakcanary")
    assertThat(processes.first().processId).isEqualTo(1201)
  }

  @Test fun `a dump that doesn't say which process wrote it matches none`() {
    val adb = FakeAdb(PS_COMMAND to "PID NAME\n1201 com.example\n")

    val processes = DeviceHeapDumps(adb).matchingProcesses(
      device(),
      HeapDumpOrigin(
        sdkInt = 36,
        fingerprint = null,
        manufacturer = null,
        model = null,
        processName = null
      )
    )

    assertThat(processes).isEmpty()
    // Nothing to match means nothing to ask the device about.
    assertThat(adb.commands).isEmpty()
  }

  @Test fun `the pixels come back keyed by the native pointer of the bitmap they belong to`() {
    val png = pngBytes(width = 8, height = 8)
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to png))

    val pixels = DeviceHeapDumps(adb).fetchBitmaps(device(), process())

    assertThat(pixels.format).isEqualTo(EncodedImageFormat.PNG)
    assertThat(pixels.bytesByNativePointer).containsOnlyKeys(NATIVE_POINTER)
    assertThat(pixels.bytesByNativePointer.getValue(NATIVE_POINTER)).isEqualTo(png)
  }

  @Test fun `the heap dump is asked for with its bitmaps, and taken off the device afterwards`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))

    DeviceHeapDumps(adb).fetchBitmaps(device(), process())

    // Tens of megabytes of someone's device, and a dump nobody asked to keep.
    assertThat(adb.commands.first()).contains("am dumpheap -b png 1201 /data/local/tmp/")
    assertThat(adb.commands.last()).contains("shell rm -f /data/local/tmp/")
  }

  @Test fun `progress is reported for each step, because each of them takes seconds`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))
    val progress = mutableListOf<String>()

    DeviceHeapDumps(adb).fetchBitmaps(device(), process()) { progress += it }

    assertThat(progress.first()).isEqualTo("Dumping the heap of com.example with its bitmaps")
    assertThat(progress.last()).isEqualTo("Reading the bitmaps out of it")
  }

  @Test fun `a device too old to compress its bitmaps says so rather than dumping its heap`() {
    val adb = FakeAdb()

    assertThatThrownBy {
      DeviceHeapDumps(adb).fetchBitmaps(device(sdkInt = 34), process())
    }.isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("runs API 34")
      .hasMessageContaining("API ${DeviceHeapDumps.MIN_BITMAP_DUMP_SDK_INT}")

    assertThat(adb.commands).isEmpty()
  }

  @Test fun `a device too old to compress its bitmaps is handed to the debugger instead`() {
    val adb = FakeAdb()
    val png = pngBytes(width = 8, height = 8)
    var asked: String? = null
    val debugger = BitmapDebugger { debuggedDevice, debuggedProcess, _ ->
      asked = "${debuggedProcess.name} on ${debuggedDevice.description}"
      NativeBitmapPixels(EncodedImageFormat.PNG, mapOf(NATIVE_POINTER to png))
    }

    val pixels = DeviceHeapDumps(adb, debugger).fetchBitmaps(device(sdkInt = 34), process())

    assertThat(asked).isEqualTo("com.example on Pixel 9 · API 34 · emulator-5554")
    assertThat(pixels.bytesByNativePointer).containsOnlyKeys(NATIVE_POINTER)
    // Not dumped as well: a dump of that device comes back with no pixels whatever it was asked for.
    assertThat(adb.commands).isEmpty()
  }

  @Test fun `a device that can put bitmaps in a heap dump is dumped rather than debugged`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))
    // Dumping costs the app the dump it was going to take anyway, where a debugger stops it and runs
    // code in it, so the debugger is the fallback and not the way.
    val debugger = BitmapDebugger { _, _, _ -> error("The debugger was used on a device that can dump") }

    DeviceHeapDumps(adb, debugger).fetchBitmaps(device(), process())

    assertThat(adb.commands.first()).contains("am dumpheap -b png")
  }

  @Test fun `a dump that came back without the compressed images says that, not nothing`() {
    // Which is what a device that ignored `-b` looks like, and the failure that would otherwise read as
    // the fetch having quietly done nothing.
    val adb = fakeDeviceWith(dumpedImages = null)

    assertThatThrownBy { DeviceHeapDumps(adb).fetchBitmaps(device(), process()) }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("no `Bitmap.dumpData` in it")
  }

  @Test fun `a process that isn't there is a failure, though adb exits successfully`() {
    val adb = FakeAdb("$DEVICE shell am dumpheap" to "Error: Unknown process: 1201")

    assertThatThrownBy { DeviceHeapDumps(adb).fetchBitmaps(device(), process()) }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("Unknown process")

    // Nothing was pulled and nothing waited for: the dump the rest of the flow reads doesn't exist.
    assertThat(adb.commands.none { it.contains(" pull ") }).isTrue()
  }

  @Test fun `a heap dump taken to be explored is asked for with its bitmaps, and kept`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))

    val heapDumpFile = DeviceHeapDumps(adb).dumpHeap(device(), process())

    assertThat(adb.commands.first()).contains("am dumpheap -g -b png 1201 /data/local/tmp/")
    // The explorer reads a heap dump lazily and for as long as it's open, so this one outlives the call
    // that pulled it — unlike the one a fetch of the bitmaps alone takes.
    assertThat(heapDumpFile).exists()
    heapDumpFile.openHeapGraph().use { graph ->
      assertThat(graph.findClassByName("android.graphics.Bitmap")).isNotNull()
    }
    heapDumpFile.delete()
  }

  @Test fun `a device too old to compress its bitmaps still gets its heap dumped`() {
    val adb = fakeDeviceWith(dumpedImages = null)
    val progress = mutableListOf<String>()

    val heapDumpFile = DeviceHeapDumps(adb).dumpHeap(device(sdkInt = 30), process()) { progress += it }

    // Where the pixels of a bitmap are is the one thing an API level changes about a heap dump, and it
    // changes nothing about the rest of it: a dump without them is still worth taking.
    assertThat(adb.commands.first()).doesNotContain("-b png")
    assertThat(progress.first()).isEqualTo(
      "Collecting the garbage of com.example, then dumping its heap, which on API 30 can't include " +
        "its bitmaps"
    )
    assertThat(heapDumpFile).exists()
    heapDumpFile.delete()
  }

  @Test fun `the garbage is collected before a heap dump taken to be explored`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))
    val progress = mutableListOf<String>()

    // Nothing else collects it: `am dumpheap` writes whatever is in the heap, and ART's hprof dumper
    // suspends the runtime rather than collecting. Measured on a 125 MB dump of a real app, the
    // difference is 12.0 MB of uncollected garbage against 0.29 MB.
    val heapDumpFile = DeviceHeapDumps(adb).dumpHeap(device(), process()) { progress += it }

    assertThat(adb.commands.first()).contains("am dumpheap -g ")
    assertThat(progress.first()).isEqualTo(
      "Collecting the garbage of com.example, then dumping its heap with its bitmaps"
    )
    heapDumpFile.delete()
  }

  @Test fun `a device too old for the flag is collected through the debugger instead`() {
    val adb = fakeDeviceWith(dumpedImages = null)
    var asked: String? = null
    val gcDebugger = GcDebugger { collectedDevice, collectedProcess, onProgress ->
      asked = "${collectedProcess.name} on ${collectedDevice.description}"
      onProgress("Collecting the garbage of ${collectedProcess.name}")
    }

    // `-g` arrived in Android 8.1, and an older device refuses the whole command over an unknown option
    // rather than ignoring it. The process still has `Runtime.gc()` in it though, so a debugger can ask.
    val heapDumpFile = DeviceHeapDumps(adb, gcDebugger = gcDebugger)
      .dumpHeap(device(sdkInt = OLD_SDK_INT), process())

    assertThat(asked).isEqualTo("com.example on Pixel 9 · API $OLD_SDK_INT · emulator-5554")
    assertThat(adb.commands.first { it.contains("am dumpheap") }).doesNotContain("-g")
    assertThat(heapDumpFile).exists()
    heapDumpFile.delete()
  }

  @Test fun `a device new enough for the flag is not handed to the debugger`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))
    // Asking through the dump costs the app the dump it was going to take anyway, where a debugger stops
    // every thread of it, so the debugger is the fallback and not the way. Same rule as for bitmaps.
    val gcDebugger = GcDebugger { _, _, _ -> error("The debugger was used on a device that has `-g`") }

    val heapDumpFile = DeviceHeapDumps(adb, gcDebugger = gcDebugger).dumpHeap(device(), process())

    assertThat(adb.commands.first()).contains("am dumpheap -g ")
    heapDumpFile.delete()
  }

  @Test fun `a device too old for the flag with no debugger is dumped without collecting`() {
    val adb = fakeDeviceWith(dumpedImages = null)

    val heapDumpFile = DeviceHeapDumps(adb).dumpHeap(device(sdkInt = OLD_SDK_INT), process())

    // A dump with garbage in it beats no dump at all.
    assertThat(adb.commands.first()).doesNotContain("-g")
    assertThat(heapDumpFile).exists()
    heapDumpFile.delete()
  }

  @Test fun `a collection that failed doesn't fail the dump`() {
    val adb = fakeDeviceWith(dumpedImages = null)
    val gcDebugger = GcDebugger { _, _, _ -> error("The debugger could not attach") }

    val heapDumpFile = DeviceHeapDumps(adb, gcDebugger = gcDebugger)
      .dumpHeap(device(sdkInt = OLD_SDK_INT), process())

    // The dump is the expensive thing and the one that was asked for; a debugger that can't attach must
    // not turn a dump with garbage in it into no dump at all.
    assertThat(heapDumpFile).exists()
    assertThat(adb.commands.any { it.contains("am dumpheap") }).isTrue()
    heapDumpFile.delete()
  }

  @Test fun `the garbage is collected before the dump is asked for, not after`() {
    val adb = fakeDeviceWith(dumpedImages = null)
    var commandsWhenCollected = 0
    val gcDebugger = GcDebugger { _, _, _ -> commandsWhenCollected = adb.commands.size }

    val heapDumpFile = DeviceHeapDumps(adb, gcDebugger = gcDebugger)
      .dumpHeap(device(sdkInt = OLD_SDK_INT), process())

    // Collecting after the dump would collect nothing that is in it.
    assertThat(commandsWhenCollected).isZero()
    heapDumpFile.delete()
  }

  @Test fun `the bitmaps of a live process are fetched without collecting first`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))

    DeviceHeapDumps(adb).fetchBitmaps(device(), process())

    // This dump is read for the pixels of the bitmaps of a dump taken earlier, and collecting first is
    // how a bitmap that is still in that one stops being in this one.
    assertThat(adb.commands.first()).doesNotContain("-g")
  }

  @Test fun `a heap dump that failed leaves no local file behind`() {
    val adb = FakeAdb("$DEVICE shell am dumpheap" to "Error: Unknown process: 1201")
    val temporaryFilesBefore = temporaryHeapDumps()

    assertThatThrownBy { DeviceHeapDumps(adb).dumpHeap(device(), process()) }
      .isInstanceOf(AdbFailureException::class.java)

    // The file is created before the dump is asked for, so a failure has one to clean up.
    assertThat(temporaryHeapDumps()).isEqualTo(temporaryFilesBefore)
  }

  @Test fun `a process that can't be dumped is told why, since the framework doesn't say`() {
    val adb = FakeAdb(
      "$DEVICE shell am dumpheap" to """
        Exception occurred while executing 'dumpheap':
        java.lang.SecurityException: Process not debuggable: com.example
          at com.android.server.am.ActivityManagerService.enforceDebuggable(ActivityManagerService.java:1)
      """.trimIndent()
    )

    assertThatThrownBy { DeviceHeapDumps(adb).dumpHeap(device(), process()) }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("Process not debuggable: com.example")
      // Both ways past it, since either one is enough and the app is only one of them.
      .hasMessageContaining("an app built debuggable")
      .hasMessageContaining("ro.debuggable=1")
      // The twelve frames under it say nothing anyone at a window can act on.
      .hasMessageNotContaining("at com.android.server")
  }

  @Test fun `the processes offered for a fresh dump are the apps, the app being worked on first`() {
    val adb = FakeAdb(
      PS_COMMAND to """
        PID NAME
        1 init
        320 surfaceflinger
        400 android.hardware.audio.service
        521 media.extractor
        914 com.android.systemui
        1046 com.google.android.apps.nexuslauncher
        1201 com.example
        1202 com.example:remote
        1500 kworker/u16:2
      """.trimIndent(),
      PACKAGES_COMMAND to """
        package:com.android.systemui
        package:com.google.android.apps.nexuslauncher
        package:com.example
      """.trimIndent()
    )

    val processes = DeviceHeapDumps(adb).appProcesses(device())

    // A native service reads exactly like a package — `media.extractor`, `android.hardware.audio.service`
    // — so what the device says is installed is what separates them from an app. The one being worked on
    // is the only process here that can really be dumped, so it belongs at the top.
    assertThat(processes.map { it.name }).containsExactly(
      "com.example",
      "com.example:remote",
      "com.android.systemui",
      "com.google.android.apps.nexuslauncher"
    )
  }

  @Test fun `the pid and name of each process are read off ps`() {
    val processes = parseProcessLines(
      """
        PID NAME
        1201 com.example
        not a process line
        1400 system_server
      """.trimIndent()
    )

    assertThat(processes.map { "${it.processId} ${it.name}" })
      .containsExactly("1201 com.example", "1400 system_server")
  }

  /**
   * An `adb` that behaves like a device asked for a heap dump with its bitmaps in it: the dump is
   * written, its size settles, and `pull` hands over a real heap dump holding [dumpedImages]. Null for a
   * device that wrote a dump without any, which is what ignoring `-b` looks like.
   */
  private fun fakeDeviceWith(dumpedImages: Map<Long, ByteArray>?): FakeAdb {
    val heapDumpFile = testFolder.newFile()
    heapDumpFile.dump {
      bitmapClass(dumpedImages?.let { bitmapDumpData(it) } ?: NULL_REFERENCE)
    }
    val dumpBytes = heapDumpFile.readBytes()
    return FakeAdb(
      mapOf(
        // A size that hasn't changed since the last look is the only signal that the process has
        // finished writing, so it's the same one twice.
        "$DEVICE shell stat" to { _: List<String> -> AdbOutput(0, dumpBytes.size.toString()) },
        "$DEVICE pull" to { arguments: List<String> ->
          File(arguments.last()).writeBytes(dumpBytes)
          AdbOutput(0, "1 file pulled")
        }
      )
    )
  }

  /**
   * The files a [DeviceHeapDumps.dumpHeap] of this test's process would have left, which go where the
   * platform puts a temp file: a dump that is kept is not the caller's to place.
   */
  private fun temporaryHeapDumps(): List<String> =
    File(System.getProperty("java.io.tmpdir")).list()
      .orEmpty()
      .filter { it.startsWith("com.example-1201-") }
      .sorted()

  private fun device(sdkInt: Int = 36) = AndroidDevice(
    serialNumber = "emulator-5554",
    state = "device",
    fingerprint = "google/tokay/tokay:16/BP31.250610.004/13698546:user/release-keys",
    model = "Pixel 9",
    sdkInt = sdkInt,
    isDebuggableBuild = false
  )

  private fun process() = DeviceProcess(processId = 1201, name = "com.example")

  private fun origin() = HeapDumpOrigin(
    sdkInt = 36,
    fingerprint = "google/tokay/tokay:16/BP31.250610.004/13698546:user/release-keys",
    manufacturer = "Google",
    model = "Pixel 9",
    processName = "com.example"
  )

  companion object {
    /** What every command in this test starts with, since all of them name the one device. */
    private const val DEVICE = "-s emulator-5554"

    private const val PS_COMMAND = "$DEVICE shell ps -A -o PID,NAME"
    private const val PACKAGES_COMMAND = "$DEVICE shell pm list packages"
    private const val NATIVE_POINTER = 0x7f4321L

    /** A device with no `am dumpheap -g`, which is what a debugger is the answer for. */
    private const val OLD_SDK_INT = DeviceHeapDumps.MIN_GC_BEFORE_DUMP_SDK_INT - 1
  }
}
