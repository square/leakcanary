package shark.explorer.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standard input and output, wired to the run of the app an agent wants to talk to.
 *
 * The one thing every MCP client can be configured with is a command to run, so this is what makes the
 * window reachable without asking anybody to configure a port that changes every run: the client launches
 * this, this finds the window. A dozen lines of pipe against a client integration nobody has to write.
 *
 * It is a mode of the app rather than a program of its own so that there is one thing to install — the
 * `.app` on the machine is both the window and the bridge to it. See `shark.explorer.app.main`.
 *
 * **Nothing is ever written to stdout but protocol**, which is why this runs before the app's logging is
 * installed: that logger writes to stdout, and one line of it in the middle of a JSON-RPC stream is a
 * client that reports the server as broken. Everything this has to say goes to stderr, which is where an
 * MCP client collects a server's log.
 */
object AgentStdioBridge {

  /**
   * Pumps until either end goes away, and returns the exit code the process should end with.
   *
   * A run that has gone is the interesting failure and it is reported rather than waited on: an agent
   * whose client hangs at startup has no way to tell that from a machine that is slow, so this says what
   * is wrong on stderr and ends.
   */
  fun run(
    /** Where the runs of the app publish themselves. See [AgentServer]. */
    directory: File,
    /** Which run, by process id, or null for the one that started most recently. */
    pid: String? = null,
    /** How long to wait for a run to appear, for a client that launched this before the app was open. */
    waitMillis: Long = DEFAULT_WAIT_MILLIS,
    /**
     * How to open a window to investigate in when no run of the app is open, and null to wait for one.
     *
     * Because the alternative is an agent whose only answer is "ask somebody to launch Shark Explorer", and
     * a window opened here is a window the person at the machine can then watch — which is the whole reason
     * this surface is a window rather than a library. Not called when a run was asked for by [pid]: that
     * names a window, and opening a different one would be answering about the wrong heap dump.
     */
    openAWindow: (() -> Unit)? = null
  ): Int {
    val run = waitForRun(directory, pid, waitMillis, openAWindow) ?: return NOTHING_TO_TALK_TO
    val socket = try {
      Socket().apply {
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), run.port), CONNECT_TIMEOUT_MILLIS)
      }
    } catch (throwable: Throwable) {
      // Which is a run that was killed: the file is still there and nothing is on the port.
      say("Shark Explorer run ${run.pid} does not answer on port ${run.port}: $throwable")
      run.file.delete()
      return NOTHING_TO_TALK_TO
    }
    return socket.use { pump(it, run) }
  }

  private fun pump(
    socket: Socket,
    run: AgentServer.PublishedRun
  ): Int {
    val toApp = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
    val fromApp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    toApp.println(run.token)
    if (fromApp.readLine() != AgentServer.ACCEPTED) {
      say("Shark Explorer run ${run.pid} refused the token in ${run.file}, so it is not the run that wrote it")
      return NOTHING_TO_TALK_TO
    }
    say("Talking to Shark Explorer run ${run.pid}")
    // Set before the socket is closed from this side, so that the read it interrupts knows it was us. Which is
    // how *every* session that ends normally ends, so without this each one finishes with a stack trace on
    // stderr — where an MCP client collects a server's log, and reads it as the server having crashed.
    val ending = AtomicBoolean(false)
    // The app's answers on their own thread, because both directions are blocking reads and a client sends
    // its next message without waiting to be answered.
    val answers = Thread({
      val out = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
      try {
        while (true) {
          val line = fromApp.readLine() ?: break
          out.println(line)
        }
        // The window closed, which ends the session: nothing is going to answer the client's next message.
        say("Shark Explorer run ${run.pid} closed the connection")
      } catch (throwable: Throwable) {
        if (!ending.get()) {
          say("Shark Explorer run ${run.pid} stopped answering: $throwable")
        }
      }
      out.flush()
    }, "shark-explorer-agent-answers").apply {
      isDaemon = true
      start()
    }
    val stdin = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    while (true) {
      val line = stdin.readLine() ?: break
      toApp.println(line)
      if (toApp.checkError()) {
        say("Shark Explorer run ${run.pid} went away")
        return NOTHING_TO_TALK_TO
      }
    }
    // The client closed its end, which is how a session normally ends.
    ending.set(true)
    socket.close()
    answers.join(SHUTDOWN_MILLIS)
    return 0
  }

  private fun waitForRun(
    directory: File,
    pid: String?,
    waitMillis: Long,
    openAWindow: (() -> Unit)?
  ): AgentServer.PublishedRun? {
    var waited = 0L
    var deadline = waitMillis
    var opened = false
    // Naming a run names a window and therefore a heap dump, so opening a different one would be answering
    // about the wrong dump: for that command line there is nothing to open, only something to wait for.
    val opensAWindow = openAWindow != null && pid == null
    while (true) {
      val runs = AgentServer.publishedRuns(directory)
      val run = if (pid == null) runs.firstOrNull() else runs.firstOrNull { it.pid == pid }
      if (run == null && !opened && opensAWindow) {
        say("No Shark Explorer is running, so one is being opened to investigate in.")
        requireNotNull(openAWindow).invoke()
        opened = true
        // From here rather than from the start, because what is being waited for changed: a JVM starting,
        // Compose coming up and a window appearing, rather than a file that may already be there.
        deadline = waited + OPENING_WAIT_MILLIS
      }
      if (run != null) {
        if (pid == null && runs.size > 1) {
          // Which run an agent ends up in is worth saying rather than leaving to be worked out from what
          // heap dump it finds open: several explorers at once is the normal way this app is used.
          say(
            "${runs.size} Shark Explorer runs are open; talking to ${run.pid}, the one that started most " +
              "recently. Pass $PID_OPTION<pid> to pick another: " + runs.joinToString(", ") { it.pid }
          )
        }
        return run
      }
      if (waited >= deadline) {
        say(
          if (pid == null && opened) {
            "A Shark Explorer was started and has not published itself in " +
              "${OPENING_WAIT_MILLIS / 1000} seconds, so something went wrong opening it. Its log is in " +
              "the newest file under ~/.shark-explorer/logs."
          } else if (pid == null) {
            "No Shark Explorer is running, so there is no heap dump to investigate. Open one — every run " +
              "of the app publishes itself in $directory — and start this again."
          } else {
            "No Shark Explorer run is $pid. Open runs: " +
              AgentServer.publishedRuns(directory).joinToString(", ") { it.pid }.ifEmpty { "none" }
          }
        )
        return null
      }
      Thread.sleep(POLL_MILLIS)
      waited += POLL_MILLIS
    }
  }

  /**
   * On stderr, always, which is where an MCP client collects what a server has to say.
   *
   * Not through `SharkLog`: this process deliberately never installs the app's logging, since that writes
   * to stdout and stdout is the protocol.
   */
  private fun say(message: String) {
    System.err.println("[shark-explorer] $message")
  }

  /** What the command line says to pick a run by process id. See `shark.explorer.app.ExplorerArguments`. */
  const val PID_OPTION = "--agent-run="

  private const val DEFAULT_WAIT_MILLIS = 10_000L

  /**
   * How long a window opened from here is given to publish itself.
   *
   * Longer than [DEFAULT_WAIT_MILLIS] by a lot, because it covers a cold JVM, Compose starting and jlink's
   * runtime being paged in — and because the alternative to waiting is telling an agent there is no window
   * while one is in the middle of appearing.
   */
  private const val OPENING_WAIT_MILLIS = 60_000L
  private const val POLL_MILLIS = 250L
  private const val CONNECT_TIMEOUT_MILLIS = 1_000
  private const val SHUTDOWN_MILLIS = 500L

  /**
   * What this process ends with when it never found a window, which is a failure a client should show: an
   * MCP server that exits zero having done nothing reads as one with no tools.
   */
  private const val NOTHING_TO_TALK_TO = 1
}
