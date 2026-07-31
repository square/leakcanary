package shark.explorer

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The `adb` command line, as much of it as reaching into a live app needs.
 *
 * An interface so that everything built on it — which devices are connected, which of them wrote the
 * heap dump being read, whether the process is still running — is testable without a device. See
 * [CommandLineAdb] for the one that shells out.
 */
fun interface Adb {

  /**
   * Runs `adb` with [arguments], waits for it, and returns what it said. Its two output streams come
   * back as one, because `adb` and the commands it forwards to a device disagree about which one an
   * error belongs on and the message is worth having either way.
   *
   * @throws AdbUnavailableException when `adb` itself couldn't be run.
   */
  fun run(arguments: List<String>): AdbOutput

  fun run(vararg arguments: String): AdbOutput = run(arguments.toList())
}

/** What one `adb` command said, and whether it worked. */
class AdbOutput(
  val exitCode: Int,
  val text: String
) {
  val isSuccess: Boolean get() = exitCode == 0

  /**
   * The output of a command that has to have worked.
   *
   * `adb` exits 0 for some shell commands that failed, because the exit code it reports is the shell's,
   * so a line of the output saying no is a failure as much as a non zero exit is.
   */
  fun orFail(what: String): String {
    val failure = failureLine()
    if (!isSuccess || failure != null) {
      throw AdbFailureException(
        "Could not $what: ${failure ?: text.trim().ifEmpty { "adb exited with $exitCode" }}"
      )
    }
    return text
  }

  /**
   * The one line of the output worth reading, or null when nothing in it says a failure.
   *
   * Two shapes, because `am` refuses in two ways: `Error: Unknown option: -b`, and an exception with a
   * stack trace under it — of which the first line is the whole of the message and the twelve below it
   * are framework frames nobody at a window can act on.
   */
  private fun failureLine(): String? = text.lines().map { it.trim() }.firstOrNull {
    it.startsWith(DEVICE_ERROR_PREFIX) || THROWN_LINE.matches(it)
  }

  companion object {
    /** How every command `am` refuses starts its complaint. */
    private const val DEVICE_ERROR_PREFIX = "Error:"

    /** `java.lang.SecurityException: Process not debuggable: com.example`, and not the frames below it. */
    private val THROWN_LINE = Regex("[\\w.$]*(Exception|Error): .*")
  }
}

/**
 * A command `adb` or a device refused, worded for the window that shows it rather than for a stack
 * trace: everything that can go wrong here is a device that isn't there, an app that can't be dumped or
 * an Android version that can't do it, and none of those is a bug to report.
 */
class AdbFailureException(message: String) : RuntimeException(message)

/** `adb` couldn't be run, which is a missing SDK or a missing `PATH` rather than anything on a device. */
class AdbUnavailableException(
  val executable: String,
  cause: Throwable
) : RuntimeException(
  "Could not run `$executable`. Put the Android SDK's platform-tools on the PATH, or set " +
    "ANDROID_HOME to the SDK.",
  cause
)

/**
 * Runs `adb` as a subprocess.
 *
 * Looks the executable up rather than trusting the `PATH`: this is a desktop app, and an app launched
 * from a dock or a launcher inherits a `PATH` that has nothing on it.
 */
class CommandLineAdb(
  private val executable: String = findExecutable(),
  private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) : Adb {

  override fun run(arguments: List<String>): AdbOutput {
    val process = try {
      ProcessBuilder(listOf(executable) + arguments)
        .redirectErrorStream(true)
        .start()
    } catch (exception: Exception) {
      throw AdbUnavailableException(executable, exception)
    }
    val text = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    // Reading to the end of the output is what waits for the process; the timeout is there for a
    // command that keeps the stream open without saying anything, e.g. `adb wait-for-device`.
    return if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      AdbOutput(process.exitValue(), text)
    } else {
      process.destroyForcibly()
      AdbOutput(exitCode = -1, text = "`$executable ${arguments.joinToString(" ")}` timed out")
    }
  }

  companion object {
    private const val DEFAULT_TIMEOUT_SECONDS = 120L

    /**
     * Where the Android SDK says `adb` is, or the bare name for a `PATH` that has it.
     *
     * The two environment variables first, then where the SDK installs itself on macOS and on Linux,
     * because those are what an SDK installed by Android Studio actually looks like.
     */
    fun findExecutable(): String {
      val home = System.getProperty("user.home")
      val candidates = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        "$home/Library/Android/sdk",
        "$home/Android/Sdk"
      ).map { File(it, "platform-tools/adb") }
      return candidates.firstOrNull { it.canExecute() }?.absolutePath ?: "adb"
    }
  }
}

