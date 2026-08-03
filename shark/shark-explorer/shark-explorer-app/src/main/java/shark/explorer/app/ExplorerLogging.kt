package shark.explorer.app

import java.io.Closeable
import java.io.File
import shark.SharkLog
import shark.explorer.SessionLog
import shark.explorer.formatByteSize

/**
 * Sends everything Shark logs to stdout and to this run's own log file, and returns what closes it.
 *
 * The file is what makes a report of "it hung" or "it showed nothing" answerable: the log says which
 * heap dump was open, what was read off it and how long each read took, which read failed and with
 * what. See [SessionLog], and [LOG_DIRECTORY] for where the files are.
 */
internal fun installLogging(): Closeable {
  val standardOut = StandardOutLogger()
  val sessionLog = try {
    SessionLog.openIn(LOG_DIRECTORY)
  } catch (throwable: Throwable) {
    // A log file is a side channel, so not being able to open one is no reason not to start: say so on
    // stdout, where a run from a terminal will see it, and run with stdout alone.
    standardOut.d(throwable, "Could not open a log file in $LOG_DIRECTORY, logging to stdout only")
    null
  }
  SharkLog.logger = if (sessionLog == null) {
    standardOut
  } else {
    Loggers(listOf(standardOut, sessionLog))
  }
  logEnvironment(sessionLog)
  logUncaughtExceptions()
  return Closeable {
    // The line that tells a session that ended from one that was killed or ran out of memory.
    SharkLog.d { "Shark Explorer closed" }
    sessionLog?.close()
  }
}

/**
 * What every report needs and nobody thinks to include: which JVM this is, which OS, and above all how
 * much heap it was given, since a heap dump too large for the explorer runs out of exactly that.
 */
private fun logEnvironment(sessionLog: SessionLog?) {
  val runtime = Runtime.getRuntime()
  SharkLog.d {
    // Which version, because a report is about a build rather than about the app in general, and because
    // this is the only line that says so: the update check names it only when it found a manifest to
    // compare against, so a run that couldn't reach GitHub would otherwise name no version at all.
    "Shark Explorer ${SharkExplorerVersion.current} starting" +
      if (sessionLog == null) "" else ", logging to ${sessionLog.file}"
  }
  SharkLog.d {
    "Java ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}) on " +
      "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
      "(${System.getProperty("os.arch")})"
  }
  SharkLog.d {
    "Heap limit ${formatByteSize(runtime.maxMemory())}, " +
      "${runtime.availableProcessors()} processors"
  }
}

/**
 * Logs what killed a thread, which is otherwise printed to stderr alone and therefore missing from the
 * file a report attaches. How Compose reports a failure in a composition or in an effect, so this is
 * what stands between a window that vanished and knowing why.
 */
private fun logUncaughtExceptions() {
  val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    SharkLog.d(throwable) { "Uncaught ${throwable.javaClass.name} on thread ${thread.name}" }
    // Nothing to delegate to when there was no handler, and nothing missing either: printing the trace
    // is what the JVM would have done, and [StandardOutLogger] has just done it.
    previousHandler?.uncaughtException(thread, throwable)
  }
}

/** Shark's diagnostics on stdout, which is where a run from a terminal expects them. */
private class StandardOutLogger : SharkLog.Logger {

  override fun d(message: String) = println(message)

  override fun d(
    throwable: Throwable,
    message: String
  ) {
    println(message)
    throwable.printStackTrace()
  }
}

/** Every log to each of [loggers], in the order they were given. */
private class Loggers(private val loggers: List<SharkLog.Logger>) : SharkLog.Logger {

  override fun d(message: String) = loggers.forEach { it.d(message) }

  override fun d(
    throwable: Throwable,
    message: String
  ) = loggers.forEach { it.d(throwable, message) }
}

/**
 * Where the log files go: under the user's home directory rather than beside the heap dump or in the
 * working directory, so that every run writes to the same place however it was started.
 */
private val LOG_DIRECTORY = File(System.getProperty("user.home"), ".shark-explorer/logs")
