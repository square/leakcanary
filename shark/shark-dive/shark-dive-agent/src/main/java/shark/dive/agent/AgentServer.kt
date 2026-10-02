package shark.dive.agent

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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
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
 * **Every run publishes itself**, like the links do, because several Shark Dive windows open at once is how this app
 * is used. [AgentCommandLine] is what picks one, and a run that was killed leaves a file behind that the next
 * reader deletes for it — see [isRunning], which is why the list is runs rather than files.
 *
 * Loopback only, and a caller has to quote the token out of the file — which proves it can read the user's
 * home directory, and therefore that it is the user. Worth spelling out what that is and isn't: this is
 * enough to keep a web page or another machine out, and it is not a boundary between programs run by the
 * same person. Anything that can read `~/.shark-dive` can read any heap dump on the disk anyway.
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
    /**
     * Which commit this run was built from, which is what [AgentCommandLine] filters runs by. See
     * [PublishedRun.buildSha].
     */
    buildSha: String,
    /**
     * Whether this run draws windows, which is the one thing about a run a command line has to know before it
     * connects. See [PublishedRun.hasWindow].
     */
    hasWindow: Boolean,
    /** Where the file naming this run goes, which is `~/.shark-dive/agents` for the real app. */
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
      write(file, serverSocket.localPort, token, buildSha, hasWindow)
      SharkLog.d {
        "Answering agents on port ${serverSocket.localPort}, published as $file: built from $buildSha, " +
          if (hasWindow) "with windows" else "with no window"
      }
      val sessions = sessionsDirectory(directory)
      val callsInFlight = AtomicInteger()
      val thread = Thread(
        { accept(serverSocket, token, heapDumps, serverVersion, sessions, callsInFlight) },
        THREAD_NAME
      ).apply {
        isDaemon = true
        start()
      }
      Runtime.getRuntime().addShutdownHook(Thread { file.delete() })
      Closeable {
        file.delete()
        serverSocket.close()
        letAnswersOut(callsInFlight)
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

  /**
   * The run a file names, and null for a file that names none this can use.
   *
   * **A file naming a process that is running is left where it is, whatever is in it**, and the delete is only
   * for one that names a process that has gone. That is the half worth writing down, because it cost a run: a
   * `--no-ui` run logged itself as published, the command line that had just started it never saw the file, the
   * file was gone afterwards, and the run stayed alive and unreachable for the rest of its life — a JVM holding
   * a heap dump nothing could ever ask about, while the command that started it waited its full sixty seconds
   * and then said something had gone wrong opening it. Nothing else reads this directory, so what deleted it was
   * this, reading the file in the moment between its creation and its contents. [write] closes that moment, and
   * this is the other half of it: the two reasons a live run's file might not parse — being written right now,
   * and being written by a build older than one of these properties — are both runs that something can still
   * talk to, and neither is this reader's to delete.
   */
  private fun read(file: File): PublishedRun? {
    val pid = file.name.removeSuffix(RUN_SUFFIX)
    if (!isRunning(pid)) {
      SharkLog.d { "Run $pid has ended, so nothing answers at $file: deleting it" }
      file.delete()
      return null
    }
    val properties = Properties()
    return try {
      file.inputStream().use { properties.load(it) }
      // Every one of the four properties is what a call needs before it sends anything, so a file missing any
      // of them names nothing a call can use — which a run of a build older than those properties is too, and
      // it is still that build's run to talk to.
      runOf(file, pid, properties) ?: run {
        SharkLog.d { "$file does not say where and what run $pid is, so it names no run this can use" }
        null
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not read $file, so no agent can be pointed at that run" }
      null
    }
  }

  /** The run a file names, and null for a file that is missing any of the four things a call needs. */
  private fun runOf(
    file: File,
    pid: String,
    properties: Properties
  ): PublishedRun? {
    val port = properties.getProperty(PORT_PROPERTY)?.toIntOrNull() ?: return null
    val token = properties.getProperty(TOKEN_PROPERTY) ?: return null
    val buildSha = properties.getProperty(BUILD_SHA_PROPERTY) ?: return null
    val hasWindow = properties.getProperty(WINDOW_PROPERTY)?.toBooleanStrictOrNull() ?: return null
    return PublishedRun(file, pid, port, token, buildSha, hasWindow)
  }

  /**
   * Whether the run a file is named after is still there, which is what makes this list a list of runs.
   *
   * A run deletes its own file, from a shutdown hook and from the [Closeable] — and **neither of those runs
   * for a run that was killed**, which is what a force quit, an out of memory and a `kill -9` all are. So the
   * file outlives the run often enough to matter, and what it costs is not only a stale name in a message:
   * the list is newest first, so a dead run can be the one a call is sent to, and that call is spent finding
   * out. Measured here — a window force quit three weeks ago was still being offered to every `--cli` call
   * beside the live one.
   *
   * Asking the OS rather than connecting, because this is read before anything is sent anywhere: the connect
   * is the [AgentCommandLine] path for a run that is alive and not listening, and it is a timeout rather than
   * an answer.
   *
   * A pid can be reused, so this says a process of that id exists rather than that it is Shark Dive. The
   * token is what settles the rest — a process holding that port and not this run's token is [DECLINED] —
   * and a name that is no number names no process, which is a file this never wrote.
   */
  private fun isRunning(pid: String): Boolean {
    val processId = pid.toLongOrNull() ?: return false
    return ProcessHandle.of(processId).map { it.isAlive }.orElse(false)
  }

  /**
   * Publishes this run, by writing the file beside the name and moving it onto it in one step.
   *
   * **A reader is watching this directory while this is written**, and one of them is the command line that
   * started this run, reading it every 250 milliseconds — so a file written straight into the name it is looked
   * for under is a run that can be found half published. What that used to cost is in [read]. The name it is
   * written under deliberately does not end in [RUN_SUFFIX], so it is not in the list at all until it is
   * complete, and it is a sibling so that the move is a rename inside one directory.
   */
  private fun write(
    file: File,
    port: Int,
    token: String,
    buildSha: String,
    hasWindow: Boolean
  ) {
    file.parentFile.mkdirs()
    val properties = Properties().apply {
      setProperty(PORT_PROPERTY, port.toString())
      setProperty(TOKEN_PROPERTY, token)
      setProperty(BUILD_SHA_PROPERTY, buildSha)
      setProperty(WINDOW_PROPERTY, hasWindow.toString())
    }
    val writing = File(file.parentFile, "${file.name}$WRITING_SUFFIX")
    writing.outputStream().use { properties.store(it, "Where this Shark Dive run answers agents") }
    // Best effort, and only worth anything on a machine with more than one user on it: the token is what
    // this is protecting, and a token nobody can read is a run no agent can reach. Before the move, so that
    // there is no moment in which the token is published and readable by anybody.
    writing.setReadable(false, false)
    writing.setReadable(true, true)
    Files.move(writing.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
  }

  /**
   * Waits for whatever is mid-call to have written its answer, because **a run ends while one is being made**.
   *
   * `close_heap_dump` on the last dump open ends the run, so the process this is serving from is on its way out
   * while the answer saying so is still on this thread — and the connection threads are daemons, so nothing
   * else holds the JVM open for them. Without this, the command that worked reads as "Shark Dive stopped
   * answering", which is the one thing a caller must not be told about a call that did what it said.
   *
   * Bounded, and short, because what it is waiting for is a `println` on a loopback socket of an answer that is
   * already built. A call still *working* — `dump_heap`, minutes of `adb` — is cut off as it is today: this
   * costs it the wait and then goes, rather than holding a closing app open for the length of a heap dump.
   */
  private fun letAnswersOut(callsInFlight: AtomicInteger) {
    var waited = 0L
    while (callsInFlight.get() > 0 && waited < ANSWER_DRAIN_MILLIS) {
      Thread.sleep(DRAIN_POLL_MILLIS)
      waited += DRAIN_POLL_MILLIS
    }
    if (callsInFlight.get() > 0) {
      SharkLog.d { "Stopped answering agents with ${callsInFlight.get()} calls still being worked on" }
    }
  }

  private fun accept(
    serverSocket: ServerSocket,
    token: String,
    heapDumps: AgentHeapDumps,
    serverVersion: String,
    sessions: File,
    callsInFlight: AtomicInteger
  ) {
    while (!serverSocket.isClosed) {
      try {
        val socket = serverSocket.accept()
        // A thread per agent, because a session is held open for as long as the agent is working and two
        // agents on one heap dump is a thing to allow rather than to serialise: what they would queue on
        // is the heap dump's own thread, which is where reads belong anyway.
        Thread({ serve(socket, token, heapDumps, serverVersion, sessions, callsInFlight) }, THREAD_NAME).apply {
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
   * One connection: the token and optionally a session to join, then a call per line until the agent goes
   * away. See [AgentWire].
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
    sessions: File,
    callsInFlight: AtomicInteger
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
      // two sessions to read rather than one file with both of their reasoning in it.
      val sessionFile = sessionFile(sessions, serverVersion, handshake.getOrNull(1))
      SharkLog.d { "An agent's session is being written to ${sessionFile.file}" }
      val connection = AgentConnection(
        // Read off disk per call rather than captured, so that an agent asking what has been done to a
        // heap dump sees what another one working on it right now has done so far.
        AgentTools(heapDumps) { AgentSessionFile.sessionsIn(sessions) },
        sessionFile
      )
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) {
          continue
        }
        // Counted around the answer *and the writing of it*, which is what [letAnswersOut] waits on: the
        // call that ends this run is answered from here while the run is going away underneath.
        callsInFlight.incrementAndGet()
        try {
          // Blocking on this thread rather than a scope of our own: a call is answered before the next is
          // read, which is what an agent sends anyway, and the reads inside suspend onto the heap dump's
          // thread where they belong.
          writer.println(runBlocking { connection.answer(line) })
        } finally {
          callsInFlight.decrementAndGet()
        }
      }
      SharkLog.d { "An agent disconnected" }
    }
  }

  /**
   * Where this connection's calls are written down: a session of its own, or the one it asked to join.
   *
   * A command line is a process per call, so it names the session its calls belong to rather than being one —
   * see [AgentCommandLine]. The name is checked here as well as there, because it becomes part of a file name
   * and it arrived from another process; a name this cannot use is a session of its own and a line saying so,
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
    val token: String,
    /**
     * The commit this run was built from, which is what makes a machine in the middle of a branch usable.
     *
     * A command line only ever talks to a run of its own build — see [AgentCommandLine] — because a tool
     * renamed on this branch is a refusal from the window still running last week's, and it arrives as "there
     * is no tool called that" rather than as "that window is a different build". Which is the normal state of
     * this machine while the surface is being worked on: the run from the last branch is still up.
     */
    val buildSha: String,
    /**
     * Whether this run draws windows.
     *
     * Written down rather than asked over the socket because it decides *whether to connect at all*: opening a
     * heap dump with no window and opening one in a window are two things to want, and a run is one or the
     * other for the life of it — see `shark.dive.app.HeadlessAgentHeapDumps`. So a command line that asked for
     * the other kind says so before it makes a call, rather than after one opened a gigabyte in the wrong
     * place.
     */
    val hasWindow: Boolean
  )

  private const val ANY_FREE_PORT = 0
  private const val BACKLOG = 8
  private const val TOKEN_BYTES = 16

  /** How long a run on its way out gives an answer to get onto the socket. See [letAnswersOut]. */
  private const val ANSWER_DRAIN_MILLIS = 2_000L
  private const val DRAIN_POLL_MILLIS = 10L

  /** Beside the runs answering links, the notes and the logs, which is everything else this app keeps. */
  internal const val RUN_SUFFIX = ".agent"

  /** After [RUN_SUFFIX] rather than instead of it, so that neither name is ever the other. See [write]. */
  private const val WRITING_SUFFIX = ".writing"

  /** Under the directory the runs publish themselves in, since a session is a run being talked to. */
  private const val SESSIONS_DIRECTORY = "sessions"
  internal const val ACCEPTED = "OK"
  internal const val DECLINED = "NO"

  /** Between the token and the session a connection is joining, which is why a name has no spaces. */
  private const val HANDSHAKE_SEPARATOR = ' '
  private const val PORT_PROPERTY = "port"
  private const val TOKEN_PROPERTY = "token"
  private const val BUILD_SHA_PROPERTY = "buildSha"
  private const val WINDOW_PROPERTY = "window"
  private const val THREAD_NAME = "shark-dive-agents"
}
