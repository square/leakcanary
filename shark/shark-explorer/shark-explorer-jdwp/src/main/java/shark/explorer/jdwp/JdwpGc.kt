package shark.explorer.jdwp

import com.sun.jdi.ClassType
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import shark.explorer.Adb
import shark.explorer.AdbFailureException
import shark.explorer.AndroidDevice
import shark.explorer.CommandLineAdb
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceProcess
import shark.explorer.GcDebugger

/**
 * Collects the garbage of a live process by attaching to it as a debugger and making it collect its own.
 *
 * This is what there is below API 27: nothing on the `am dumpheap` path collects, `-g` is the flag that
 * asks it to and it only arrived in Android 8.1 ([DeviceHeapDumps.MIN_GC_BEFORE_DUMP_SDK_INT]), and an
 * older device answers `Error: Unknown option: -g` and takes no dump at all. But the process still has
 * `Runtime.gc()` in it, so the way to a collected heap is the one [JdwpBitmaps] takes to a bitmap's
 * pixels: attach over JDWP and invoke the method. `com.sun.jdi` is part of the JDK, so this needs nothing
 * built for a device — no JVMTI agent, no NDK, nothing pushed and attached.
 *
 * What it runs is `Runtime.getRuntime().gc()`, `Thread.sleep(100)`, `System.runFinalization()`,
 * `Runtime.getRuntime().gc()` — LeakCanary's own `FinalizingInProcessGcTrigger`, and one call more than
 * `-g` runs. The sleep is there because there is no programmatic way to wait for the
 * `ReferenceQueueDaemon` to move references onto their queues, and 100 ms of the app running is what
 * AOSP's `FinalizationTester` settled on. A caller of `-g` has no way to ask for it; one that is inside
 * the process does.
 *
 * **It still doesn't collect quite as much as `-g`**, because the app runs between the detach and the
 * dump — dispose the connection, take the forward down, start `am dumpheap` — where `-g` collects and
 * dumps within the one call. Measured on one API 29 process, dumped three ways so that only the way
 * differs: 18.05 MB of it was unreachable with nothing collecting, 0.13 MB with `-g`, and 0.80 MB with
 * this. Closing that last 0.67 MB would mean dumping from inside the session with
 * `Debug.dumpHprofData`, and the app can't write anywhere the shell can then read — which is the whole
 * reason `am dumpheap` passes it a file descriptor. See `notes/decisions.md`.
 *
 * It charges the same price `am dumpheap` does, which is **the app has to be debuggable** — see
 * [JdwpSession], which is everything this and [JdwpBitmaps] both need before they can ask anything.
 *
 * Blocks on `adb` and on the app, so not from the UI thread.
 */
class JdwpGc(private val adb: Adb = CommandLineAdb()) : GcDebugger {

  override fun collectGarbage(
    device: AndroidDevice,
    process: DeviceProcess,
    onProgress: (String) -> Unit
  ) {
    onProgress("Attaching a debugger to ${process.name}")
    JdwpSession.attach(adb, device, process).use { session ->
      onProgress("Waiting for ${process.name} to run something")
      val thread = session.awaitSafePoint()
      onProgress("Collecting the garbage of ${process.name}")
      session.virtualMachine.collectGarbage(thread, device, process)
    }
  }
}

/**
 * Runs a collection in the target process, on [thread].
 *
 * Every call is made without `INVOKE_SINGLE_THREADED`, which resumes the app's other threads for its
 * duration and is what lets the daemons run at all: a suspended `ReferenceQueueDaemon` enqueues nothing
 * for the sleep to wait on, and a suspended `FinalizerDaemon` is a `runFinalization()` that never returns.
 *
 * `Runtime.getRuntime().gc()` rather than `System.gc()`, which on Android only collects every other time
 * it is called — libcore's version sets a flag and leaves the collecting to the next `runFinalization()`.
 * The `-g` sequence works out because its three calls hand that flag between them, but a caller that picks
 * its own calls has no reason to depend on that.
 */
private fun VirtualMachine.collectGarbage(
  thread: ThreadReference,
  device: AndroidDevice,
  process: DeviceProcess
) {
  val runtimeClass = classType(RUNTIME_CLASS_NAME, device, process)
  val threadClass = classType(THREAD_CLASS_NAME, device, process)
  val systemClass = classType(SYSTEM_CLASS_NAME, device, process)
  val getRuntime = runtimeClass.method("getRuntime", "()Ljava/lang/Runtime;", device, process)
  val gc = runtimeClass.method("gc", "()V", device, process)
  val sleep = threadClass.method("sleep", "(J)V", device, process)
  val runFinalization = systemClass.method("runFinalization", "()V", device, process)

  val runtime = runtimeClass.invokeMethod(thread, getRuntime, emptyList(), RESUME_OTHER_THREADS)
    as? ObjectReference ?: throw missing("a $RUNTIME_CLASS_NAME to collect with", device, process)

  runtime.invokeMethod(thread, gc, emptyList(), RESUME_OTHER_THREADS)
  threadClass.invokeMethod(
    thread, sleep, listOf(mirrorOf(REFERENCE_QUEUE_MILLIS)), RESUME_OTHER_THREADS
  )
  systemClass.invokeMethod(thread, runFinalization, emptyList(), RESUME_OTHER_THREADS)
  runtime.invokeMethod(thread, gc, emptyList(), RESUME_OTHER_THREADS)
}

private fun VirtualMachine.classType(
  name: String,
  device: AndroidDevice,
  process: DeviceProcess
): ClassType = classesByName(name).filterIsInstance<ClassType>().firstOrNull()
  ?: throw missing(name, device, process)

private fun ClassType.method(
  name: String,
  signature: String,
  device: AndroidDevice,
  process: DeviceProcess
): Method = concreteMethodByName(name, signature)
  ?: throw missing("${name()}.$name$signature", device, process)

private fun missing(
  what: String,
  device: AndroidDevice,
  process: DeviceProcess
) = AdbFailureException(
  "${process.name} on ${device.description} is missing $what, which no running Java process should be, " +
    "so its garbage can't be collected before its heap is dumped."
)

/**
 * How long the app is left running between the collection and the finalization, so that the
 * `ReferenceQueueDaemon` can move references onto their queues. Straight out of AOSP's
 * `FinalizationTester`, by way of LeakCanary's `FinalizingInProcessGcTrigger`.
 */
private const val REFERENCE_QUEUE_MILLIS = 100L

private const val RUNTIME_CLASS_NAME = "java.lang.Runtime"
private const val THREAD_CLASS_NAME = "java.lang.Thread"
private const val SYSTEM_CLASS_NAME = "java.lang.System"
