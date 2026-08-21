package shark.explorer.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
    waitMillis: Long = DEFAULT_WAIT_MILLIS
  ): Int {
    val run = waitForRun(directory, pid, waitMillis) ?: return NOTHING_TO_TALK_TO
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
    // The app's answers on their own thread, because both directions are blocking reads and a client sends
    // its next message without waiting to be answered.
    val answers = Thread({
      val out = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
      while (true) {
        val line = fromApp.readLine() ?: break
        out.println(line)
      }
      // The window closed, which ends the session: nothing is going to answer the client's next message.
      say("Shark Explorer run ${run.pid} closed the connection")
      System.out.flush()
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
    socket.close()
    answers.join(SHUTDOWN_MILLIS)
    return 0
  }

  private fun waitForRun(
    directory: File,
    pid: String?,
    waitMillis: Long
  ): AgentServer.PublishedRun? {
    var waited = 0L
    while (true) {
      val runs = AgentServer.publishedRuns(directory)
      val run = if (pid == null) runs.firstOrNull() else runs.firstOrNull { it.pid == pid }
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
      if (waited >= waitMillis) {
        say(
          if (pid == null) {
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
  private const val POLL_MILLIS = 250L
  private const val CONNECT_TIMEOUT_MILLIS = 1_000
  private const val SHUTDOWN_MILLIS = 500L

  /**
   * What this process ends with when it never found a window, which is a failure a client should show: an
   * MCP server that exits zero having done nothing reads as one with no tools.
   */
  private const val NOTHING_TO_TALK_TO = 1
}
