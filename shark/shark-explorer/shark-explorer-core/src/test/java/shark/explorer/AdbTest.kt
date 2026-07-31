package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.Test

/** Covers reading `adb`'s output, and matching what it says about a device against a heap dump. */
class AdbTest {

  @Test fun `the devices adb is connected to are read off its output`() {
    val adb = FakeAdb(
      "devices" to "List of devices attached\nemulator-5554\tdevice\n",
      "-s emulator-5554 shell getprop" to """
        [ro.build.fingerprint]: [google/sdk_gphone64_arm64/emu64a:16/BP22.250325.006/13207872:user/release-keys]
        [ro.build.version.sdk]: [36]
        [ro.product.model]: [sdk_gphone64_arm64]
      """.trimIndent()
    )

    val devices = adb.connectedDevices()

    assertThat(devices).hasSize(1)
    val device = devices.single()
    assertThat(device.serialNumber).isEqualTo("emulator-5554")
    assertThat(device.isReady).isTrue()
    assertThat(device.model).isEqualTo("sdk_gphone64_arm64")
    assertThat(device.sdkInt).isEqualTo(36)
    assertThat(device.description).isEqualTo("sdk_gphone64_arm64 · API 36 · emulator-5554")
  }

  @Test fun `a device that is not ready is not asked what it is`() {
    // `adb shell` against an unauthorized device waits for it to be authorized, which would hang the
    // window on a device nobody can use anyway.
    val adb = FakeAdb("devices" to "List of devices attached\nZY223KHXYZ\tunauthorized\n")

    val device = adb.connectedDevices().single()

    assertThat(device.isReady).isFalse()
    assertThat(device.model).isNull()
    assertThat(adb.commands).containsExactly("devices")
    assertThat(device.description).isEqualTo("ZY223KHXYZ")
  }

  @Test fun `the lines adb prints about itself are not devices`() {
    val output = """
      * daemon not running; starting now at tcp:5037
      * daemon started successfully
      List of devices attached
      emulator-5554	device
      ZY223KHXYZ	offline

    """.trimIndent()

    assertThat(parseDeviceLines(output))
      .containsExactly("emulator-5554" to "device", "ZY223KHXYZ" to "offline")
  }

  @Test fun `a property with an empty value is still a property`() {
    val properties = parseProperties(
      """
        [ro.product.model]: [Pixel 9]
        [persist.sys.locale]: []
        not a property line
      """.trimIndent()
    )

    assertThat(properties).containsExactly(
      entry("ro.product.model", "Pixel 9"),
      entry("persist.sys.locale", "")
    )
  }

  @Test fun `the same fingerprint is the same build of the same model`() {
    val device = device(fingerprint = FINGERPRINT, model = "Pixel 9", sdkInt = 36)

    assertThat(device.matchTo(origin(fingerprint = FINGERPRINT, model = "Pixel 9", sdkInt = 36)))
      .isEqualTo(DeviceMatch.SAME_BUILD)
  }

  @Test fun `the same model on another build is worth offering, and worth saying`() {
    val device = device(fingerprint = FINGERPRINT, model = "Pixel 9", sdkInt = 36)

    assertThat(device.matchTo(origin(fingerprint = "another/build", model = "Pixel 9", sdkInt = 36)))
      .isEqualTo(DeviceMatch.SAME_MODEL)
  }

  @Test fun `the same model on another Android version is another device`() {
    // A dump of API 35 read against a device that has since been updated: the bitmaps of the process it
    // came from are not on this device.
    val device = device(fingerprint = FINGERPRINT, model = "Pixel 9", sdkInt = 36)

    assertThat(device.matchTo(origin(fingerprint = "another/build", model = "Pixel 9", sdkInt = 35)))
      .isEqualTo(DeviceMatch.OTHER)
  }

  @Test fun `a heap dump that records no device matches none`() {
    // Which is every non Android heap dump, and every dump whose `android.os.Build` was stripped.
    val device = device(fingerprint = FINGERPRINT, model = "Pixel 9", sdkInt = 36)

    assertThat(device.matchTo(origin(fingerprint = null, model = null, sdkInt = null)))
      .isEqualTo(DeviceMatch.OTHER)
  }

  @Test fun `a shell command that failed is a failure, whatever adb exited with`() {
    // `adb shell` reports the exit code of the shell it ran, so a command the device refused comes back
    // as a success with the complaint on the output.
    val output = AdbOutput(exitCode = 0, text = "Error: Unknown process: com.example\n")

    assertThatThrownBy { output.orFail("dump the heap") }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessage("Could not dump the heap: Error: Unknown process: com.example")
  }

  @Test fun `a non zero exit is a failure even when nothing was printed`() {
    assertThatThrownBy { AdbOutput(exitCode = 1, text = "").orFail("list the devices") }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessage("Could not list the devices: adb exited with 1")
  }

  private fun device(
    fingerprint: String?,
    model: String?,
    sdkInt: Int?
  ) = AndroidDevice(
    serialNumber = "emulator-5554",
    state = "device",
    fingerprint = fingerprint,
    model = model,
    sdkInt = sdkInt
  )

  private fun origin(
    fingerprint: String?,
    model: String?,
    sdkInt: Int?
  ) = HeapDumpOrigin(
    sdkInt = sdkInt,
    fingerprint = fingerprint,
    manufacturer = "Google",
    model = model,
    processName = "com.example"
  )

  companion object {
    private const val FINGERPRINT = "google/tokay/tokay:16/BP31.250610.004/13698546:user/release-keys"
  }
}
