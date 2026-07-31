package shark.explorer

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import shark.SharkLog

/**
 * A [SharkLog.Logger] writing to a file of one run's own, with the files of the runs before it kept
 * until there are more than [KEEP_SESSION_COUNT] of them.
 *
 * What this is for: someone reporting that the explorer did something odd, and reading back what it
 * actually did rather than what anyone remembers of it — which heap dump, which reads, which one
 * failed and with what. So every line says when it was written and from which thread, and every write
 * is flushed, because the session worth reading is usually the one that ended by crashing and a
 * buffered tail is the part that explains why.
 *
 * A file per run rather than one file appended to for ever: the question is always what one session
 * did, and a week of sessions in one file is a file nobody reads.
 */
class SessionLog private constructor(
  /** The file this run logs to. Worth showing the user, since it's what a bug report attaches. */
  val file: File,
  private val writer: Writer
) : SharkLog.Logger, Closeable {

  /** [SimpleDateFormat] isn't thread safe, so this is only ever touched under [write]'s lock. */
  private val lineTimeFormat = SimpleDateFormat(LINE_TIME_PATTERN, Locale.US)

  /** False once this stops being written to, by [close] or by a write that failed. */
  private var isOpen = true

  override fun d(message: String) = write(message, throwable = null)

  override fun d(
    throwable: Throwable,
    message: String
  ) = write(message, throwable)

  private fun write(
    message: String,
    throwable: Throwable?
  ) {
    // Shark logs from whichever thread does the work — the window's, the heap dump's, an uncaught
    // exception handler's — and one thread's line spliced into another's reads as a corrupted file.
    synchronized(this) {
      if (!isOpen) {
        return
      }
      try {
        writer.write("${lineTimeFormat.format(Date())} [${Thread.currentThread().name}] $message\n")
        throwable?.let { writer.write(it.stackTraceText()) }
        writer.flush()
      } catch (failure: IOException) {
        // Not logged through SharkLog, which is what this is the sink of, and only once: a line per
        // log line saying the log isn't being written is worse than the log that is missing. Stderr
        // because a run from a terminal is where anyone would notice.
        isOpen = false
        System.err.println("Stopped writing to the log file $file: $failure")
      }
    }
  }

  /**
   * Stops writing and releases the file.
   *
   * Nothing is lost by never calling this — every line is already flushed — and whatever logs after it
   * is dropped rather than throwing, because a logger that throws while the app is shutting down
   * replaces the reason it is shutting down.
   */
  override fun close() {
    synchronized(this) {
      if (isOpen) {
        isOpen = false
        writer.close()
      }
    }
  }

  /**
   * Deletes the oldest log files, keeping this run's and the [keepSessionCount] - 1 runs before it.
   *
   * Says what it deleted, and says when it couldn't: a log directory that grows past what it's meant
   * to is only ever explained here.
   */
  private fun deleteOlderSessions(keepSessionCount: Int) {
    val directory = file.parentFile
    val logFiles = directory.listFiles { candidate: File -> candidate.isSessionLog() }
    if (logFiles == null) {
      d("Could not list $directory to delete the log files of older runs")
      return
    }
    // Named after the time the run started, so oldest first is a sort by name.
    logFiles.sortedBy { it.name }.dropLast(keepSessionCount).forEach { olderLog ->
      if (olderLog.delete()) {
        d("Deleted the log of an older run, $olderLog")
      } else {
        d("Could not delete the log of an older run, $olderLog")
      }
    }
  }

  companion object {
    /**
     * Opens the log file of this run in [directory], creating the directory if it isn't there, and
     * deletes all but the newest [keepSessionCount] log files, this one included.
     */
    fun openIn(
      directory: File,
      keepSessionCount: Int = KEEP_SESSION_COUNT,
      startedAt: Date = Date()
    ): SessionLog {
      require(keepSessionCount >= 1) {
        "Expected to keep at least this run's log file, not $keepSessionCount of them"
      }
      directory.mkdirs()
      check(directory.isDirectory) {
        "Expected $directory to be a directory to write this run's log file to"
      }
      val fileName = FILE_NAME_PREFIX +
        SimpleDateFormat(FILE_NAME_TIME_PATTERN, Locale.US).format(startedAt) +
        FILE_NAME_SUFFIX
      val file = File(directory, fileName)
      // Appended to rather than truncated: two runs starting in the same millisecond isn't something
      // that happens, and if it ever did, two sessions in one file beats one of them overwritten.
      val writer = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).buffered()
      return SessionLog(file, writer).apply { deleteOlderSessions(keepSessionCount) }
    }

    /**
     * How many runs' log files are kept. Enough that a session reported a few days late is still
     * there, few enough that the directory stays something to read rather than to search.
     */
    const val KEEP_SESSION_COUNT = 20

    private const val FILE_NAME_PREFIX = "shark-explorer-"

    private const val FILE_NAME_SUFFIX = ".log"

    /** Sorts oldest first as text, which is what makes finding the oldest files a sort by name. */
    private const val FILE_NAME_TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss_SSS"

    /** No date: the file name has it, and every line of a session is better off short. */
    private const val LINE_TIME_PATTERN = "HH:mm:ss.SSS"

    private fun File.isSessionLog(): Boolean =
      name.startsWith(FILE_NAME_PREFIX) && name.endsWith(FILE_NAME_SUFFIX)

    private fun Throwable.stackTraceText(): String {
      val text = StringWriter()
      PrintWriter(text).use { printStackTrace(it) }
      return text.toString()
    }
  }
}
