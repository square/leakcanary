package shark.dive.jdwp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import shark.dive.AdbFailureException
import shark.dive.AndroidDevice
import shark.dive.DeviceProcess

/**
 * Covers the half of a collection that needs no app: the port `adb` hands out, and what a connection that
 * goes nowhere says.
 *
 * Everything past attaching runs in a live app and can't be faked — a JDI client talks to a real VM or to
 * nothing — so what a real one answers is in `notes/decisions.md` instead.
 */
class JdwpGcTest {

  @Test fun `a process that won't take a debugger is told what does`() {
    val adb = fakeAdb()

    assertThatThrownBy { JdwpGc(adb).collectGarbage(device(), process()) {} }
      .isInstanceOf(AdbFailureException::class.java)
      .hasMessageContaining("Only a debuggable app lets a debugger in")
  }

  @Test fun `the forwarded port is closed again, whatever came of the collection`() {
    val adb = fakeAdb()

    runCatching { JdwpGc(adb).collectGarbage(device(), process()) {} }

    // An attach that failed has no session for the caller to close, so closing the forward is this
    // side's job — a forward outlives what set it up.
    assertThat(adb.commands.last()).isEqualTo("-s emulator-5554 forward --remove tcp:$CLOSED_PORT")
  }

  @Test fun `an adb that answers something other than a port says what it answered`() {
    val adb = fakeAdb(forwardOutput = "cannot bind listener")

    assertThatThrownBy { JdwpGc(adb).collectGarbage(device(), process()) {} }
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
    fingerprint = "google/sdk/sdk:8.0.0/OSR1/4443079:userdebug/test-keys",
    model = "Pixel 6",
    // Below the API 27 `am dumpheap -g` arrived in, which is the whole reason this class exists.
    sdkInt = 26,
    isDebuggableBuild = true
  )

  private fun process() = DeviceProcess(processId = 1201, name = "com.example")

  companion object {
    /** Reserved by IANA for nothing, and below the range anything on a desktop listens on. */
    private const val CLOSED_PORT = 1
  }
}
