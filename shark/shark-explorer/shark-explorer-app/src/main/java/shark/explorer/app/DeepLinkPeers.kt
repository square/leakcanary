package shark.explorer.app

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Properties
import shark.SharkLog
import shark.explorer.DeepLink

/**
 * How a link reaches the run that has the window it names, when the OS starts a new process to deliver it.
 *
 * Which is Windows and Linux always, and macOS whenever the app was not launched from its own bundle —
 * a `./gradlew run` among others, see the module's AGENTS.md. A packaged macOS app is handed the URL
 * inside the process that is already running and never gets here.
 *
 * **Every run listens, rather than one of them being the instance and the others deferring to it.** Several
 * explorers open at once is the normal way this app is used — one per piece of work, often on the same heap
 * dump — and making the second run of it hand its command line to the first and exit would take that away.
 * So a run publishes where it can be reached, and a run holding a link asks each of the others in turn
 * whether the window is theirs. Nobody is in charge and nothing is lost when any of them is killed.
 *
 * The socket is on the loopback address, so nothing off this machine can reach it, and a caller has to
 * quote the token out of the file to be listened to at all — which proves it can read the user's home
 * directory, and therefore that it is the user.
 */
internal object DeepLinkPeers {

  /**
   * Publishes this run and answers links for it until closed.
   *
   * Failing to listen is not a reason to refuse to start: the app works, and what stops working is links
   * arriving from another process — which the log then says, rather than links quietly going nowhere.
   */
  fun listen(windows: ExplorerWindows): Closeable {
    val serverSocket = try {
      ServerSocket(ANY_FREE_PORT, BACKLOG, InetAddress.getLoopbackAddress())
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not listen for links: this run will not answer any" }
      return Closeable {}
    }
    val token = newToken()
    val file = File(peerDirectory(), "${ProcessHandle.current().pid()}$PEER_SUFFIX")
    return try {
      write(file, serverSocket.localPort, token)
      SharkLog.d { "Answering links on port ${serverSocket.localPort}, published as $file" }
      val thread = Thread({ accept(serverSocket, token, windows) }, THREAD_NAME).apply {
        isDaemon = true
        start()
      }
      // A run that is killed rather than closed leaves its file behind, which the next run to read the
      // directory deletes when nothing answers on the port. This is only the tidy case.
      val close = Closeable {
        file.delete()
        serverSocket.close()
        thread.interrupt()
      }
      Runtime.getRuntime().addShutdownHook(Thread { file.delete() })
      close
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not publish this run at $file: it will not answer links" }
      serverSocket.close()
      Closeable {}
    }
  }

  /**
   * Follows [link] in this run, or in whichever other run has a window of the heap dump it names.
   *
   * Which is what a run handed a link by the OS does with it, and the macOS half of the reason this exists
   * at all: there, one installed app is handed every `shark://` link on the machine, including the ones
   * about a heap dump open in a run from source that the OS knows nothing about.
   *
   * **A run claims a link only for a window it already has**, never for a heap dump it could open, or every
   * run on the machine would claim every link. Whoever is left with it opens the dump — see
   * [ExplorerWindows.open] — so a link with nobody to take it lands in the run the OS chose, which is the
   * installed app.
   *
   * Asking the others is a connection each, so it happens off the caller's thread — a link arrives on the
   * event thread there, and a run that has been killed is only found out about by waiting for it. A window of
   * this run is answered before any of that, on the thread the link came in on: it takes no disk and no
   * socket to see that a link is about a heap dump on screen here.
   */
  fun follow(
    link: DeepLink,
    windows: ExplorerWindows
  ) {
    if (windows.windowsFor(link).isNotEmpty()) {
      windows.open(link)
      return
    }
    Thread({
      // Nobody else's, so this run answers for it, which is opening that heap dump here — or asking where it
      // is, since a link says the dump's name and not where it is. See [ExplorerWindows.open].
      deliver(listOf(link)).forEach { windows.open(it) }
    }, THREAD_NAME).apply {
      isDaemon = true
      start()
    }
  }

  /**
   * Hands each of [links] to whichever other run has a window of the heap dump it names, and returns the
   * ones nobody claimed.
   *
   * The leftovers are the caller's to answer for, which is what makes a link whose window has gone a window
   * of that heap dump here rather than a process that started and exited without a word.
   *
   * The link goes out exactly as it arrived: what a run does about a heap dump it has no window for is that
   * run's own business — open it, or ask which one, or ask where it is — and every one of those answers
   * belongs to whoever ends up with the link rather than to whoever passed it on. See [ExplorerWindows.open].
   */
  fun deliver(links: List<DeepLink>): List<DeepLink> {
    if (links.isEmpty()) {
      return emptyList()
    }
    val peers = peers()
    return links.filter { link -> peers.none { peer -> peer.deliver(link) } }
  }

