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
 * One command typed at a run that is already open, which is the whole of how this app is talked to.
 *
 * Four things here are worth a test and the rest is translation. **A refusal has to come back as a refusal** —
 * the message on stderr and an exit code of its own, since the whole method rests on an agent being told no
 * in words it can act on. **A shell's worth of commands has to be one session**: a connection held open would
 * gather an investigation, and a process per command has nothing to gather it with unless it says which
 * session it is joining — and **which process it joins is a walk rather than the parent**, since the shell an
 * agent's command arrives in is one command long. **And which run a command lands in is never a guess**: one
 * run of this build or a message naming the ones there are, which is what the rest of this covers. See
 * [AgentServerTest] for the socket under it.
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
  fun `a command is answered with the tool's own JSON`() {
    listen()

    val exitCode = cli(
      "describe_object",
      "heapDumpKey=${window.heapDumpName}",
      "object=${exactHexObjectId(heapDump.holderObjectId)}",
      "reason=Reading the holder's fields."
    )

    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    // Indented, because the reader of a typed command is a person as often as a model, and the answer is the
    // tool's own object either way rather than a second rendering of it for the terminal.
    assertThat(printed()).contains(HOLDER_CLASS_NAME).contains("\n  ")
    assertThat(window.reads).isNotEmpty
  }

  @Test
  fun `a refusal is what the shell gets back, and it says what to do instead`() {
    listen()

    val exitCode = cli(
      "describe_object",
      "heapDumpKey=${window.heapDumpName}",
      "object=com.example.MainActivity",
      "reason=Reading the activity's fields."
    )

    // Its own exit code, because a refusal is not a failure of the command: the tool answered, and what it
    // answered is the next thing to do. Nothing on stdout, so a shell keeping that for the JSON gets none.
    assertThat(exitCode).isEqualTo(AgentCommandLine.REFUSED)
    assertThat(printed()).isEmpty()
    assertThat(said()).contains("no object address").contains("find_objects")
  }

  @Test
  fun `a command of a named session needs its reason, and is refused before anything is called`() {
    listen()

    val exitCode = cli(LIST_HEAP_DUMPS)

    // The pair: `--session=` says these calls are one investigation somebody will read afterwards, and a call
    // with no sentence beside it is a read they cannot follow. Refused here rather than in every tool, because
    // this is the one place that knows both halves — a command line with no session is a person typing one
    // command, and being refused for not writing a sentence teaches them nothing. See [AgentArguments.reason].
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("needs `reason`").contains(AgentCommandLine.SESSION_OPTION + SESSION_NAME)
    assertThat(sessions()).isEmpty()
  }

  @Test
  fun `a command nobody said a session for is answered with no reason given`() {
    listen()

    val exitCode = cli(LIST_HEAP_DUMPS, sessionName = null)

    // Which is a person at a terminal: `--cli list_heap_dumps` is a question with no sentence to write under
    // it. The session it is written down in is named after the shell it was typed in — see
    // [AgentCommandLine.defaultSessionName].
    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    assertThat(printed()).contains(window.heapDumpName)
  }

  @Test
  fun `the commands of one shell are one session`() {
    listen()

    cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.")
    cli("list_leaks", "heapDumpKey=${window.heapDumpName}", "reason=Reading what the dump says about itself.")

    // One row of the *Agent logs* screen rather than two, which is the whole of what naming a session buys:
    // an investigation is what somebody reads afterwards, and a process per command would have cut it up.
    val session = sessions().single()
    assertThat(session.sessionId).isEqualTo(SESSION_NAME)
    assertThat(session.toolCalls.map { it.tool }).containsExactly(LIST_HEAP_DUMPS, "list_leaks")
    // **One row per command typed**, which is what there is to record: a process connects, makes its one
    // call and ends, so nothing crosses this socket that isn't the call itself. Which connection each of them
    // was is in this run's log.
    assertThat(session.calls).hasSize(2)
  }

  @Test
  fun `the commands of another shell are another session`() {
    listen()

    cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.")
    cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.", sessionName = "cli99")

    // Two agents at one heap dump are two investigations to read, exactly as two connections are.
    assertThat(sessions().map { it.sessionId }).containsExactlyInAnyOrder(SESSION_NAME, "cli99")
  }

  @Test
  fun `a list argument is spelled with commas`() {
    listen()

    val exitCode = cli(
      "find_objects",
      "heapDumpKey=${window.heapDumpName}",
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
  fun `a command with no run to talk to says so rather than making something up`() {
    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.")

    // And says it of *this build*, which is the filter the rest of these tests are about: a run of another
    // build is a run whose commands are not these commands, so it is no more use here than none at all.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(printed()).isEmpty()
    assertThat(said()).contains("No Shark Dive run of this build ($BUILD_SHA) is open")
      .contains(OPEN_HEAP_DUMP)
  }

  @Test
  fun `a command that opens a heap dump starts a run when there is none, and then talks to it`() {
    // Which is the one command that does: every other one is a question about a run, and a run this just
    // started has nothing open to answer it with. Publishing the run inline is what the app does a JVM later,
    // so the loop finds it on its next poll rather than timing out on a run nothing started.
    val started = mutableListOf<Boolean>()

    val exitCode = cli(
      OPEN_HEAP_DUMP,
      "path=${window.heapDumpPath}",
      "reason=Starting on the dump I was given.",
      openARun = { noWindow ->
        started += noWindow
        listen()
      }
    )

    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    assertThat(started).containsExactly(false)
    assertThat(said()).contains("one is being started to investigate in")
    assertThat(printed()).contains(window.heapDumpName)
  }

  @Test
  fun `a command that is not opening a heap dump starts nothing, and says which one would`() {
    val started = mutableListOf<Boolean>()

    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.", openARun = { started += it })

    // "Nothing is open" from a command that never talked to anything reads exactly like "a run is open and has
    // nothing in it", so the one that could only answer the first is refused with the next step as its
    // message. Which keeps the other half of it true: every JSON answer here came off the socket.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(started).isEmpty()
    assertThat(said()).contains("`shark-dive $CLI_OPTION $OPEN_HEAP_DUMP")
  }

  @Test
  fun `two runs of this build are a message naming them rather than a guess at which was meant`() {
    listen()
    val second = publishedByHand(shellRunning(listOf("/bin/sh", "-c", "sleep $SHELL_SECONDS; :")).pid())

    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.")

    // A command that picked one of them would be a command whose heap dump depends on what else is on the
    // machine, which is what the surface before this did — it said which it had picked and answered about
    // that one's dumps. So both are named, with the option that says which.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("2 Shark Dive runs are open")
      .contains(ProcessHandle.current().pid().toString())
      .contains(second)
      .contains(AgentCommandLine.RUN_OPTION)
  }

  @Test
  fun `a run of another build is not one of the runs a command can see`() {
    listen()
    publishedByHand(
      pid = shellRunning(listOf("/bin/sh", "-c", "sleep $SHELL_SECONDS; :")).pid(),
      buildSha = "0000000000000000000000000000000000000000"
    )

    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.")

    // Which is the state of this machine while the surface is being worked on: last branch's run is still up.
    // A command renamed since is a refusal from it reading "there is no command called that", so a run of
    // another build is filtered out before anything is sent — and two published runs are one to talk to.
    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    assertThat(printed()).contains(window.heapDumpName)
  }

  @Test
  fun `a run named by pid is the one talked to, and its build is what a mismatch is about`() {
    listen()
    val other = publishedByHand(
      pid = shellRunning(listOf("/bin/sh", "-c", "sleep $SHELL_SECONDS; :")).pid(),
      buildSha = "0000000000000000000000000000000000000000"
    )

    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.", pid = other)

    // Naming a run this command line cannot talk to has to say *why*, since the run is there and answering:
    // "no run is $pid" would send somebody looking for a window that is on their screen.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("was built from 0000000")
  }

  @Test
  fun `the kind of run to open a heap dump in has to be the kind of run there is`() {
    listen()

    val exitCode = cli(
      OPEN_HEAP_DUMP,
      "path=${window.heapDumpPath}",
      "reason=Opening it where nothing is drawn.",
      noWindow = true
    )

    // Whether anything is drawn is decided once, as a run starts — a run with no window cannot start Compose
    // at all — so there is no opening one dump of a run without a window. Refused rather than papered over,
    // because a command line that asked for no window on a machine that has a screen meant something by it.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("draws windows").contains(AgentCommandLine.NO_UI_OPTION)
  }

  @Test
  fun `no window is said about the commands that start a run and about nothing else`() {
    listen()

    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.", noWindow = true)

    // Refused locally, before a connect: the option says what kind of run to *start*, and every command that
    // starts none reads a dump that is open already — which a dump open with no window answers exactly as one
    // open in a window does. So a command line that passed it here has the wrong end of what it means.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("commands that start one").contains(OPEN_HEAP_DUMP)
  }

  @Test
  fun `a command this build does not have is a message about the ones it does`() {
    listen()

    val exitCode = cli("open_heap_dumps", "reason=Typing last month's name for it.")

    // Answered locally, which it can be *because* of the build filter above: the commands this build has are
    // the commands the run it would talk to has, so a typo costs a message rather than a connect, a session
    // row and a refusal from the far end.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("There is no command called \"open_heap_dumps\"").contains(LIST_HEAP_DUMPS)
    assertThat(sessions()).isEmpty()
  }

  @Test
  fun `closing the last heap dump open is answered, and says the run is ending`() {
    listen()

    val exitCode = cli("close_heap_dump", "heapDumpKey=${window.heapDumpName}", "reason=Done with this one.")

    // The answer is the only place a caller hears that there is nothing left to talk to, so it has to arrive
    // even though making it is what ends the run — see [AgentServer.letAnswersOut].
    assertThat(exitCode).isEqualTo(AgentCommandLine.ANSWERED)
    assertThat(printed()).contains("\"runEnded\": true")
  }

  @Test
  fun `a session name that could be a path is refused before anything is called`() {
    listen()

    val exitCode = cli(LIST_HEAP_DUMPS, "reason=Finding out what is open.", sessionName = "../../evil")

    // It becomes part of a file name, so the caller hears about it on the command it got wrong rather than
    // finding a session file somewhere else. The app end checks it too — see [AgentServerTest].
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("is no session name")
    assertThat(sessions()).isEmpty()
  }

  @Test
  fun `a word that is no argument is a message about arguments`() {
    listen()

    val exitCode = cli("describe_object", "0x7205")

    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said()).contains("An argument is `name=value`")
  }

  @Test
  fun `the help is every command of this build, one line each, and needs no run`() {
    val help = AgentCommandLine.commandsHelp(command = "shark-dive")

    // Generated from the registry, so a command added without a summary is a test failure rather than a
    // command an agent reading this never hears about. And nothing here reached a run: this is what somebody
    // types *before* there is one, so help that needed a window would fail where it is needed.
    agentTools(FakeAgentHeapDumps()).all.forEach { command ->
      assertThat(help).contains(command.name).contains(command.summary)
    }
    // The two ways in, since every other command needs the name one of them answers with.
    assertThat(help).contains("Start with $OPEN_HEAP_DUMP").contains(LIST_HEAP_DUMPS)
    // And `reason` is said once rather than under each of eighteen commands, which would be a sixth of the
    // help spent on the one argument every command takes.
    assertThat(help).contains("Every command takes `reason`")
    assertThat(help.lines().filter { it.trim().startsWith("reason (") }).isEmpty()
  }

  @Test
  fun `the help of one command is that command, and of no command says which there are`() {
    val one = AgentCommandLine.commandHelp(command = "shark-dive", commandName = "conclude")

    assertThat(one).contains("conclude").doesNotContain("list_leaks")
    // The one thing a schema doesn't say, because JSON has brackets and a command line hasn't. Here rather
    // than in the list, since a list argument is a thing one command takes and not a rule of the surface.
    val listed = AgentCommandLine.commandHelp(command = "shark-dive", commandName = "find_objects")
    assertThat(listed).contains("comma separated")

    val none = AgentCommandLine.commandHelp(command = "shark-dive", commandName = "chain_from_a_gc_root")

    assertThat(none).contains("There is no command").contains("chain_from_gc_root")
  }

  @Test
  fun `a command from a shell given one command joins whatever drove that shell`() {
    // How an agent's commands arrive: Claude Code runs each of them in a shell of its own, so the shell is one
    // command long and the session has to be the process above it — the one whose life is the conversation.
    // Which is this test JVM here, standing in for whatever drove the shell.
    val shell = shellRunning(listOf("/bin/sh", "-c", "sleep $SHELL_SECONDS; :"))

    val name = AgentCommandLine.sessionName(shell.theCommandItRan())

    assertThat(name).isEqualTo("cli${ProcessHandle.current().pid()}")
  }

  @Test
  fun `a command from a person's own shell joins that shell`() {
    // And the case that is not an agent: a shell with no command on its command line lives as long as the
    // terminal tab it is in, so it is what gathers the commands typed in it, and two tabs stay two sessions.
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
    // itself — the process every command here is made from.
    assertThat(AgentCommandLine.ranOneCommand("/opt/homebrew/bin/claude", listOf("-c"))).isFalse
    assertThat(AgentCommandLine.ranOneCommand("/usr/bin/java", listOf("-cp", "a.jar", "Main"))).isFalse
    // A process this one may not read, which is where the walk stops rather than guesses.
    assertThat(AgentCommandLine.ranOneCommand(null, listOf("-c", "x"))).isFalse
  }

  private fun listen(): Closeable = AgentServer.listen(
    heapDumps = FakeAgentHeapDumps(listOf(window), opens = { window }),
    serverVersion = "1.2.3",
    buildSha = BUILD_SHA,
    hasWindow = true,
    directory = directory
  ).also { closeables += it }

  /**
   * A second run of this build, written by hand, and the pid it is published under.
   *
   * [AgentServer.listen] names its file after the process it is called in, so two of them in one JVM overwrite
   * one file and are one run — which makes a hand-written file the only way to have two. The port in it is
   * never reached: two runs is refused before anything connects, and a run of another build is filtered out
   * before that.
   */
  private fun publishedByHand(
    pid: Long,
    buildSha: String = BUILD_SHA,
    hasWindow: Boolean = true
  ): String {
    File(directory, "$pid${AgentServer.RUN_SUFFIX}").writeText(
      "port=1\ntoken=nothing\nbuildSha=$buildSha\nwindow=$hasWindow\n"
    )
    return pid.toString()
  }

  /**
   * Runs one command and hands back its exit code, with stdout and stderr collected.
   *
   * Nothing waited for unless this command starts a run, which is the behaviour rather than a setting these
   * pass: a run listed in [directory] is connected to at once, and one that isn't listed is one nothing here
   * is about to publish. So no test spends a timeout, and the one that does start a run is handed an
   * [openARun] that publishes before it returns. See [AgentCommandLine.runToTalkTo].
   */
  private fun cli(
    vararg words: String,
    sessionName: String? = SESSION_NAME,
    pid: String? = null,
    noWindow: Boolean = false,
    openARun: ((noWindow: Boolean) -> Unit)? = null
  ): Int {
    val previousOut = System.out
    val previousErr = System.err
    System.setOut(PrintStream(printed, true, Charsets.UTF_8.name()))
    System.setErr(PrintStream(said, true, Charsets.UTF_8.name()))
    return try {
      AgentCommandLine.run(
        directory = directory,
        command = "shark-dive",
        words = words.toList(),
        buildSha = BUILD_SHA,
        pid = pid,
        noWindow = noWindow,
        sessionName = sessionName,
        openARun = openARun
      )
    } finally {
      System.setOut(previousOut)
      System.setErr(previousErr)
    }
  }

  /**
   * Starts a shell and leaves it running something, which is the shape these tests are about: a command is
   * made by a process a shell started, so what it joins is a walk up from one. And a live pid, which is what
   * a run published by hand needs to be a run at all.
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

    /** What one shell's commands are gathered under, which is `cli<pid>` for a real one. */
    const val SESSION_NAME = "cli1234"

    /** Which build these commands are, and therefore the only runs they can see. */
    const val BUILD_SHA = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b"

    const val CLI_OPTION = AgentCommandLine.CLI_OPTION

    /** Long enough that the shells these tests start outlast them, since they are killed rather than waited on. */
    const val SHELL_SECONDS = 30

    const val FORK_WAIT_MILLIS = 5_000
    const val FORK_POLL_MILLIS = 20L
  }
}
