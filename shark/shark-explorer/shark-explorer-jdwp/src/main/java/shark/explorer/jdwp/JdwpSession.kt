package shark.explorer.jdwp

import com.sun.jdi.Bootstrap
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.request.EventRequest
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import shark.explorer.Adb
import shark.explorer.AdbFailureException
import shark.explorer.AndroidDevice
import shark.explorer.DeviceProcess

/**
 * A debugger attached to one process of one device, for as long as it takes to ask the process something
 * a heap dump can't answer.
 *
 * Everything here is what both [JdwpBitmaps] and [JdwpGc] need before they can ask anything, and none of
 * it is about what they ask: a port forward that has to be removed again whatever happens, an attach whose
 * one likely failure is worth wording, and a thread stopped somewhere the process can be made to run code.
 *
 * It charges the same price `am dumpheap` does, which is **the app has to be debuggable**: that is what
 * opens a JDWP connection at all. One debugger at a time, too, so an app Android Studio is attached to
 * can't be reached. And the app is suspended from [attach] until [close], so a session is worth keeping
 * short.
 */
internal class JdwpSession private constructor(
  private val adb: Adb,
  private val device: AndroidDevice,
  private val process: DeviceProcess,
  private val port: Int,
  val virtualMachine: VirtualMachine
) : Closeable {

  /**
   * A thread of the process stopped somewhere it can be made to run code, with every other thread of it
   * stopped too.
   *
   * Suspending the process is not enough. ART refuses to invoke a method on a thread it stopped wherever
   * that thread happened to be — `IncompatibleThreadStateException` — so what is needed is a thread
   * stopped *by an event*, and the event that says least about the app is the next method entry anywhere
   * in it. A count filter of one means exactly one ever fires, so the app is suspended once and nothing
   * stays instrumented while it is being asked.
   *
   * Then the app has to run something, and an app that is idle or in the background runs nothing at all.
   * `dumpsys meminfo` is the nudge: the framework answers it by calling into the app over binder, so it
   * runs code in there whether or not the app is on screen, and unlike anything driven through the UI it
   * changes nothing about what the app is showing.
   */
  fun awaitSafePoint(): ThreadReference {
    val request = virtualMachine.eventRequestManager().createMethodEntryRequest().apply {
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
          "and a process can only be asked to run something at a point where it was already running. " +
          "Touching the app makes it run something."
      )
    }
  }

  /**
   * Detaches, which is what resumes the app, and takes the port forward back down.
   *
   * Both halves matter and neither may be skipped over a failure in the other: a client that walks away
   * from a suspended process leaves it frozen, and a forward outlives what set it up, so one left behind
   * is one more dead entry in `adb forward --list` for every session there has ever been.
   */
  override fun close() {
    try {
      virtualMachine.dispose()
    } finally {
      adb.run("-s", device.serialNumber, "forward", "--remove", "tcp:$port")
    }
  }

  /** The thread of the first method entry event, or null if none arrives in the budget. */
  private fun awaitMethodEntry(): ThreadReference? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFE_POINT_BUDGET_SECONDS)
    while (System.nanoTime() < deadline) {
      val events = virtualMachine.eventQueue().remove(EVENT_POLL_MILLIS) ?: continue
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
     * Attaches to [process], leaving nothing behind if it can't: the forward is opened first and is the
     * caller's to remove, so an attach that fails removes it here rather than leaking it to a caller that
     * has no session to close.
     */
    fun attach(
      adb: Adb,
      device: AndroidDevice,
      process: DeviceProcess
    ): JdwpSession {
      val port = forwardJdwp(adb, device, process)
      val virtualMachine = try {
        attach(port, device, process)
      } catch (throwable: Throwable) {
        adb.run("-s", device.serialNumber, "forward", "--remove", "tcp:$port")
        throw throwable
      }
      return JdwpSession(adb, device, process, port, virtualMachine)
    }

    /**
     * A local TCP port `adb` forwards to the JDWP connection of [process].
     *
     * Asks for `tcp:0`, which has `adb` pick a free port and print it, rather than picking one here and
     * racing whatever else on the machine opens sockets. Note that a forward to a process that isn't
     * debuggable is set up just as happily; nothing says so until something connects.
     */
    private fun forwardJdwp(
      adb: Adb,
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
            "${exception.message}. Only a debuggable app lets a debugger in, which a release build of " +
            "one isn't, and only one debugger at a time — so an app Android Studio is debugging is taken."
        )
      }
    }
  }
}

/** No `INVOKE_SINGLE_THREADED`, which is what lets the app's other threads run during a call. */
internal const val RESUME_OTHER_THREADS = 0