  /** Every other run that has published itself, stale files cleared out on the way past. */
  private fun peers(): List<Peer> {
    val ourselves = ProcessHandle.current().pid()
    val files = peerDirectory().listFiles { file -> file.name.endsWith(PEER_SUFFIX) }.orEmpty()
    return files.mapNotNull { file ->
      if (file.name == "$ourselves$PEER_SUFFIX") null else read(file)
    }
  }

  private fun read(file: File): Peer? {
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
        Peer(file, port, token)
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not read $file, so this run cannot be asked about links" }
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
    file.outputStream().use { properties.store(it, "Where this Shark Explorer run answers links") }
    // Best effort, and only worth anything on a machine with more than one user on it: the token is what
    // this is protecting, and a token nobody can read is a run nothing can send a link to.
    file.setReadable(false, false)
    file.setReadable(true, true)
  }

  /**
   * One line in, one line out, per connection: the token and the link, answered with whether a window of the
   * heap dump this link names belongs to this run.
   */
  private fun accept(
    serverSocket: ServerSocket,
    token: String,
    windows: ExplorerWindows
  ) {
    while (!serverSocket.isClosed) {
      try {
        serverSocket.accept().use { socket ->
          socket.soTimeout = READ_TIMEOUT_MILLIS
          answer(socket, token, windows)
        }
      } catch (closed: SocketException) {
        // Which is what closing the socket out from under accept() looks like, and it is how this ends.
        SharkLog.d { "Stopped answering links: ${closed.message}" }
        return
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "A link could not be read off the socket, carrying on listening" }
      }
    }
  }

  private fun answer(
    socket: Socket,
    token: String,
    windows: ExplorerWindows
  ) {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
    val request = reader.readLine()
    if (request == null) {
      SharkLog.d { "Something connected asking nothing" }
      return
    }
    val sentToken = request.substringBefore(' ')
    val uri = request.substringAfter(' ', "")
    if (sentToken != token) {
      // Loopback only, so this is a stale file being read by a run that started before this one, far more
      // often than it is anything to worry about.
      SharkLog.d { "A link arrived quoting the wrong token, so it was not read" }
      writer.println(DECLINED)
      return
    }
    val link = try {
      DeepLink.parse(uri)
    } catch (invalidLink: IllegalArgumentException) {
      SharkLog.d { "A run handed over \"$uri\", which is no link: ${invalidLink.message}" }
      writer.println(DECLINED)
      return
    }
    // Answered before the window is asked to go anywhere, because the run on the other end is waiting to
    // find out whether to keep looking, and going somewhere is a frame away rather than a read away.
    if (windows.windowsFor(link).isNotEmpty()) {
      writer.println(ACCEPTED)
      windows.open(link)
    } else {
      writer.println(DECLINED)
    }
  }

  private fun newToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun peerDirectory(): File = File(SHARK_EXPLORER_DIRECTORY, "runs")

  /** Another run of this app, and the way to ask it whether a window is one of its. */
  private class Peer(
    private val file: File,
    private val port: Int,
    private val token: String
  ) {

    /** Whether this run took [link], which only the run whose window it names does. */
    fun deliver(link: DeepLink): Boolean = try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
        socket.soTimeout = READ_TIMEOUT_MILLIS
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        writer.println("$token ${link.toUri()}")
        val answer =
          BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
        answer == ACCEPTED
      }
    } catch (throwable: Throwable) {
      // A run that was killed leaves its file behind and nothing on the port, which is the common case
      // here rather than a failure: clear it out so the directory is the runs that are actually up.
      SharkLog.d { "Nothing answered on port $port, so $file names a run that has gone: deleting it" }
      file.delete()
      false
    }
  }

  private const val ANY_FREE_PORT = 0
  private const val BACKLOG = 8
  private const val CONNECT_TIMEOUT_MILLIS = 500
  private const val READ_TIMEOUT_MILLIS = 2_000
  private const val TOKEN_BYTES = 16
  private const val PEER_SUFFIX = ".run"
  private const val PORT_PROPERTY = "port"
  private const val TOKEN_PROPERTY = "token"
  private const val ACCEPTED = "OK"
  private const val DECLINED = "NO"
  private const val THREAD_NAME = "shark-explorer-links"
}
