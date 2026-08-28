package shark.explorer.app

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.agent.AgentCommandLine
import shark.explorer.agent.AgentStdioBridge

/**
 * Which command lines reach an agent rather than a window, answered before anything else in `main`.
 *
 * The cases here are the ones that end without talking to anybody, and they are the only ones a test can
 * drive: everything else either pipes stdio to a window, serves the tools until a client closes its end, or
 * calls a run of the app that whoever is running the tests may have open. `HeadlessAgentHeapDumpsTest` covers
 * what is served, and `AgentCommandLineTest` in `shark-explorer-agent` covers a call over a real socket.
 *
 * What is worth pinning here is the **split**: a command line carries both a call and the window that may
 * have to be opened to answer it, and mistaking one for the other means an argument opened as a heap dump.
 */
class AgentOptionsTest {

  @Test
  fun `an ordinary command line is a window`() {
    assertThat(agentBridgeExitCode(arrayOf("--title=Windowed", "dump.hprof"))).isNull()
    // `--no-ui` on its own is not a way to run the app with no window: there would be nothing to run.
    assertThat(agentBridgeExitCode(arrayOf(NO_UI_OPTION))).isNull()
    assertThat(agentCommandExitCode(arrayOf("--title=Windowed", "dump.hprof"))).isNull()
  }

  @Test
  fun `a command line that does not read is a failure rather than a message`() {
    // A client that launched this has nowhere to show a usage message, so the exit code is what says so.
    assertThat(agentBridgeExitCode(arrayOf(MCP_STDIO_OPTION, NO_UI_OPTION, "--titel=Typo"))).isEqualTo(1)
  }

  @Test
  fun `what is left of a server's command line is a window's`() {
    val arguments = windowArguments(
      arrayOf(
        MCP_STDIO_OPTION,
        NO_UI_OPTION,
        "${AgentStdioBridge.PID_OPTION}12345",
        "--title=For an agent",
        "dump.hprof"
      )
    )

    // The heap dump and the title survive, and the three server options are not taken for heap dumps: a
    // window saying `--no-ui` could not be read is what that mistake looks like.
    assertThat(arguments.heapDumpFiles.map { it.name }).containsExactly("dump.hprof")
    assertThat(arguments.titlePrefix).isEqualTo("For an agent")
  }

  @Test
  fun `what is left of a call's command line is a window's too`() {
    val arguments = windowArguments(
      arrayOf(
        AgentCommandLine.AGENT_OPTION,
        "describe_object",
        "object=0x7205",
        "reason=Reading the holder's fields.",
        "${AgentCommandLine.SESSION_OPTION}cli99",
        "--title=For an agent",
        "dump.hprof"
      ),
      toolName = "describe_object"
    )

    // The tool and its arguments are the call, and what remains is the window this would open to answer it —
    // which is the same window a command line with no call in it would have opened.
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
   * Runs [block] with stdout and stderr taken over, since these two paths write to both.
   *
   * A test that let them through would put the help of sixteen tools in the middle of the test report, and
   * the messages beside it read as failures of whatever ran next.
   */
  private fun onItsOwnStreams(
    printed: ByteArrayOutputStream = ByteArrayOutputStream(),
    block: () -> Int?
  ): Int? {
    val previousOut = System.out
    val previousErr = System.err
    System.setOut(PrintStream(printed, true, Charsets.UTF_8.name()))
    System.setErr(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8.name()))
    return try {
      block()
    } finally {
      System.setOut(previousOut)
      System.setErr(previousErr)
    }
  }
}
