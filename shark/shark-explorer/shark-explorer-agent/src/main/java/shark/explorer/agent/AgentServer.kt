package shark.explorer.agent

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Properties
import kotlinx.coroutines.runBlocking
import shark.SharkLog

/**
 * Where an agent reaches this run of the app, and how it finds out where that is.
 *
 * A loopback socket and a file naming it, which is the same shape as `DeepLinkPeers` and deliberately not
 * the same socket: a link is one line delivered to whichever run owns a window, and this is a session held
 * open for as long as an agent is working. Two features with two lifetimes on one port would mean a link
 * arriving while an investigation is in flight, and an investigation ending when a link handler closed.
 *
 * **Every run publishes itself**, like the links do, because several explorers open at once is how this app
 * is used. [AgentStdioBridge] is what picks one, and a run that was killed leaves a file that nothing
 * answers on, which the next reader deletes.
 *
 * Loopback only, and a caller has to quote the token out of the file — which proves it can read the user's
 * home directory, and therefore that it is the user. Worth spelling out what that is and isn't: this is
 * enough to keep a web page or another machine out, and it is not a boundary between programs run by the
 * same person. Anything that can read `~/.shark-explorer` can read any heap dump on the disk anyway.
 */
object AgentServer {

  /**
   * Publishes this run and answers agents until closed.
   *
   * Failing to listen is not a reason to refuse to start: the window works, and what stops working is
   * agents being able to reach it — which the log then says, rather than a client that hangs with no
   * explanation.
   */
  fun listen(
    heapDumps: AgentHeapDumps,
    /** Which build is answering, for the handshake. */
    serverVersion: String,
    /** Where the file naming this run goes, which is `~/.shark-explorer/agents` for the real app. */
    directory: File
  ): Closeable {
    val serverSocket = try {
      ServerSocket(ANY_FREE_PORT, BACKLOG, InetAddress.getLoopbackAddress())
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not listen for agents: no agent will be able to reach this run" }
      return Closeable {}
    }
    val token = newToken()
    val file = File(directory, "${ProcessHandle.current().pid()}$RUN_SUFFIX")
    return try {
      write(file, serverSocket.localPort, token)
      SharkLog.d { "Answering agents on port ${serverSocket.localPort}, published as $file" }
      val sessions = sessionsDirectory(directory)
      val thread = Thread(
        { accept(serverSocket, token, heapDumps, serverVersion, sessions) },
        THREAD_NAME
      ).apply {
        isDaemon = true
        start()
      }
      Runtime.getRuntime().addShutdownHook(Thread { file.delete() })
      Closeable {
        file.delete()
        serverSocket.close()
        thread.interrupt()
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not publish this run at $file: no agent will find it" }
      serverSocket.close()
      Closeable {}
    }
  }

  /**
   * Where the sessions of the agents that connect to a run published in [directory] are written down.
   *
   * One function rather than a path spelled in two modules: the app reads these to draw them, and a screen
   * looking in the wrong directory is a screen that says no agent has ever been here. See [AgentSessionFile].
   */
  fun sessionsDirectory(directory: File): File = File(directory, SESSIONS_DIRECTORY)

  /** Every run of this app an agent could connect to, newest first, stale files cleared out on the way. */
  internal fun publishedRuns(directory: File): List<PublishedRun> {
    val files = directory.listFiles { file -> file.name.endsWith(RUN_SUFFIX) }.orEmpty()
    return files.sortedByDescending { it.lastModified() }.mapNotNull { file -> read(file) }
  }

  private fun read(file: File): PublishedRun? {
    val properties = Properties()
    return try {
      file.inputStream().use { properties.load(it) }
      val port = properties.getProperty(PORT_PROPERTY)?.toIntOrNull()
      val token = properties.getProperty(TOKEN_PROPERTY)
      if (port == null || token == null) {
        SharkLog.d { "$file says no port and token, so it names no run: deleting it" }
        file.delete()
        null
      } else {
        PublishedRun(file, file.name.removeSuffix(RUN_SUFFIX), port, token)
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not read $file, so no agent can be pointed at that run" }
      null
    }
  }

  private fun write(
    file: File,
    port: Int,
    token: String
  ) {
    file.parentFile.mkdirs()
    val properties = Properties().apply {
      setProperty(PORT_PROPERTY, port.toString())
      setProperty(TOKEN_PROPERTY, token)
    }
    file.outputStream().use { properties.store(it, "Where this Shark Explorer run answers agents") }
    // Best effort, and only worth anything on a machine with more than one user on it: the token is what
    // this is protecting, and a token nobody can read is a run no agent can reach.
    file.setReadable(false, false)
    file.setReadable(true, true)
  }

  private fun accept(
    serverSocket: ServerSocket,
    token: String,
    heapDumps: AgentHeapDumps,
    serverVersion: String,
    sessions: File
  ) {
    while (!serverSocket.isClosed) {
      try {
        val socket = serverSocket.accept()
        // A thread per agent, because a session is held open for as long as the agent is working and two
        // agents on one heap dump is a thing to allow rather than to serialise: what they would queue on
        // is the heap dump's own thread, which is where reads belong anyway.
        Thread({ serve(socket, token, heapDumps, serverVersion, sessions) }, THREAD_NAME).apply {
          isDaemon = true
          start()
        }
      } catch (closed: SocketException) {
        // Which is what closing the socket out from under accept() looks like, and it is how this ends.
        SharkLog.d { "Stopped answering agents: ${closed.message}" }
        return
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "An agent could not be accepted, carrying on listening" }
      }
    }
  }

  /**
   * One connection: the token and optionally a session to join, then a JSON-RPC message per line until the
   * agent goes away.
   *
   * **No read timeout**, unlike the link socket. An agent thinking, or waiting for the person at the
   * machine, is a connection with nothing on it for minutes at a time, and a session dropped for being
   * quiet is one that loses whatever it had concluded.
   */
  private fun serve(
    socket: Socket,
    token: String,
    heapDumps: AgentHeapDumps,
    serverVersion: String,
    sessions: File
  ) {
    socket.use {
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
      val handshake = reader.readLine().orEmpty().split(HANDSHAKE_SEPARATOR)
      if (handshake.firstOrNull() != token) {
        // Loopback only, so this is a stale file being read far more often than it is anything to worry
        // about.
        SharkLog.d { "An agent connected quoting the wrong token, so it was not listened to" }
        writer.println(DECLINED)
        return
      }
      writer.println(ACCEPTED)
      // A file per accepted connection unless it asked to join one, so that two agents at one heap dump are
      // two sessions to read rather than one file with both of their reasoning in it. Named before the
      // handshake, since a client that connects and says nothing is itself worth a line on that screen.
      val sessionFile = sessionFile(sessions, serverVersion, handshake.getOrNull(1))
      SharkLog.d { "An agent's session is being written to ${sessionFile.file}" }
      val session = McpSession(
        // Read off disk per call rather than captured, so that an agent asking what has been done to a
        // heap dump sees what another one working on it right now has done so far.
        AgentTools(heapDumps) { AgentSessionFile.sessionsIn(sessions) },
        serverVersion,
        sessionFile
      )
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) {
          continue
        }
        // Blocking on this thread rather than a scope of our own: a message is answered before the next is
        // read, which is what an agent sends anyway, and the reads inside suspend onto the heap dump's
        // thread where they belong.
        val answer = runBlocking { session.answer(line) }
        if (answer != null) {
          writer.println(answer)
        }
      }
      SharkLog.d { "An agent disconnected" }
    }
  }

  /**
   * Where this connection's calls are written down: a session of its own, or the one it asked to join.
   *
   * A connection is a session for a client that holds one open, which is what MCP over the pipe is. A
   * command line is a process per call, so it names the session its calls belong to instead — see
   * [AgentCommandLine]. The name is checked here as well as there, because it becomes part of a file name and
   * it arrived from another process; a name this cannot use is a session of its own and a line saying so,
   * rather than a connection refused, since the calls themselves are none the worse for it.
   */
  private fun sessionFile(
    sessions: File,
    serverVersion: String,
    name: String?
  ): AgentSessionFile {
    if (name == null) {
      return AgentSessionFile.starting(sessions, serverVersion)
    }
    if (!AgentSessionFile.isSessionName(name)) {
      SharkLog.d { "\"$name\" is no session name, so this connection was given a session of its own" }
      return AgentSessionFile.starting(sessions, serverVersion)
    }
    return AgentSessionFile.continuing(sessions, serverVersion, name)
  }

  private fun newToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  /** A run of the app that has published where it answers agents. See [publishedRuns]. */
  internal class PublishedRun(
    val file: File,
    /** The process id, which is what the file is named after and what identifies a run to a person. */
    val pid: String,
    val port: Int,
    val token: String
  )

  private const val ANY_FREE_PORT = 0
  private const val BACKLOG = 8
  private const val TOKEN_BYTES = 16

  /** Beside the runs answering links, the notes and the logs, which is everything else this app keeps. */
  internal const val RUN_SUFFIX = ".agent"

  /** Under the directory the runs publish themselves in, since a session is a run being talked to. */
  private const val SESSIONS_DIRECTORY = "sessions"
  internal const val ACCEPTED = "OK"
  internal const val DECLINED = "NO"

  /** Between the token and the session a connection is joining, which is why a name has no spaces in it. */
  private const val HANDSHAKE_SEPARATOR = ' '
  private const val PORT_PROPERTY = "port"
  private const val TOKEN_PROPERTY = "token"
  private const val THREAD_NAME = "shark-explorer-agents"
}
