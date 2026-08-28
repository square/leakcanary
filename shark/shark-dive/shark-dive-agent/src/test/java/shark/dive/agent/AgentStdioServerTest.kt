package shark.dive.agent

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.exactHexObjectId

/**
 * The tools served over this process's own stdio, which is `--mcp-stdio --no-ui`.
 *
 * The same shape as [AgentStdioBridgeTest] and the opposite case: there the tools are in another process and
 * this is a pipe to it, here there is no other process and no socket at all. Worth its own test because that
 * makes it the one path where an agent's calls and the heap dump's own diagnostics share a process, and the
 * rule is that only one of them may reach stdout.
 */
class AgentStdioServerTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private lateinit var sessions: File
  private lateinit var heapDump: InvestigationHeapDump
  private lateinit var dump: FakeAgentHeapDump

  @Before
  fun setUp() {
    sessions = temporaryFolder.newFolder("sessions")
    heapDump = temporaryFolder.applicationHoldsActivityThroughHolder()
    dump = FakeAgentHeapDump(heapDump.dive)
  }

  @After
  fun tearDown() {
    heapDump.close()
  }

  @Test
  fun `a client's messages are answered from this process`() {
    val answers = serve { send ->
      send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
      send(
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"describe_object",""" +
          """"arguments":{"object":"${exactHexObjectId(heapDump.holderObjectId)}",""" +
          """"reason":"Reading the holder's fields with no window open."}}}"""
      )
    }

    assertThat(answers).hasSize(2)
    assertThat(answers[0]).contains("\"id\":1").contains("shark-dive")
    assertThat(answers[1]).contains("\"id\":2").contains(HOLDER_CLASS_NAME)
    assertThat(dump.reads).isNotEmpty
  }

  @Test
  fun `a session with no window is written down like any other`() {
    serve { send ->
      send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
      send(
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_leaks",""" +
          """"arguments":{"reason":"What the dump says about itself."}}}"""
      )
    }

    // The *Agent logs* screen of whoever opens this dump in a window later is what reads these, which is the
    // point of writing them from a run that has no screen at all.
    val session = AgentSessionFile.sessionsIn(sessions).single()
    assertThat(session.calls.map { it.tool }).contains("list_leaks")
  }

  @Test
  fun `a blank line is not a message`() {
    val answers = serve { send ->
      send("")
      send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
    }

    // A client that ends its messages with a newline the reader then sees again would otherwise get an error
    // for a message it never sent.
    assertThat(answers).hasSize(1)
  }

  /**
   * Runs the server over a pipe, sends what [session] sends, and hands back the lines that came out.
   *
   * A pipe rather than a string of input, for the reason [AgentStdioBridgeTest] uses one: a real client keeps
   * stdin open until it has been answered, so closing it after the last message would be a race with the
   * answer.
   */
  private fun serve(session: (send: (String) -> Unit) -> Unit): List<String> {
    val stdin = PipedOutputStream()
    val stdout = ByteArrayOutputStream()
    val previousIn = System.`in`
    val previousOut = System.out
    System.setIn(PipedInputStream(stdin))
    System.setOut(PrintStream(stdout, true, Charsets.UTF_8.name()))
    var expected = 0
    try {
      val server = Thread({
        AgentStdioServer.run(
          heapDumps = FakeAgentHeapDumps(listOf(dump)),
          serverVersion = "1.2.3",
          sessions = sessions
        )
      }, "stdio server under test").apply {
        isDaemon = true
        start()
      }
      session { message ->
        stdin.write("$message\n".toByteArray(Charsets.UTF_8))
        stdin.flush()
        if (message.isNotBlank()) {
          expected++
          awaitLines(stdout, expected)
        }
      }
      stdin.close()
      server.join(JOIN_MILLIS)
    } finally {
      System.setIn(previousIn)
      System.setOut(previousOut)
    }
    return stdout.toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }
  }

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
    const val AWAIT_MILLIS = 10_000L
    const val POLL_MILLIS = 20L
    const val JOIN_MILLIS = 5_000L
  }
}
