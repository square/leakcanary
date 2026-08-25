package shark.explorer.agent

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.util.Properties
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.exactHexObjectId

/**
 * The pipe an MCP client actually launches, end to end: stdin to a window and its answers back to stdout.
 *
 * Worth testing as a whole rather than in parts, because what it is for is the one thing a client can be
 * configured with — a command — reaching a port that changes every run. Anything between the two ends being
 * wrong is a client reporting a server with no tools.
 */
class AgentStdioBridgeTest {

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
    window = FakeAgentHeapDump(heapDump.explorer)
  }

  @After
  fun tearDown() {
    closeables.forEach { it.close() }
    heapDump.close()
  }

  @Test
  fun `a client's messages reach the window and its answers come back`() {
    closeables += AgentServer.listen(
      heapDumps = FakeAgentHeapDumps(listOf(window)),
      serverVersion = "1.2.3",
      directory = directory
    )

    val answers = bridge { send ->
      send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
      send(
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"describe_object",""" +
          """"arguments":{"object":"${exactHexObjectId(heapDump.holderObjectId)}",""" +
          """"reason":"Reading the holder's fields through the bridge."}}}"""
      )
    }

    assertThat(answers).hasSize(2)
    assertThat(answers[0]).contains("\"id\":1").contains("shark-explorer")
    assertThat(answers[1]).contains("\"id\":2").contains(HOLDER_CLASS_NAME)
    assertThat(window.reads).isNotEmpty
  }

  @Test
  fun `no run to talk to is reported rather than waited for`() {
    val exitCode = runBridge()

    assertThat(exitCode).isEqualTo(NOTHING_TO_TALK_TO)
  }

  @Test
  fun `a run that no longer answers on its port has its file cleared out`() {
    val port = ServerSocket(0).use { it.localPort }
    val stale = File(directory, "999999${AgentServer.RUN_SUFFIX}")
    stale.outputStream().use { output ->
      Properties().apply {
        setProperty("port", port.toString())
        setProperty("token", "0".repeat(32))
      }.store(output, null)
    }

    val exitCode = runBridge()

    assertThat(exitCode).isEqualTo(NOTHING_TO_TALK_TO)
    assertThat(stale).doesNotExist()
  }

  /**
   * Runs the bridge over a pipe, sends what [session] sends, and hands back the lines that came out.
   *
   * A pipe rather than a string of input, because a real client keeps stdin open until it has what it asked
   * for: closing it the moment the last message is written would be a race with the answer coming back, and a
   * test that lost it would be reporting the timing rather than the wiring.
   */
  private fun bridge(session: (send: (String) -> Unit) -> Unit): List<String> {
    val stdin = PipedOutputStream()
    val stdout = ByteArrayOutputStream()
    val previousIn = System.`in`
    val previousOut = System.out
    System.setIn(PipedInputStream(stdin))
    System.setOut(PrintStream(stdout, true, Charsets.UTF_8.name()))
    var sent = 0
    try {
      val bridge = Thread({ runBridge() }, "bridge under test").apply {
        isDaemon = true
        start()
      }
      session { message ->
        stdin.write("$message\n".toByteArray(Charsets.UTF_8))
        stdin.flush()
        sent++
        // One answer per message, waited for before the next goes out, which is what makes closing stdin at
        // the end of the session safe.
        awaitLines(stdout, sent)
      }
      stdin.close()
      bridge.join(JOIN_MILLIS)
    } finally {
      System.setIn(previousIn)
      System.setOut(previousOut)
    }
    return stdout.toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }
  }

  /** Nothing waited for, since the run these tests are about is either already published or never will be. */
  private fun runBridge(): Int = AgentStdioBridge.run(directory, pid = null, waitMillis = 0L)

  private fun awaitLines(
    stdout: ByteArrayOutputStream,
    count: Int
  ) {
    val giveUpAt = System.currentTimeMillis() + AWAIT_MILLIS
    while (System.currentTimeMillis() < giveUpAt) {
      if (stdout.toString(Charsets.UTF_8.name()).lines().count { it.isNotBlank() } >= count) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "Waited ${AWAIT_MILLIS}ms for $count answers and got: ${stdout.toString(Charsets.UTF_8.name())}"
    )
  }

  private companion object {

    /** What the bridge ends with when it found no window, which a client shows as a server that failed. */
    const val NOTHING_TO_TALK_TO = 1

    const val AWAIT_MILLIS = 10_000L
    const val POLL_MILLIS = 20L
    const val JOIN_MILLIS = 5_000L
  }
}
