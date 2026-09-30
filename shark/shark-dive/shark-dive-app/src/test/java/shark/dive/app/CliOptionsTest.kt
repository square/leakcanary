package shark.dive.app

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.agent.AgentCommandLine

/**
 * Which command lines are a command rather than a window, answered before anything else in `main`.
 *
 * The cases here are the ones that end without talking to anybody, which is what a test can drive: a command
 * itself reaches a run of the app that whoever is running the tests may have open, and `AgentCommandLineTest`
 * in `shark-dive-agent` covers one over a real socket.
 *
 * What is worth pinning here is the **split**: a command line carries both a command and the run that may have
 * to be started to answer it, and mistaking one for the other means an option opened as a heap dump. And the
 * order the three readers are asked in, since [helpExitCode] answering first is what makes help a text this
 * build prints rather than a question for whatever is open.
 */
class CliOptionsTest {

  @Test
  fun `an ordinary command line is a window`() {
    val args = arrayOf("--title=Windowed", "dump.hprof")

    assertThat(helpExitCode(args)).isNull()
    assertThat(cliExitCode(args)).isNull()
    assertThat(headlessAgentExitCode(args)).isNull()
  }

  @Test
  fun `the help names every way in, whichever spelling asked for it`() {
    HELP_SPELLINGS.forEach { option ->
      val printed = ByteArrayOutputStream()

      val exitCode = onItsOwnStreams(printed) { helpExitCode(arrayOf(option)) }

      // Both spellings, because this is the one command an agent types at a program nothing has told it
      // about, and a program that answers `--help` and not `-h` reads as one with no help at all.
      assertThat(exitCode).describedAs(option).isZero
      // And every way in is in it, the window's half and the command surface's: what this replaced was
      // `Unknown option --help` and a usage line naming the window's options alone, which answers that there
      // is no command surface here. See [help].
      assertThat(printed.toString(Charsets.UTF_8.name())).describedAs(option)
        .contains("--title")
        .contains("shark://")
        .contains(AgentCommandLine.CLI_OPTION)
        .contains(AgentCommandLine.HELP_OPTION)
        .contains(AgentCommandLine.RUN_OPTION)
        .contains(AgentCommandLine.SESSION_OPTION)
        .contains(AgentCommandLine.NO_UI_OPTION)
        .contains(AgentCommandLine.LEAK_METHOD_OPTION)
        // And where to start, since somebody reading this has a heap dump and nothing open.
        .contains(OPEN_HEAP_DUMP)
    }
  }

  @Test
  fun `saying this is a command and naming none asks what the commands are`() {
    val printed = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(printed) { helpExitCode(arrayOf(AgentCommandLine.CLI_OPTION)) }

    // The same text `--help` prints, and reached before anything looks for a run: a command line that says it
    // is a command and then names none is asking what the commands are, and the answer is this build's own.
    assertThat(exitCode).isZero
    assertThat(printed.toString(Charsets.UTF_8.name())).contains(OPEN_HEAP_DUMP)
  }

  @Test
  fun `the help of one command is printed, and needs nothing open`() {
    val printed = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(printed) {
      helpExitCode(arrayOf(AgentCommandLine.HELP_OPTION, "conclude"))
    }

    assertThat(exitCode).isZero
    // The command asked about, and not the sixteen others: reading a surface a piece at a time is what naming
    // one is for.
    assertThat(printed.toString(Charsets.UTF_8.name())).contains("conclude").doesNotContain("list_leaks")
  }

  @Test
  fun `how to investigate a leak is printed by a command line of its own`() {
    val printed = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(printed) {
      helpExitCode(arrayOf(AgentCommandLine.LEAK_METHOD_OPTION))
    }

    // Which is what took it out of every `list_leaks` answer: a session reads it once, here, rather than
    // being handed it again with each leak it asks about. See [AgentMethod.LEAK].
    assertThat(exitCode).isZero
    assertThat(printed.toString(Charsets.UTF_8.name())).isEqualToIgnoringWhitespace(AgentCommandLine.leakMethod())
  }

