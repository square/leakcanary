package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dump

/**
 * Covers going back to the process a heap dump came from: which of its processes to ask, and what comes
 * back from asking.
 *
 * Everything here is driven through a [FakeAdb], so the flow is tested without a device — which is the
 * point of `adb` being an interface. What a real device answers is in `notes/bitmaps.md`.
 */
class DeviceBitmapsTest {

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

    val processes = DeviceBitmaps(adb).matchingProcesses(device(), origin())

    // The app's other processes are offered too: the one that wrote the dump may have died and been
    // restarted, and a `:remote` service holds bitmaps of its own.
    assertThat(processes.map { it.name })
      .containsExactly("com.example", "com.example:remote", "com.example:leakcanary")
    assertThat(processes.first().processId).isEqualTo(1201)
  }

  @Test fun `a dump that doesn't say which process wrote it matches none`() {
    val adb = FakeAdb(PS_COMMAND to "PID NAME\n1201 com.example\n")

    val processes = DeviceBitmaps(adb).matchingProcesses(
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

    val pixels = DeviceBitmaps(adb).fetchBitmaps(device(), process())

    assertThat(pixels.format).isEqualTo(EncodedImageFormat.PNG)
    assertThat(pixels.bytesByNativePointer).containsOnlyKeys(NATIVE_POINTER)
    assertThat(pixels.bytesByNativePointer.getValue(NATIVE_POINTER)).isEqualTo(png)
  }

  @Test fun `the heap dump is asked for with its bitmaps, and taken off the device afterwards`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))

    DeviceBitmaps(adb).fetchBitmaps(device(), process())

    // Tens of megabytes of someone's device, and a dump nobody asked to keep.
    assertThat(adb.commands.first()).contains("am dumpheap -b png 1201 /data/local/tmp/")
    assertThat(adb.commands.last()).contains("shell rm -f /data/local/tmp/")
  }

  @Test fun `progress is reported for each step, because each of them takes seconds`() {
    val adb = fakeDeviceWith(mapOf(NATIVE_POINTER to pngBytes(width = 8, height = 8)))
    val progress = mutableListOf<String>()

    DeviceBitmaps(adb).fetchBitmaps(device(), process()) { progress += it }

    assertThat(progress.first()).isEqualTo("Dumping the heap of com.example with its bitmaps")
    assertThat(progress.last()).isEqualTo("Reading the bitmaps out of it")
  }

  @Test fun `a device too old to compress its bitmaps says so rather than dumping its heap`() {
    val adb = FakeAdb()

    assertThatThrownBy {
      DeviceBitmaps(adb).fetchBitmaps(device(sdkInt = 34), process())
    }.isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("runs API 34")
      .hasMessageContaining("API ${DeviceBitmaps.MIN_BITMAP_DUMP_SDK_INT}")

    assertThat(adb.commands).isEmpty()
  }

  @Test fun `a dump that came back without the compressed images says that, not nothing`() {
    // Which is what a device that ignored `-b` looks like, and the failure that would otherwise read as
    // the fetch having quietly done nothing.
    val adb = fakeDeviceWith(dumpedImages = null)

    assertThatThrownBy { DeviceBitmaps(adb).fetchBitmaps(device(), process()) }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("no `Bitmap.dumpData` in it")
  }

  @Test fun `a process that isn't there is a failure, though adb exits successfully`() {
    val adb = FakeAdb("$DEVICE shell am dumpheap" to "Error: Unknown process: 1201")

    assertThatThrownBy { DeviceBitmaps(adb).fetchBitmaps(device(), process()) }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("Unknown process")

    // Nothing was pulled and nothing waited for: the dump the rest of the flow reads doesn't exist.
    assertThat(adb.commands.none { it.contains(" pull ") }).isTrue()
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

  private fun device(sdkInt: Int = 36) = AndroidDevice(
    serialNumber = "emulator-5554",
    state = "device",
    fingerprint = "google/tokay/tokay:16/BP31.250610.004/13698546:user/release-keys",
    model = "Pixel 9",
    sdkInt = sdkInt
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
    private const val NATIVE_POINTER = 0x7f4321L
  }
}
