package shark.explorer.app

import java.io.File
import shark.SharkLog

/**
 * How this run of the app was launched, which is two quite different things and neither is visible from the
 * classpath.
 *
 * A packaged install is a launcher `jpackage` generated — one executable that knows its own classpath and main
 * class. Everything else is a JVM someone put a classpath on: `./gradlew run`, `runNamed`, an IDE run
 * configuration. Anything that has to *name* this app to the OS or start another copy of it has to know which,
 * and the answer is different every time somebody asks it the easy way.
 */

/**
 * The executable the OS should be told to open a `shark://` link with, and null when this run is not one.
 *
 * Null for a JVM on purpose: registering `java` would tell the OS to open links with a JVM and no classpath,
 * which is worse than not registering at all. See [DeepLinkScheme].
 */
internal fun launcherPathOrNull(): String? {
  val command = ProcessHandle.current().info().command().orElse(null) ?: return null
  return command.takeIf { File(it).name !in JVM_EXECUTABLES }
}

/**
 * What to run to start another Shark Explorer, and null when this run can't work out how it was started.
 *
 * Both cases, unlike [launcherPathOrNull]: a JVM *can* start another copy of itself, since this process
 * already holds the classpath that would take. Which is what makes an agent able to open a window from a
 * `./gradlew run` bridge as well as from an installed app — and the reason it is worth spelling the main class
 * here is that the alternative is the feature only working in a packaged build, where it is slowest to try.
 */
internal fun relaunchCommand(): List<String>? {
  val command = ProcessHandle.current().info().command().orElse(null)
  if (command == null) {
    SharkLog.d { "This process does not say what launched it, so another run of it cannot be started" }
    return null
  }
  if (File(command).name !in JVM_EXECUTABLES) {
    return listOf(command)
  }
  val classPath = System.getProperty("java.class.path")
  if (classPath.isNullOrEmpty()) {
    SharkLog.d { "$command was launched with no classpath, so another run of it cannot be started" }
    return null
  }
  return listOf(command, "-cp", classPath, MAIN_CLASS)
}

/**
 * A run launched as one of these is a classpath rather than an app, whatever bundle it came out of.
 *
 * Measured rather than assumed: a `runNamed` bundle declares its own identity in an `Info.plist` and macOS
 * still records the process as `net.java.openjdk.java`, because what it launched is this.
 */
private val JVM_EXECUTABLES = setOf("java", "java.exe", "javaw.exe")

/**
 * What Kotlin calls the file `main` is in, which is the one thing here that a rename would silently break.
 *
 * `ExplorerProcessTest` loads it, so a rename of `Main.kt` fails a test rather than a feature nobody tries
 * until an agent needs a window.
 */
internal const val MAIN_CLASS = "shark.explorer.app.MainKt"
