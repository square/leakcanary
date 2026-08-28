package shark.dive.jdwp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import shark.dive.AdbFailureException
import shark.dive.AndroidDevice
import shark.dive.DeviceProcess

/**
 * Covers the half of a fetch that needs no app: the port `adb` hands out, and what a connection that
 * goes nowhere says.
 *
 * Everything past attaching runs in a live app and can't be faked — a JDI client talks to a real VM or to
 * nothing — so what a real one answers is in `notes/bitmaps.md` instead.
 */
class JdwpBitmapsTest {

  @Test fun `a process that won't take a debugger is told what does`() {
    val adb = fakeAdb()

    assertThatThrownBy { JdwpBitmaps(adb).fetchBitmaps(device(), process()) {} }
      .isInstanceOf(AdbFailureException::class.java)
      // The failure this actually is, nine times in ten: `adb forward` sets up a forward to any pid at
      // all, and a process that doesn't speak JDWP only shows up as a connection nobody answers.
      .hasMessageContaining("Only a debuggable app lets a debugger in")
  }

  @Test fun `the forwarded port is closed again, whatever came of the fetch`() {
    val adb = fakeAdb()

    runCatching { JdwpBitmaps(adb).fetchBitmaps(device(), process()) {} }

    // A forward outlives what set it up, so one left behind is one more dead entry in `adb forward
    // --list` for every fetch that was ever tried.
    assertThat(adb.commands.last()).isEqualTo("-s emulator-5554 forward --remove tcp:$CLOSED_PORT")
  }

  @Test fun `an adb that answers something other than a port says what it answered`() {
    val adb = fakeAdb(forwardOutput = "cannot bind listener")

    assertThatThrownBy { JdwpBitmaps(adb).fetchBitmaps(device(), process()) {} }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("answered \"cannot bind listener\"")
  }

  /**
   * An `adb` that forwards a port nothing is listening on, which is what a forward to a process that
   * isn't debuggable behaves like.
   */
  private fun fakeAdb(forwardOutput: String = CLOSED_PORT.toString()) = RecordingAdb(
    mapOf("-s emulator-5554 forward tcp:0" to forwardOutput)
  )

  private fun device() = AndroidDevice(
    serialNumber = "emulator-5554",
    state = "device",
    fingerprint = "google/sdk/sdk:10/QSR1/6427100:user/release-keys",
    model = "Pixel 4",
    sdkInt = 29,
    isDebuggableBuild = false
  )

  private fun process() = DeviceProcess(processId = 1201, name = "com.example")

  companion object {
    /** Reserved by IANA for nothing, and below the range anything on a desktop listens on. */
    private const val CLOSED_PORT = 1
  }
}
