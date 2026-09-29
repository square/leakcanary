package shark.dive.agent

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.exactHexObjectId

/**
 * A tool call typed at a window that is already open, which is the whole of how this app is talked to.
 *
 * Two things here are worth a test and the rest is translation. **A refusal has to come back as a refusal** —
 * the message on stderr and an exit code of its own, since the whole method rests on an agent being told no
 * in words it can act on. And **a shell's worth of calls has to be one session**: a connection held open
 * would gather an investigation, and a process per call has nothing to gather it with unless it says which
 * session it is joining — and **which process it joins is a walk rather than the parent**, since the shell an
 * agent's call arrives in is one command long. See [AgentServerTest] for the socket under this.
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
    window = FakeAgentHeapDump(heapDump.dive)
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
    // Indented, because the reader of a typed call is a person as often as a model, and the answer is the
    // tool's own object either way rather than a second rendering of it for the terminal.
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
    assertThat(session.toolCalls.map { it.tool }).containsExactly("open_heap_dumps", "list_leaks")
    // **One row per command typed**, which is what there is to record: a process connects, makes its one
    // call and ends, so nothing crosses this socket that isn't the call itself. Which connection each of them
    // was is in this run's log.
    assertThat(session.calls).hasSize(2)
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
    assertThat(said()).contains("No Shark Dive is running")
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
    val help = AgentCommandLine.help(command = "shark-dive")

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
  fun `a call from a shell given one command joins whatever drove that shell`() {
    // How an agent's calls arrive: Claude Code runs each of them in a shell of its own, so the shell is one
    // command long and the session has to be the process above it — the one whose life is the conversation.
    // Which is this test JVM here, standing in for whatever drove the shell.
    val shell = shellRunning(listOf("/bin/sh", "-c", "sleep $SHELL_SECONDS; :"))

    val name = AgentCommandLine.sessionName(shell.theCommandItRan())

    assertThat(name).isEqualTo("cli${ProcessHandle.current().pid()}")
  }

  @Test
  fun `a call from a person's own shell joins that shell`() {
    // And the case that is not an agent: a shell with no command on its command line lives as long as the
    // terminal tab it is in, so it is what gathers the calls typed in it, and two tabs stay two sessions.
    val shell = shellRunning(listOf("/bin/sh"), typing = "sleep $SHELL_SECONDS\n")

    val name = AgentCommandLine.sessionName(shell.theCommandItRan())

    assertThat(name).isEqualTo("cli${shell.pid()}")
  }

  @Test
  fun `a shell is walked past for the command on its command line, not for being a shell`() {
    assertThat(AgentCommandLine.ranOneCommand("/bin/zsh", listOf("-c", "source snapshot && eval x"))).isTrue
    // A login shell running one command, which is one option carrying two letters.
    assertThat(AgentCommandLine.ranOneCommand("/bin/bash", listOf("-lc", "x"))).isTrue
    assertThat(AgentCommandLine.ranOneCommand("/bin/zsh", emptyList())).isFalse
    assertThat(AgentCommandLine.ranOneCommand("/bin/zsh", listOf("-l"))).isFalse
  }

  @Test
  fun `something that is not a shell is where the walk stops, whatever its options are`() {
    // The two that make the name half of it earn its keep: `-c` resumes a conversation for the very agent
    // this is about, and walking past it would gather that conversation and the next into one session. And a
    // JVM's `-cp` is a short option with a `c` in it, so a walk reading options alone would go past the app
    // itself — the process every call here is made from.
    assertThat(AgentCommandLine.ranOneCommand("/opt/homebrew/bin/claude", listOf("-c"))).isFalse
    assertThat(AgentCommandLine.ranOneCommand("/usr/bin/java", listOf("-cp", "a.jar", "Main"))).isFalse
    // A process this one may not read, which is where the walk stops rather than guesses.
    assertThat(AgentCommandLine.ranOneCommand(null, listOf("-c", "x"))).isFalse
  }

  @Test
  fun `the help of one tool is that tool, and of no tool says which there are`() {
    val one = AgentCommandLine.help(command = "shark-dive", toolName = "conclude")

    assertThat(one).contains("conclude").doesNotContain("list_leaks")

    val none = AgentCommandLine.help(command = "shark-dive", toolName = "chain_from_a_gc_root")

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

  /**
   * Starts a shell and leaves it running something, which is the shape these tests are about: a call is made
   * by a process a shell started, so what the call joins is a walk up from one.
   *
   * Real processes rather than a fake [ProcessHandle], because what is being tested is what this platform
   * reports about a shell — the command, and whether the arguments carry the one it was given. A fake would
   * be this code's own idea of that, asserted against itself.
   */
  private fun shellRunning(
    command: List<String>,
    /** What to send on its stdin instead, for the shell given no command: a person types theirs. */
    typing: String? = null
  ): ProcessHandle {
    assumeTrue("These walk up from a shell and there is no /bin/sh here", File("/bin/sh").canExecute())
    val process = ProcessBuilder(command)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    closeables += Closeable { process.destroyForcibly() }
    if (typing != null) {
      // Written and left open, because a shell whose stdin has closed is a shell about to end.
      process.outputStream.write(typing.toByteArray(Charsets.UTF_8))
      process.outputStream.flush()
    }
    return process.toHandle()
  }

  /**
   * What this shell started, waited for: asking a shell to run something and reading its children back are
   * two moments, and the walk begins at the child.
   */
  private fun ProcessHandle.theCommandItRan(): ProcessHandle {
    val deadline = System.currentTimeMillis() + FORK_WAIT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      children().findFirst().orElse(null)?.let { return it }
      Thread.sleep(FORK_POLL_MILLIS)
    }
    throw AssertionError("The shell ${pid()} ran nothing within $FORK_WAIT_MILLIS ms")
  }

  private fun sessions(): List<AgentSession> =
    AgentSessionFile.sessionsIn(AgentServer.sessionsDirectory(directory))

  private fun printed(): String = printed.toString(Charsets.UTF_8.name())

  private fun said(): String = said.toString(Charsets.UTF_8.name())

  private companion object {

    /** What one shell's calls are gathered under, which is `cli<pid>` for a real one. */
    const val SESSION_NAME = "cli1234"

    /** Long enough that the shells these tests start outlast them, since they are killed rather than waited on. */
    const val SHELL_SECONDS = 30

    const val FORK_WAIT_MILLIS = 5_000
    const val FORK_POLL_MILLIS = 20L
  }
}