/** One device `adb` is connected to, with what it takes to tell it from another. */
class AndroidDevice(
  /** What `adb -s` takes, e.g. `emulator-5554`. */
  val serialNumber: String,
  /** `device` for one that is ready, `offline` or `unauthorized` for one that isn't. */
  val state: String,
  val fingerprint: String?,
  val model: String?,
  val sdkInt: Int?
) {

  val isReady: Boolean get() = state == READY_STATE

  /**
   * Whether a heap dump taken of this device can carry the pixels of its bitmaps, which is what
   * `am dumpheap -b` does and what Android 15 added. See [DeviceHeapDumps].
   */
  val canDumpBitmaps: Boolean
    get() = sdkInt != null && sdkInt >= DeviceHeapDumps.MIN_BITMAP_DUMP_SDK_INT

  /** How well this device matches the machine a heap dump was written on. */
  fun matchTo(origin: HeapDumpOrigin): DeviceMatch = when {
    fingerprint != null && fingerprint == origin.fingerprint -> DeviceMatch.SAME_BUILD
    model != null && model == origin.model && sdkInt == origin.sdkInt -> DeviceMatch.SAME_MODEL
    else -> DeviceMatch.OTHER
  }

  /** What the device is, on one line. */
  val description: String
    get() = listOfNotNull(
      model ?: serialNumber,
      sdkInt?.let { "API $it" },
      serialNumber.takeIf { model != null }
    ).joinToString(" · ")

  companion object {
    private const val READY_STATE = "device"
  }
}

/**
 * How well a device matches the heap dump being read.
 *
 * A fingerprint is one build of one model, so [SAME_BUILD] is as sure as a heap dump gets — two phones
 * of the same model on the same build are indistinguishable in a dump. Below that, the model and the
 * API level agreeing is worth showing and worth a warning, since pixels pulled off the wrong device
 * would be pixels of the wrong app.
 */
enum class DeviceMatch {
  SAME_BUILD,
  SAME_MODEL,
  OTHER
}

/** The devices `adb` is connected to, each asked what it is. */
internal fun Adb.connectedDevices(): List<AndroidDevice> =
  parseDeviceLines(run("devices").orFail("list the connected devices")).map { (serial, state) ->
    // A device that isn't ready answers nothing, so don't ask: `adb shell` against an unauthorized
    // device hangs until it is authorized.
    val properties = if (state == "device") propertiesOf(serial) else emptyMap()
    AndroidDevice(
      serialNumber = serial,
      state = state,
      fingerprint = properties["ro.build.fingerprint"],
      model = properties["ro.product.model"],
      sdkInt = properties["ro.build.version.sdk"]?.toIntOrNull()
    )
  }

/** Every system property of a device, from the one `getprop` call that lists them all. */
private fun Adb.propertiesOf(serialNumber: String): Map<String, String> =
  parseProperties(run("-s", serialNumber, "shell", "getprop").text)

/** The serial number and state of each line of `adb devices`, skipping its header. */
internal fun parseDeviceLines(output: String): List<Pair<String, String>> = output.lines()
  .map { it.trim() }
  .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
  .mapNotNull { line ->
    val columns = line.split(WHITESPACE)
    if (columns.size < 2) null else columns[0] to columns[1]
  }

/** `getprop`'s `[name]: [value]` lines as a map. */
internal fun parseProperties(output: String): Map<String, String> = output.lines()
  .mapNotNull { PROPERTY_LINE.matchEntire(it.trim()) }
  .associate { it.groupValues[1] to it.groupValues[2] }

private val WHITESPACE = Regex("\\s+")

private val PROPERTY_LINE = Regex("\\[(.+?)]: \\[(.*)]")
