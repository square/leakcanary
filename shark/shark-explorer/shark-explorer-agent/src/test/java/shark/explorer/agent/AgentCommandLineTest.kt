package shark.explorer.agent

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.exactHexObjectId

/**
 * A tool call typed at a window that is already open, which is the other adapter over the same tools.
 *
 * Two things here are worth a test and the rest is translation. **A refusal has to come back as a refusal** —
 * the message on stderr and an exit code of its own, since the whole method rests on an agent being told no
 * in words it can act on. And **a shell's worth of calls has to be one session**: a connection is what
 * gathers an MCP investigation, and a process per call has nothing to gather it with unless it says which
 * session it is joining. See [AgentServerTest] for the socket under this.
 */
class AgentCommandLineTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private lateinit var directory: File
  private lateinit var heapDump: InvestigationHeapDump
  private lateinit var window: FakeAgentHeapDump
  private val closeables = mutableListOf<Closeable>()

  /** What a shell would show, which is the answer on stdout and everything else on stderr. */
  private val printed = ByteArrayOutputStream()
  private val said = ByteArrayOutputStream()

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
  fun `a call is answered with the tool's own JSON`() {
    listen()

    val exitCode = agent(
      "describe_object",
      "object=${exactHexObjectId(heapDump.holderObjectId)}",
      "reason=Reading the holder's fields."
    )

    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    // Indented, and the same JSON an MCP client is answered with: a person reads this one and a model reads
    // the other, and neither of them is reading a different surface.
    assertThat(printed()).contains(HOLDER_CLASS_NAME).contains("\n  ")
    assertThat(window.reads).isNotEmpty
  }

  @Test
  fun `a refusal is what the shell gets back, and it says what to do instead`() {
    listen()

    val exitCode = agent("describe_object", "object=${exactHexObjectId(heapDump.holderObjectId)}")

    // Its own exit code, because a refusal is not a failure of the command: the tool answered, and what it
    // answered is the next thing to do. Nothing on stdout, so a shell keeping that for the JSON gets none.
    assertThat(exitCode).isEqualTo(AgentCommandLine.REFUSED)
    assertThat(printed()).isEmpty()
    assertThat(said()).contains("needs `reason`")
  }

  @Test
  fun `the calls of one shell are one session`() {
    listen()

    agent("open_heap_dumps", "reason=Finding out what is open.")
    agent("list_leaks", "reason=Reading what the dump says about itself.")

    // One row of the *Agent logs* screen rather than two, which is the whole of what naming a session buys:
    // an investigation is what somebody reads afterwards, and a process per call would have cut it up.
    val session = sessions().single()
    assertThat(session.sessionId).isEqualTo(SESSION_NAME)
    assertThat(session.calls.map { it.tool }).containsExactly("open_heap_dumps", "list_leaks")
    // Said once, by the call that started the session, since a file with two headers is two sessions.
    assertThat(session.client).isEqualTo("shark-explorer-cli")
  }

  @Test
  fun `the calls of another shell are another session`() {
    listen()

    agent("open_heap_dumps", "reason=Finding out what is open.")
    agent("open_heap_dumps", "reason=Finding out what is open.", sessionName = "cli99")

    // Two agents at one heap dump are two investigations to read, exactly as two connections are.
    assertThat(sessions().map { it.sessionId }).containsExactlyInAnyOrder(SESSION_NAME, "cli99")
  }

  @Test
  fun `a list argument is spelled with commas`() {
    listen()

    val exitCode = agent(
      "find_objects",
      "className=Holder",
      "kinds=INSTANCE,CLASS",
      "reason=Checking there is only one holder."
    )

    // A shell has no brackets, so the one argument shape with no spelling of its own gets one here. Refused
    // rather than misread if it arrived as text, which is what makes this assertion about the commas.
    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    assertThat(printed()).contains(HOLDER_CLASS_NAME)
  }

  @Test
  fun `a call with no run to talk to says so rather than making something up`() {
    val exitCode = agent("open_heap_dumps", "reason=Finding out what is open.")

    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(printed()).isEmpty()
    assertThat(said()).contains("No Shark Explorer is running")
  }

  @Test
  fun `a session name that could be a path is refused before anything is called`() {
    listen()

    val exitCode = agent("open_heap_dumps", "reason=Finding out what is open.", sessionName = "../../evil")

    // It becomes part of a file name, so the caller hears about it on the call it got wrong rather than
    // finding a session file somewhere else. The app end checks it too — see [AgentServerTest].
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("is no session name")
    assertThat(sessions()).isEmpty()
  }

  @Test
  fun `a word that is no argument is a message about arguments`() {
    listen()

    val exitCode = agent("describe_object", "0x7205")

    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("An argument is `name=value`")
  }

  @Test
  fun `the help is every tool of this build, with how to type its arguments`() {
    val help = AgentCommandLine.help(command = "shark-explorer")

    // Generated from the registry, so a tool added without being described here is a test failure rather
    // than a tool an agent using the command line never hears about.
    agentTools(FakeAgentHeapDumps()).all.forEach { tool ->
      assertThat(help).contains(tool.name).contains(tool.description)
    }
    // The one thing the schema doesn't say, because JSON has brackets and a command line hasn't.
    assertThat(help).contains("comma separated")
    // And `reason` is said in the preamble rather than under each of sixteen tools, which would be a sixth
    // of the help spent on the one argument every tool takes.
    assertThat(help).contains("Every tool takes `reason`")
    assertThat(help.lines().filter { it.trim().startsWith("reason (") }).isEmpty()
  }

  @Test
  fun `the help of one tool is that tool, and of no tool says which there are`() {
    val one = AgentCommandLine.help(command = "shark-explorer", toolName = "conclude")

    assertThat(one).contains("conclude").doesNotContain("list_leaks")

    val none = AgentCommandLine.help(command = "shark-explorer", toolName = "chain_from_a_gc_root")

    assertThat(none).contains("There is no tool").contains("chain_from_gc_root")
  }

  private fun listen(): Closeable = AgentServer.listen(
    heapDumps = FakeAgentHeapDumps(listOf(window)),
    serverVersion = "1.2.3",
    directory = directory
  ).also { closeables += it }

  /**
   * Runs one command and hands back its exit code, with stdout and stderr collected.
   *
   * Nothing waited for: the run these tests are about is either already published or never will be, and a
   * window is not something a test can open.
   */
  private fun agent(
    vararg words: String,
    sessionName: String = SESSION_NAME
  ): Int {
    val previousOut = System.out
    val previousErr = System.err
    System.setOut(PrintStream(printed, true, Charsets.UTF_8.name()))
    System.setErr(PrintStream(said, true, Charsets.UTF_8.name()))
    return try {
      AgentCommandLine.run(
        directory = directory,
        words = words.toList(),
        pid = null,
        sessionName = sessionName,
        waitMillis = 0L,
        openAWindow = null
      )
    } finally {
      System.setOut(previousOut)
      System.setErr(previousErr)
    }
  }

  private fun sessions(): List<AgentSession> =
    AgentSessionFile.sessionsIn(AgentServer.sessionsDirectory(directory))

  private fun printed(): String = printed.toString(Charsets.UTF_8.name())

  private fun said(): String = said.toString(Charsets.UTF_8.name())

  private companion object {

    /** What one shell's calls are gathered under, which is `cli<pid>` for a real one. */
    const val SESSION_NAME = "cli1234"
  }
}