  @Test
  fun `a command that also says no window is told where that word goes rather than having it ignored`() {
    val said = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(said = said) {
      cliExitCode(arrayOf(AgentCommandLine.CLI_OPTION, "list_leaks", AgentCommandLine.NO_UI_OPTION))
    }

    // Rather than stripped and quietly dropped, which is what it would otherwise be: which kind of run a heap
    // dump is opened in is decided when it is opened, and every command after that reads a dump that is open
    // already. So the message names the one command the word belongs to.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said.toString(Charsets.UTF_8.name()))
      .contains(AgentCommandLine.NO_UI_OPTION)
      .contains(OPEN_HEAP_DUMP)
  }

  @Test
  fun `no window is no part of what a run is opened with`() {
    val arguments = windowArguments(arrayOf(AgentCommandLine.NO_UI_OPTION, "--title=Over ssh", "dump.hprof"))

    // The one thing that has to hold for a run with no window to be a run of this app: what is left is an
    // ordinary command line. A heap dump called `--no-ui` is what getting this wrong looks like.
    assertThat(arguments.heapDumpFiles.map { it.name }).containsExactly("dump.hprof")
    assertThat(arguments.titlePrefix).isEqualTo("Over ssh")
  }

  @Test
  fun `a command line that does not read is a failure rather than a message`() {
    val exitCode = onItsOwnStreams {
      cliExitCode(arrayOf(AgentCommandLine.CLI_OPTION, "list_leaks", "--titel=Typo"))
    }

    // Whatever typed this reads an exit code and the sentence on stderr, so a window it did not ask for is
    // not an answer to a command line nobody can read.
    assertThat(exitCode).isEqualTo(UNREADABLE_COMMAND_LINE)
  }

  @Test
  fun `what is left of a command's command line is a run's`() {
    val arguments = windowArguments(
      arrayOf(
        AgentCommandLine.CLI_OPTION,
        "describe_object",
        "object=0x7205",
        "reason=Reading the holder's fields.",
        "${AgentCommandLine.SESSION_OPTION}cli99",
        "${AgentCommandLine.RUN_OPTION}12345",
        "--title=For an agent",
        "dump.hprof"
      ),
      commandName = "describe_object"
    )

    // The command, its arguments and the two options that say which run and which session are the command, and
    // what remains is the run this would start to answer it — which is the same run a command line with no
    // command in it would have opened. A window saying `--session=cli99` could not be read as a heap dump is
    // what getting this wrong looks like.
    assertThat(arguments.heapDumpFiles.map { it.name }).containsExactly("dump.hprof")
    assertThat(arguments.titlePrefix).isEqualTo("For an agent")
  }

  @Test
  fun `a command line with no command named says so rather than calling something`() {
    val said = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(said = said) {
      cliExitCode(arrayOf(AgentCommandLine.CLI_OPTION, "reason=Trying it"))
    }

    // An argument is no command name, which is the case the help above doesn't catch: this command line says
    // more than that it is a command, so the answer is which word is missing rather than the whole surface.
    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
    assertThat(said.toString(Charsets.UTF_8.name())).contains(AgentCommandLine.HELP_OPTION)
  }

  /**
   * Runs [block] with stdout and stderr taken over, since these paths write to both.
   *
   * A test that let them through would put the help of seventeen commands in the middle of the test report, and
   * the messages beside it read as failures of whatever ran next. Two streams rather than one because which
   * of them a line went to is half of what these paths promise: [printed] is an answer, [said] is everything
   * else. See `AgentCommandLine.printed`.
   */
  private fun onItsOwnStreams(
    printed: ByteArrayOutputStream = ByteArrayOutputStream(),
    said: ByteArrayOutputStream = ByteArrayOutputStream(),
    block: () -> Int?
  ): Int? {
    val previousOut = System.out
    val previousErr = System.err
    System.setOut(PrintStream(printed, true, Charsets.UTF_8.name()))
    System.setErr(PrintStream(said, true, Charsets.UTF_8.name()))
    return try {
      block()
    } finally {
      System.setOut(previousOut)
      System.setErr(previousErr)
    }
  }

  companion object {
    /** Spelled here rather than read off the app, so that dropping one of the two fails this test. */
    private val HELP_SPELLINGS = listOf("--help", "-h")

    /**
     * The command everything else needs first, spelled out because its declaration is internal to
     * `shark-dive-agent` — see `AgentTools.OPEN_HEAP_DUMP`, which is what the messages here are generated from.
     */
    private const val OPEN_HEAP_DUMP = "open_heap_dump"
  }
}
