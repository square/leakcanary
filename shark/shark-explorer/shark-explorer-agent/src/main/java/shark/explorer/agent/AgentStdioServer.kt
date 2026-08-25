package shark.explorer.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlinx.coroutines.runBlocking

/**
 * The same tools over this process's own stdin and stdout, for a run with no window.
 *
 * [AgentStdioBridge] is a pipe to a window somebody is watching; this is the other case, a machine with no
 * screen to watch it on — someone's build server, or a heap dump on the far end of an ssh session. Both are
 * `--mcp-stdio`, and which one a run is depends only on whether it was told there is no UI.
 *
 * There is no socket and no token here, because there is nothing to find: the client launched this process and
 * talks to it down the pipe it already holds. Which also makes it the one shape of this surface with no
 * authorization question at all.
 *
 * **Nothing may be written to stdout but protocol**, the same rule the bridge is under, and here it reaches
 * further: the tools run in this process, so the heap dump's own diagnostics are in it too. `main` points
 * those at stderr in this mode. See `shark.explorer.app.installLogging`.
 */
object AgentStdioServer {

  /**
   * Answers a message per line until the client closes its end, and returns the exit code to end with.
   *
   * One message answered before the next is read, exactly as [AgentServer] serves a socket: a client sends
   * its next call after it has been answered anyway, and the reads inside suspend onto whichever thread owns
   * the heap dump.
   */
  fun run(
    heapDumps: AgentHeapDumps,
    serverVersion: String,
    /** Where a session is written down, the same directory the windowed runs write theirs to. */
    sessions: File
  ): Int {
    // Named before the handshake, like a socket session is, so that a client which connects and says nothing
    // is still a row on the *Agent logs* screen of whoever reads these later.
    val sessionFile = AgentSessionFile.starting(sessions, serverVersion)
    val session = McpSession(AgentTools(heapDumps), serverVersion, sessionFile)
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val writer = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) {
        continue
      }
      val answer = runBlocking { session.answer(line) }
      if (answer != null) {
        writer.println(answer)
      }
    }
    return 0
  }
}
