package shark.explorer.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.agent.AgentStdioBridge

/**
 * Which command lines mean "be an MCP server", answered before anything else in `main`.
 *
 * The two cases here are the ones that end without talking to anybody, and they are the only ones a test can
 * drive: everything else about this either pipes stdio to a window or serves the tools until a client closes
 * its end. `HeadlessAgentHeapDumpsTest` covers what it serves.
 */
class AgentCommandLineTest {

  @Test
  fun `an ordinary command line is a window`() {
    assertThat(agentBridgeExitCode(arrayOf("--title=Windowed", "dump.hprof"))).isNull()
    // `--no-ui` on its own is not a way to run the app with no window: there would be nothing to run.
    assertThat(agentBridgeExitCode(arrayOf(NO_UI_OPTION))).isNull()
  }

  @Test
  fun `a command line that does not read is a failure rather than a message`() {
    // A client that launched this has nowhere to show a usage message, so the exit code is what says so.
    assertThat(agentBridgeExitCode(arrayOf(MCP_STDIO_OPTION, NO_UI_OPTION, "--titel=Typo"))).isEqualTo(1)
  }

  @Test
  fun `what is left of a server's command line is a window's`() {
    val arguments = agentServerArguments(
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
}
