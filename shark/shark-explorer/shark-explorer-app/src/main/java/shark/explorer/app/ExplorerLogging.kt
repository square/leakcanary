package shark.explorer.app

import java.io.Closeable
import java.io.File
import java.io.PrintStream
import shark.SharkLog
import shark.explorer.SessionLog
import shark.explorer.formatByteSize

/**
 * Sends everything Shark logs to a stream and to this run's own log file, and returns what closes it.
 *
 * The file is what makes a report of "it hung" or "it showed nothing" answerable: the log says which
 * heap dump was open, what was read off it and how long each read took, which read failed and with
 * what. See [SessionLog], and [LOG_DIRECTORY] for where the files are.
 */
internal fun installLogging(
  /**
   * Where the diagnostics go besides the file, which is stdout for a run from a terminal and **stderr for
   * one talking MCP over stdio**: there, stdout is the protocol, and a log line in the middle of a JSON-RPC
   * stream is a session the client reports as broken. See `shark.explorer.agent.AgentStdioServer`.
   */
  diagnostics: PrintStream = System.out
): Closeable {
  val streamLogger = StreamLogger(diagnostics)
  val sessionLog = try {
    SessionLog.openIn(LOG_DIRECTORY)
  } catch (throwable: Throwable) {
    // A log file is a side channel, so not being able to open one is no reason not to start: say so on
    // the stream, where a run from a terminal will see it, and run with that alone.
    streamLogger.d(throwable, "Could not open a log file in $LOG_DIRECTORY, logging to the terminal only")
    null
  }
  SharkLog.logger = if (sessionLog == null) {
    streamLogger
  } else {
    Loggers(listOf(streamLogger, sessionLog))
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

/** Shark's diagnostics on one stream, which is stdout for a run from a terminal. */
private class StreamLogger(private val stream: PrintStream) : SharkLog.Logger {

  override fun d(message: String) = stream.println(message)

  override fun d(
    throwable: Throwable,
    message: String
  ) {
    stream.println(message)
    throwable.printStackTrace(stream)
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
 * Where this app keeps what it writes: under the user's home directory rather than beside the heap dump or
 * in the working directory, so that every run reads and writes the same place however it was started.
 */
internal val SHARK_EXPLORER_DIRECTORY = File(System.getProperty("user.home"), ".shark-explorer")

/** One file per run. See [SessionLog]. */
private val LOG_DIRECTORY = File(SHARK_EXPLORER_DIRECTORY, "logs")
