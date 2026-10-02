package shark.dive.app

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
 * What to run to start another Shark Dive, and null when this run can't work out how it was started.
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
 * The macOS `.app` this run was launched from, and null for every other way of starting it.
 *
 * What it is for is starting a run that **survives the terminal the command was typed in closing**, which on
 * macOS means `open -n -a <bundle>`: a JVM dies on SIGHUP, and a child of the caller is in the caller's
 * process group and its session, so a terminal closing and anything killing that group takes it with it.
 * `open` hands the launch to LaunchServices, and the run comes out a child of `launchd` in a session of its
 * own with no terminal. Measured both ways — see `shark/shark-dive/notes/agent-surface.md`.
 *
 * A bundle rather than the launcher inside it, because that is what `open` takes. The three path elements are
 * what jpackage's app image is: `<name>.app/Contents/MacOS/<launcher>`, so anything else — a JVM, a launcher
 * somebody copied out of a bundle — is null and starts the ordinary way.
 */
internal fun appBundlePathOrNull(): String? {
  val launcher = File(launcherPathOrNull() ?: return null)
  val bundle = launcher.parentFile?.parentFile?.parentFile ?: return null
  return bundle.absolutePath.takeIf {
    launcher.parentFile.name == BUNDLE_LAUNCHER_DIRECTORY && bundle.name.endsWith(BUNDLE_SUFFIX)
  }
}

/**
 * A run launched as one of these is a classpath rather than an app, whatever bundle it came out of.
 *
 * Measured rather than assumed: a `runNamed` bundle declares its own identity in an `Info.plist` and macOS
 * still records the process as `net.java.openjdk.java`, because what it launched is this.
 */
private val JVM_EXECUTABLES = setOf("java", "java.exe", "javaw.exe")

private const val BUNDLE_SUFFIX = ".app"
private const val BUNDLE_LAUNCHER_DIRECTORY = "MacOS"

/**
 * What Kotlin calls the file `main` is in, which is the one thing here that a rename would silently break.
 *
 * `DiveProcessTest` loads it, so a rename of `Main.kt` fails a test rather than a feature nobody tries
 * until an agent needs a window.
 */
internal const val MAIN_CLASS = "shark.dive.app.MainKt"
