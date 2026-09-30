package shark.dive.agent

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How an agent finds this run of the app and gets served by it, over a real socket.
 *
 * What is being tested is the half of this that isn't the calls: a run publishing where it answers, a token
 * being the whole of who may talk to it, and a file left behind by a run that is gone being cleared out by
 * whoever reads it next. [AgentConnectionTest] covers what is said once a connection is up.
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
    // The two a command line decides on before it connects: whether this run is its own build, and whether
    // it draws windows. See [AgentCommandLine].
    assertThat(run.buildSha).isEqualTo(BUILD_SHA)
    assertThat(run.hasWindow).isTrue()

    connect(run).use { client ->
      assertThat(client.accepted).isTrue()
      val answer = client.ask(CALL_LIST_HEAP_DUMPS)
      assertThat(answer).contains(window.heapDumpName).contains(window.heapDumpPath)
    }
  }

  @Test
  fun `a caller that quotes the wrong token is not listened to`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, token = "0".repeat(32)).use { client ->
      assertThat(client.accepted).isFalse()
    }

    // And the run is still there for a caller that has the right one, since a wrong token is a stale file
    // being read far more often than it is anything to worry about.
    connect(run).use { client -> assertThat(client.accepted).isTrue() }
  }

  @Test
  fun `two agents at once are two sessions of one run`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run).use { first ->
      connect(run).use { second ->
        assertThat(first.ask(CALL_LIST_HEAP_DUMPS)).contains(window.heapDumpName)
        assertThat(second.ask(CALL_LIST_HEAP_DUMPS)).contains(window.heapDumpName)
      }
    }

    // A file each, because a connection that named no session is an investigation of its own.
    assertThat(sessions()).hasSize(2)
  }

  @Test
  fun `two connections that name one session are one session`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, sessionName = "cli7").use { it.ask(NOT_A_CALL) }
    connect(run, sessionName = "cli7").use { it.ask(CALL_LIST_HEAP_DUMPS) }

    // What a command line needs of this end: a connection per call, and one file to read them in. A
    // connection that names nothing gets a session of its own. See [AgentCommandLineTest].
    val session = sessions().single()
    assertThat(session.sessionId).isEqualTo("cli7")
    assertThat(session.toolCalls.map { it.tool }).containsExactly(LIST_HEAP_DUMPS)
    // The line that was no call is in there too, since the full traffic is what a session holds: it reached
    // no tool and it is still what happened on that connection. See [AgentSessionCall.tool].
    assertThat(session.calls.map { it.tool }).containsExactly(null, LIST_HEAP_DUMPS)
  }

  @Test
  fun `a session name that could be a path is not made into one`() {
    listen()
    val run = AgentServer.publishedRuns(directory).single()

    connect(run, sessionName = "../../evil").use { client ->
      // Served, because the calls are none the worse for the name: what it loses is being gathered with the
      // others, and refusing the connection would lose the investigation instead.
      assertThat(client.accepted).isTrue()
      client.ask(CALL_LIST_HEAP_DUMPS)
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
  fun `closing a run waits for the answer of the call being made`() {
    // What `close_heap_dump` on the last dump open does to its own answer: the run ends from inside the call,
    // and the connection threads are daemons, so without the wait the answer never reaches the caller. See
    // [AgentServer.letAnswersOut].
    val callStarted = CountDownLatch(1)
    val letTheCallFinish = CountDownLatch(1)
    val busy = FakeAgentHeapDump(heapDump.dive) {
      callStarted.countDown()
      letTheCallFinish.await()
    }
    val listening = listen(busy)
    val run = AgentServer.publishedRuns(directory).single()
    val answered = CompletableFuture<String>()
    connect(run).use { client ->
      Thread { answered.complete(client.ask(callListLeaks())) }.start()
      assertThat(callStarted.await(A_WHILE_MILLIS, MILLISECONDS)).isTrue()

      val closed = CompletableFuture<Unit>()
      Thread {
        listening.close()
        closed.complete(Unit)
      }.start()
      // Still closing, because the call is still being worked on — which is the whole of what the wait is.
      Thread.sleep(A_MOMENT_MILLIS)
      assertThat(closed.isDone).isFalse()

      letTheCallFinish.countDown()
      closed.get(A_WHILE_MILLIS, MILLISECONDS)
      assertThat(answered.get(A_WHILE_MILLIS, MILLISECONDS)).contains("leaks")
    }
  }

  @Test
  fun `a run that was killed is taken off the list by whoever reads it next`() {
    // A file that reads as a perfectly good run and names a process that has gone, which is what a force
    // quit, an out of memory and a `kill -9` all leave behind: the shutdown hook and the Closeable that
    // delete it are the two things a killed run doesn't get to do.
    val killed = File(directory, "$NO_SUCH_PROCESS${AgentServer.RUN_SUFFIX}")
    killed.writeText("port=54028\ntoken=${"0".repeat(32)}")
    listen()

    val runs = AgentServer.publishedRuns(directory)

    assertThat(runs.map { it.pid }).containsExactly(ProcessHandle.current().pid().toString())
    assertThat(killed).doesNotExist()
    assertThat(log).anyMatch { it.contains("has ended") }
  }

  @Test
  fun `a file that names no run is left alone while the process it names is running`() {
    // Which is a file being written right now, or one written by a build older than one of the properties: a
    // run that something can still talk to either way, and a run a reader deletes the address of is a run
    // nothing can ever reach again. Named after a process that is running, since a name that isn't is a run
    // that has ended and is cleared out before anything reads the file at all.
    val nonsense = File(directory, "${ProcessHandle.current().pid()}${AgentServer.RUN_SUFFIX}")
    nonsense.writeText("this file is not a published run")

    assertThat(AgentServer.publishedRuns(directory)).isEmpty()
    assertThat(nonsense).exists()
    assertThat(log).anyMatch { it.contains("names no run") }
  }

  @Test
  fun `the name a run is written under before it is published is not one readers look for`() {
    // Which is the whole of publishing in one step: the file is written beside the name and moved onto it, so
    // what a reader can see while it is being written is nothing at all. Written by hand here, since the real
    // one exists for microseconds — and a publish this one is in the way of is a publish that has to work.
    val halfWritten = File(directory, "${ProcessHandle.current().pid()}${AgentServer.RUN_SUFFIX}.writing")
    halfWritten.writeText("#Where this Shark Dive run answers agents")

    listen()

    assertThat(AgentServer.publishedRuns(directory).map { it.buildSha }).containsExactly(BUILD_SHA)
  }

  private fun listen(dump: AgentHeapDump = window): Closeable = AgentServer.listen(
    heapDumps = FakeAgentHeapDumps(listOf(dump)),
    serverVersion = "1.2.3",
    buildSha = BUILD_SHA,
    hasWindow = true,
    directory = directory
  ).also { closeables += it }

  private fun connect(
    run: AgentServer.PublishedRun,
    token: String = run.token,
    sessionName: String? = null
  ): TestClient = TestClient(run.port, token, sessionName)

  private fun sessions(): List<AgentSession> =
    AgentSessionFile.sessionsIn(AgentServer.sessionsDirectory(directory))

  /** A call that reads the heap dump, which is what makes it a call there is something to wait for. */
  private fun callListLeaks(): String =
    """{"tool":"list_leaks","arguments":{"heapDump":"${window.heapDumpName}","reason":"Reading it."}}"""

  /** An agent's end of the connection, as far as this test needs one: a token, then a line at a time. */
  private class TestClient(
    port: Int,
    token: String,
    /** The session this connection joins, which a command line names and nothing else does. */
    sessionName: String?
  ) : Closeable {

    private val socket = Socket(InetAddress.getLoopbackAddress(), port)
    private val toApp = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
    private val fromApp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

    val accepted: Boolean

    init {
      toApp.println(listOfNotNull(token, sessionName).joinToString(" "))
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

    /**
     * A process id no machine issues, so that a file named after it is a run that has ended.
     *
     * Rather than a big-looking number: Linux allows pids up to 2^22, so a test picking 999999 is one that
     * passes until the machine running it is busy enough to have reached that pid.
     */
    const val NO_SUCH_PROCESS = Long.MAX_VALUE

    /** A line that reaches no tool, which is a row of a session like any other. See [AgentConnection]. */
    const val NOT_A_CALL = "this is not one JSON object"

    const val CALL_LIST_HEAP_DUMPS =
      """{"tool":"$LIST_HEAP_DUMPS","arguments":{"reason":"Finding out what is open."}}"""

    /** Which build this run says it is, which is what a command line filters runs by. */
    const val BUILD_SHA = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b"

    /** Long enough that a thread that was going to get somewhere has, short enough to fail a hang. */
    const val A_WHILE_MILLIS = 10_000L

    /** Long enough to tell a thread that is waiting from one that has already gone past. */
    const val A_MOMENT_MILLIS = 200L
  }
}
