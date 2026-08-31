package shark.dive.agent

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How an agent finds this run of the app and gets served by it, over a real socket.
 *
 * What is being tested is the half of this that isn't the protocol: a run publishing where it answers, a
 * token being the whole of who may talk to it, and a file left behind by a run that is gone being cleared out
 * by whoever reads it next. [McpSessionTest] covers what is said once a connection is up.
 */
class AgentServerTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private lateinit var directory: File
  private lateinit var heapDump: InvestigationHeapDump
  private lateinit var window: FakeAgentHeapDump
  private val closeables = mutableListOf<Closeable>()

  @Before
  fun setUp() {
    directory = temporaryFolder.newFolder("agents")
    heapDump = temporaryFolder.applicationHoldsActivityThroughHolder()
    window = FakeAgentHeapDump(heapDump.dive)
  }

  @After
  fun tearDown() {
    closeables.forEach { it.close() }
    heapDump.close()
  }

  @Test
  fun `a run publishes where it answers, and answers there`() {
    listen()

    val run = AgentServer.publishedRuns(directory).single()
    assertThat(run.pid).isEqualTo(ProcessHandle.current().pid().toString())
    assertThat(run.port).isGreaterThan(0)
    assertThat(run.token).hasSize(32)

    connect(run).use { client ->
      assertThat(client.accepted).isTrue()
      val answer = client.ask(
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"open_heap_dumps",""" +
          """"arguments":{"reason":"Finding out what is open."}}}"""
      )
      assertThat(answer).contains(heapDump.dive.heapDumpFile.name).contains(window.windowId)
    }
  }

  @Test
  fun `a client that quotes the wrong token is not listened to`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, token = "0".repeat(32)).use { client ->
      assertThat(client.accepted).isFalse()
    }

    // And the run is still there for a client that has the right one, since a wrong token is a stale file
    // being read far more often than it is anything to worry about.
    connect(run).use { client -> assertThat(client.accepted).isTrue() }
  }

  @Test
  fun `two agents at once are two sessions of one run`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run).use { first ->
      connect(run).use { second ->
        assertThat(first.ask(PING)).contains("\"id\":1")
        assertThat(second.ask(PING)).contains("\"id\":1")
      }
    }
  }

  @Test
  fun `two connections that name one session are one session`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, sessionName = "cli7").use { it.ask(PING) }
    connect(run, sessionName = "cli7").use { it.ask(CALL_OPEN_HEAP_DUMPS) }

    // What a command line needs of this end: a connection per call, and one file to read them in. A client
    // that holds a connection open says nothing and gets a session of its own. See [AgentCommandLineTest].
    val session = sessions().single()
    assertThat(session.sessionId).isEqualTo("cli7")
    assertThat(session.toolCalls.map { it.tool }).containsExactly("open_heap_dumps")
    // The ping is in there too, since the full traffic is what a session holds: it reached no tool and it is
    // still what happened on that connection. See [AgentSessionCall.method].
    assertThat(session.calls.map { it.method }).containsExactly("ping", "tools/call")
  }

  @Test
  fun `a connection that says which way in it is has every line of it recorded that way`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, sessionName = "cli7", over = AgentTransport.CLI).use { it.ask(CALL_OPEN_HEAP_DUMPS) }
    // And one that says nothing is a client holding a session open, which is what every MCP client does and
    // what everything did before the command line existed. See [AgentServer].
    connect(run).use { it.ask(CALL_OPEN_HEAP_DUMPS) }

    assertThat(sessions().map { it.transports })
      .containsExactlyInAnyOrder(listOf(AgentTransport.CLI), listOf(AgentTransport.MCP))
  }

  @Test
  fun `a connection that names a way in this build has none of is served and recorded as MCP`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, sessionName = "odd", handshakeSuffix = " carrier-pigeon").use {
      assertThat(it.accepted).isTrue()
      it.ask(CALL_OPEN_HEAP_DUMPS)
    }

    // Served, for the reason a name this cannot use is: the calls are none the worse for it, and refusing the
    // connection would lose the investigation to protect a label. The log says which word it was.
    assertThat(sessions().single().transports).containsExactly(AgentTransport.MCP)
    assertThat(log).anyMatch { it.contains("carrier-pigeon") }
  }

  @Test
  fun `a session name that could be a path is not made into one`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, sessionName = "../../evil").use { client ->
      // Served, because the calls are none the worse for the name: what it loses is being gathered with the
      // others, and refusing the connection would lose the investigation instead.
      assertThat(client.accepted).isTrue()
      client.ask(CALL_OPEN_HEAP_DUMPS)
    }

    assertThat(sessions().single().sessionId).isNotEqualTo("../../evil")
    assertThat(log).anyMatch { it.contains("is no session name") }
    assertThat(temporaryFolder.root.walkTopDown().filter { it.name.endsWith(".jsonl") }.toList())
      .hasSize(1)
  }

  @Test
  fun `closing a run takes it off the list`() {
    val listening = listen()

    listening.close()

    assertThat(AgentServer.publishedRuns(directory)).isEmpty()
  }

  @Test
  fun `a file that names no run is deleted by whoever reads it`() {
    val nonsense = File(directory, "1234${AgentServer.RUN_SUFFIX}")
    nonsense.writeText("this file is not a published run")

    assertThat(AgentServer.publishedRuns(directory)).isEmpty()
    assertThat(nonsense).doesNotExist()
    assertThat(log).anyMatch { it.contains("names no run") }
  }

  private fun listen(): Closeable = AgentServer.listen(
    heapDumps = FakeAgentHeapDumps(listOf(window)),
    serverVersion = "1.2.3",
    directory = directory
  ).also { closeables += it }

  private fun connect(
    run: AgentServer.PublishedRun,
    token: String = run.token,
    sessionName: String? = null,
    over: AgentTransport? = null,
    /** Whatever else a client puts on the handshake, for the words this build has no way in for. */
    handshakeSuffix: String = ""
  ): TestClient = TestClient(run.port, token, sessionName, over, handshakeSuffix)

  private fun sessions(): List<AgentSession> =
    AgentSessionFile.sessionsIn(AgentServer.sessionsDirectory(directory))

  /** An agent's end of the connection, as far as this test needs one: a token, then a line at a time. */
  private class TestClient(
    port: Int,
    token: String,
    /** The session this connection joins, which a command line names and a client holding one open doesn't. */
    sessionName: String?,
    /** And which way in it is, which only a command line says. See [AgentTransport]. */
    over: AgentTransport?,
    handshakeSuffix: String
  ) : Closeable {

    private val socket = Socket(InetAddress.getLoopbackAddress(), port)
    private val toApp = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
    private val fromApp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

    val accepted: Boolean

    init {
      toApp.println(listOfNotNull(token, sessionName, over?.recorded).joinToString(" ") + handshakeSuffix)
      accepted = fromApp.readLine() == AgentServer.ACCEPTED
    }

    fun ask(message: String): String {
      toApp.println(message)
      return requireNotNull(fromApp.readLine()) { "The run answered nothing to $message" }
    }

    override fun close() {
      socket.close()
    }
  }

  private companion object {

    const val PING = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

    const val CALL_OPEN_HEAP_DUMPS =
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"open_heap_dumps",""" +
        """"arguments":{"reason":"Finding out what is open."}}}"""
  }
}
