package shark.explorer.agent

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.Place

/**
 * The file an agent's session is written to, read back.
 *
 * Both halves are tested here rather than only the writing, because this file has two readers that are never
 * in the same process as the writer: the window drawing the *Agent logs* screen, and the eval scoring a run.
 * A field that is written and never read back is a row of that screen that says nothing.
 */
class AgentSessionFileTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private val directory: File get() = File(temporaryFolder.root, "sessions")

  @Test
  fun `a session is read back as it was written`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION, startedAt = STARTED_AT)
    file.opened(client = "claude-code 9.9.9", protocolVersion = "2025-06-18")
    file.called(
      call(
        tool = "describe_object",
        reason = "Reading the holder's fields.",
        place = Place.Object(OBJECT_ID),
        arguments = mapOf("object" to "0x12d368b8")
      )
    )

    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.sessionId).isEqualTo(file.sessionId)
    assertThat(session.startedAt).isEqualTo(STARTED_AT)
    assertThat(session.client).isEqualTo("claude-code 9.9.9")
    assertThat(session.serverVersion).isEqualTo(SERVER_VERSION)
    val call = session.calls.single()
    assertThat(call.tool).isEqualTo("describe_object")
    assertThat(call.reason).isEqualTo("Reading the holder's fields.")
    assertThat(call.place).isEqualTo(Place.Object(OBJECT_ID))
    assertThat(call.windowId).isEqualTo(WINDOW_ID)
    assertThat(call.heapDumpPath).isEqualTo("/dumps/leak.hprof")
    assertThat(call.arguments).containsEntry("object", "0x12d368b8")
    assertThat(call.millis).isEqualTo(12L)
  }

  @Test
  fun `a call that was refused says so, and still says what it was about`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(call(tool = "conclude", place = Place.Object(OBJECT_ID), refusal = "Not concluded. 3 steps"))

    val call = AgentSessionFile.sessionsIn(directory).single().calls.single()
    assertThat(call.refusal).isEqualTo("Not concluded. 3 steps")
    assertThat(call.place).isEqualTo(Place.Object(OBJECT_ID))
  }

  @Test
  fun `a call that concluded says which reference it concluded on`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(call(tool = "conclude", place = Place.Object(OBJECT_ID), outcome = FAULTY_REFERENCE))

    // The one line a session is read for, and the one the eval scores against the answer key: a conclusion
    // whose reference wasn't written down is a run nobody can mark. See `notes/agent-eval.md`.
    assertThat(AgentSessionFile.sessionsIn(directory).single().calls.single().outcome)
      .isEqualTo(FAULTY_REFERENCE)
  }

  @Test
  fun `what a conclusion came to is read off the answer, and nothing else is`() {
    val concluded = buildJsonObject {
      put("concluded", true)
      putJsonArray("faultyReference") {
        addJsonObject { put("reference", FAULTY_REFERENCE) }
      }
    }

    assertThat(outcomeOfTool("conclude", concluded)).isEqualTo(FAULTY_REFERENCE)
    // Every other tool answers with data rather than a conclusion, and a row saying what a read came back
    // with would be the answer printed twice.
    assertThat(outcomeOfTool("describe_object", concluded)).isNull()
    // A build whose conclude answers something else is a build whose sessions can't be scored, and null is
    // how that shows up rather than as a crash mid-session.
    assertThat(outcomeOfTool("conclude", buildJsonObject { put("concluded", true) })).isNull()
  }

  @Test
  fun `a session whose last line was cut off keeps the calls before it`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.opened(client = "a client", protocolVersion = null)
    file.called(call(tool = "list_leaks", place = Place.Leaks()))
    // Which is what a session whose app was killed mid-write looks like on disk.
    file.file.appendText("""{"at":"2026-08-25T18:19:48.0""")

    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.calls.map { it.tool }).containsExactly("list_leaks")
    assertThat(log).anyMatch { it.contains("is not one JSON object") }
  }

  @Test
  fun `newest first, whichever order the files were listed in`() {
    AgentSessionFile.starting(directory, SERVER_VERSION, startedAt = STARTED_AT, sessionId = "aaaaaaaa")
      .opened(client = "the older agent", protocolVersion = null)
    AgentSessionFile.starting(
      directory,
      SERVER_VERSION,
      startedAt = STARTED_AT.plusSeconds(60),
      sessionId = "bbbbbbbb"
    ).opened(client = "the newer agent", protocolVersion = null)

    assertThat(AgentSessionFile.sessionsIn(directory).map { it.client })
      .containsExactly("the newer agent", "the older agent")
  }

  @Test
  fun `only the newest sessions are kept`() {
    repeat(4) { index ->
      AgentSessionFile.starting(
        directory,
        SERVER_VERSION,
        startedAt = STARTED_AT.plusSeconds(index.toLong()),
        sessionId = "session$index",
        keepSessionCount = 2
      ).opened(client = "agent $index", protocolVersion = null)
    }

    assertThat(AgentSessionFile.sessionsIn(directory).map { it.client })
      .containsExactly("agent 3", "agent 2")
  }

  @Test
  fun `every tool has a verb, so that no screen ends up showing the protocol`() {
    val withoutAVerb = agentTools(FakeAgentHeapDumps()).all
      .map { it.name }
      .filter { verbOfTool(it, emptyMap()) == null }

    assertThat(withoutAVerb).isEmpty()
  }

  @Test
  fun `a directory no agent has ever connected through is no sessions rather than a failure`() {
    assertThat(AgentSessionFile.sessionsIn(File(temporaryFolder.root, "never-used"))).isEmpty()
  }

  private fun call(
    tool: String,
    reason: String? = "Because.",
    place: Place? = null,
    arguments: Map<String, String> = emptyMap(),
    refusal: String? = null,
    outcome: String? = null
  ) = AgentSessionCall(
    at = STARTED_AT,
    tool = tool,
    reason = reason,
    windowId = WINDOW_ID,
    heapDumpPath = "/dumps/leak.hprof",
    place = place,
    arguments = arguments,
    refusal = refusal,
    outcome = outcome,
    millis = 12L
  )

  private companion object {
    const val SERVER_VERSION = "1.2.3"
    const val WINDOW_ID = "zvphq4r3"
    const val OBJECT_ID = 0x12d368b8L

    val STARTED_AT: Instant = Instant.parse("2026-08-25T18:19:48.035Z")
  }
}
