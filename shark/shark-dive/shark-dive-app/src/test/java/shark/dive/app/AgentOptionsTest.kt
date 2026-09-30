package shark.dive.app

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.agent.AgentCommandLine

/**
 * Which command lines are a call rather than a window, answered before anything else in `main`.
 *
 * The cases here are the ones that end without talking to anybody, which is what a test can drive: a call
 * itself reaches a run of the app that whoever is running the tests may have open, and `AgentCommandLineTest`
 * in `shark-dive-agent` covers one over a real socket.
 *
 * What is worth pinning here is the **split**: a command line carries both a call and the window that may
 * have to be opened to answer it, and mistaking one for the other means an argument opened as a heap dump.
 */
class AgentOptionsTest {

  @Test
  fun `an ordinary command line is a window`() {
    val args = arrayOf("--title=Windowed", "dump.hprof")

    assertThat(agentCommandExitCode(args)).isNull()
    assertThat(headlessAgentExitCode(args)).isNull()
    assertThat(helpExitCode(args)).isNull()
  }

  @Test
  fun `the help names the agent half of the command line as well as the window's`() {
    HELP_SPELLINGS.forEach { option ->
      val printed = ByteArrayOutputStream()

      val exitCode = onItsOwnStreams(printed) { helpExitCode(arrayOf(option)) }

      // Both spellings, because this is the one command an agent types at a program nothing has told it
      // about, and a program that answers `--help` and not `-h` reads as one with no help at all.
      assertThat(exitCode).describedAs(option).isZero
      // And every way in is in it. What this replaced was `Unknown option --help` and a usage line naming
      // the window's options alone, which answers that there is no agent surface here.
      assertThat(printed.toString(Charsets.UTF_8.name())).describedAs(option)
        .contains("--title")
        .contains("shark://")
        .contains(AgentCommandLine.AGENT_OPTION)
        .contains(AgentCommandLine.HELP_OPTION)
        .contains(AgentCommandLine.PID_OPTION)
        .contains(AgentCommandLine.SESSION_OPTION)
        .contains(NO_UI_OPTION)
    }
  }

  @Test
  fun `a call that also says no window is told to start one rather than having it ignored`() {
    val said = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(said = said) {
      agentCommandExitCode(arrayOf(AgentCommandLine.AGENT_OPTION, "list_leaks", NO_UI_OPTION))
    }

    // Rather than stripped and quietly dropped, which is what it would otherwise be: a call reaches whatever
    // is already published, so there is no run for this word to make. And the message is the two commands,
    // since a machine with no screen is where somebody types this.
    assertThat(exitCode).isEqualTo(1)
    assertThat(said.toString(Charsets.UTF_8.name())).contains(NO_UI_OPTION)
  }

  @Test
  fun `no window is no part of what a window is opened with`() {
    val arguments = windowArguments(arrayOf(NO_UI_OPTION, "--title=Over ssh", "dump.hprof"))

    // The one thing that has to hold for a run with no window to be a run of this app: what is left is an
    // ordinary command line. A heap dump called `--no-ui` is what getting this wrong looks like.
    assertThat(arguments.heapDumpFiles.map { it.name }).containsExactly("dump.hprof")
    assertThat(arguments.titlePrefix).isEqualTo("Over ssh")
  }

  @Test
  fun `a command line that does not read is a failure rather than a message`() {
    val exitCode = onItsOwnStreams {
      agentCommandExitCode(arrayOf(AgentCommandLine.AGENT_OPTION, "list_leaks", "--titel=Typo"))
    }

    // Whatever typed this reads an exit code and the sentence on stderr, so a window it did not ask for is
    // not an answer to a command line nobody can read.
    assertThat(exitCode).isEqualTo(1)
  }

  @Test
  fun `what is left of a call's command line is a window's`() {
    val arguments = windowArguments(
      arrayOf(
        AgentCommandLine.AGENT_OPTION,
        "describe_object",
        "object=0x7205",
        "reason=Reading the holder's fields.",
        "${AgentCommandLine.SESSION_OPTION}cli99",
        "${AgentCommandLine.PID_OPTION}12345",
        "--title=For an agent",
        "dump.hprof"
      ),
      toolName = "describe_object"
    )

    // The tool, its arguments and the two options that say which run and which session are the call, and what
    // remains is the window this would open to answer it — which is the same window a command line with no
    // call in it would have opened. A window saying `--session=cli99` could not be read as a heap dump is
    // what getting this wrong looks like.
    assertThat(arguments.heapDumpFiles.map { it.name }).containsExactly("dump.hprof")
    assertThat(arguments.titlePrefix).isEqualTo("For an agent")
  }

  @Test
  fun `a call with no tool named says so rather than calling something`() {
    val exitCode = onItsOwnStreams { agentCommandExitCode(arrayOf(AgentCommandLine.AGENT_OPTION)) }

    assertThat(exitCode).isEqualTo(AgentCommandLine.NOTHING_ANSWERED)
  }

  @Test
  fun `the help is printed, and needs nothing open`() {
    val printed = ByteArrayOutputStream()

    val exitCode = onItsOwnStreams(printed) {
      agentCommandExitCode(arrayOf(AgentCommandLine.HELP_OPTION, "conclude"))
    }

    assertThat(exitCode).isZero
    // The tool asked about, and not the fifteen others: reading a surface a piece at a time is what naming
    // one is for.
    assertThat(printed.toString(Charsets.UTF_8.name())).contains("conclude").doesNotContain("list_leaks")
  }

  /**
   * Runs [block] with stdout and stderr taken over, since these paths write to both.
   *
   * A test that let them through would put the help of seventeen tools in the middle of the test report, and
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
  }
}
