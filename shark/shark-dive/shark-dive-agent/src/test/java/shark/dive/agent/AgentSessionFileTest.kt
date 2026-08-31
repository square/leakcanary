package shark.dive.agent

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
import shark.dive.Place

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
  fun `what a call sent and what it read back are kept as they were`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(call(tool = "describe_object", input = SENT, output = ANSWERED))

    // Character for character, newlines and all, because the point of keeping these is that they are not a
    // reading of what happened: an argument spelled wrong reads right in every derived field there is.
    val call = AgentSessionFile.sessionsIn(directory).single().calls.single()
    assertThat(call.input).isEqualTo(SENT)
    assertThat(call.output).isEqualTo(ANSWERED)
  }

  @Test
  fun `a session recorded before the exchange was kept reads back without one`() {
    directory.mkdirs()
    File(directory, "agent-2026-08-25_18-19-48_035-older.jsonl").writeText(
      """{"agentSession":"older","startedAt":"$STARTED_AT","sharkDive":"1.0.0"}""" + "\n" +
        """{"at":"$STARTED_AT","tool":"list_leaks","reason":"What the dump says.",""" +
        """"window":"$WINDOW_ID","heapDump":"/dumps/leak.hprof","millis":3}""" + "\n"
    )

    // Null rather than empty, which is what the *Agent logs* screen says out loud: a fold that opens onto
    // nothing reads as an app that lost the answer rather than one that was never given it.
    val call = AgentSessionFile.sessionsIn(directory).single().calls.single()
    assertThat(call.input).isNull()
    assertThat(call.output).isNull()
    assertThat(call.reason).isEqualTo("What the dump says.")
  }

  @Test
  fun `a message that reached no tool is a line like any other`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(message(method = "tools/list", input = """{"method":"tools/list"}""", output = """{"tools":[]}"""))
    file.called(message(method = null, input = "this is not JSON", output = "no", error = "That is not JSON"))
    file.called(call(tool = "list_leaks"))

    // All three, since the full traffic is what a session is: the calls are the subset that got as far as a
    // tool, and everything else is how it got there or why it didn't. See [AgentSession.toolCalls].
    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.calls).hasSize(3)
    assertThat(session.toolCalls.map { it.tool }).containsExactly("list_leaks")
    assertThat(session.errorCount).isEqualTo(1)
    val unreadable = session.calls[1]
    assertThat(unreadable.method).isNull()
    assertThat(unreadable.input).isEqualTo("this is not JSON")
    assertThat(unreadable.error).isEqualTo("That is not JSON")
    // Which is what the row of it says, since there is no tool to name it after and no place to lead to.
    assertThat(unreadable.verb).isEqualTo("Sent something this app could not read")
    assertThat(unreadable.place).isNull()
  }

  @Test
  fun `which way in a message came is read back, and a session says which ways it was talked to`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(call(tool = "list_leaks", over = AgentTransport.MCP))
    file.called(call(tool = "describe_object", over = AgentTransport.CLI))

    // An MCP client's call and a call somebody typed at the window are the same protocol on the same socket
    // by the time anything answers them, so the door is the only place that knows and this is where it says.
    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.calls.map { it.over })
      .containsExactly(AgentTransport.MCP, AgentTransport.CLI)
    assertThat(session.transports).containsExactly(AgentTransport.MCP, AgentTransport.CLI)
  }

  @Test
  fun `a session recorded before the way in was kept says nothing rather than guessing one`() {
    directory.mkdirs()
    File(directory, "agent-2026-08-25_18-19-48_035-older.jsonl").writeText(
      """{"agentSession":"older","startedAt":"$STARTED_AT","sharkDive":"1.0.0"}""" + "\n" +
        """{"at":"$STARTED_AT","tool":"list_leaks","reason":"What the dump says.","millis":3}""" + "\n" +
        """{"at":"$STARTED_AT","over":"carrier pigeon","tool":"list_leaks","millis":3}""" + "\n"
    )

    // Null, not MCP: a command line's calls are exactly the ones a guess would label wrongly. And a way in
    // this build has never heard of reads the same, with a line in the log saying which.
    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.calls.map { it.over }).containsExactly(null, null)
    assertThat(session.transports).isEmpty()
    assertThat(log).anyMatch { it.contains("carrier pigeon") }
    // And a tool call from a build that recorded no method still reads as the one method it can have been.
    assertThat(session.calls.map { it.method }).containsOnly("tools/call")
    assertThat(session.toolCalls).hasSize(2)
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
  fun `a call that named nothing goes where the tool means, whether or not the file says so`() {
    val tools = agentTools(FakeAgentHeapDumps())

    // Both sides read [AgentScreen], so a screen cannot end up reachable and unnamed or named and
    // unreachable: what a call with no arguments is about is the place, and the words are what the row of
    // the *Agent logs* screen draws as the link to it.
    tools.all.map { it.name }.forEach { name ->
      assertThat(tools.target(name, buildJsonObject { }).place)
        .describedAs(name)
        .isEqualTo(screenOfTool(name, emptyMap())?.place)
    }
  }

  @Test
  fun `a session written before a call like that had a link still leads where it went`() {
    directory.mkdirs()
    // Which is every session on this machine, since the place of these was worked out from the arguments
    // and they have none: a row of one that leads nowhere is the bug this fixed, kept fixed for the
    // sessions that were already on disk.
    File(directory, "agent-2026-08-25_18-19-48_035-older.jsonl").writeText(
      """{"agentSession":"older","startedAt":"$STARTED_AT","sharkDive":"1.0.0"}""" + "\n" +
        """{"at":"$STARTED_AT","tool":"dominator_tree","reason":"Where the memory went.",""" +
        """"window":"$WINDOW_ID","heapDump":"/dumps/leak.hprof","millis":3}""" + "\n"
    )

    val call = AgentSessionFile.sessionsIn(directory).single().calls.single()
    assertThat(call.place).isEqualTo(Place.wholeHeapDump())
    assertThat(call.screen).isEqualTo("dominator tree")
  }

  @Test
  fun `which heap dumps were open is read off the answer, and nothing else is`() {
    val answered = buildJsonObject {
      putJsonArray("heapDumps") {
        addJsonObject {
          put("window", WINDOW_ID)
          put("heapDumpPath", "/dumps/leak.hprof")
        }
      }
    }

    assertThat(openHeapDumpsOfTool("open_heap_dumps", answered)).containsExactly("/dumps/leak.hprof")
    // Every other call is about a heap dump rather than about which ones there are, and a row of them
    // listing the dumps would be the window's own state printed against somebody's investigation.
    assertThat(openHeapDumpsOfTool("list_leaks", answered)).isEmpty()
  }

  @Test
  fun `the heap dumps a call was answered with are read back as somewhere to go`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(
      call(tool = "open_heap_dumps", openHeapDumps = listOf("/dumps/leak.hprof", "/dumps/other.hprof"))
    )

    // In the order they were open in, because that is the order the window unfolds them in — and paths,
    // since the window ids beside them belonged to a run that has ended by the time this is read.
    assertThat(AgentSessionFile.sessionsIn(directory).single().calls.single().openHeapDumps)
      .containsExactly("/dumps/leak.hprof", "/dumps/other.hprof")
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
    input: String? = null,
    output: String? = null,
    refusal: String? = null,
    error: String? = null,
    outcome: String? = null,
    over: AgentTransport = AgentTransport.MCP,
    openHeapDumps: List<String> = emptyList()
  ) = AgentSessionCall(
    at = STARTED_AT,
    over = over,
    method = "tools/call",
    tool = tool,
    reason = reason,
    windowId = WINDOW_ID,
    heapDumpPath = "/dumps/leak.hprof",
    place = place,
    arguments = arguments,
    input = input,
    output = output,
    refusal = refusal,
    error = error,
    outcome = outcome,
    openHeapDumps = openHeapDumps,
    millis = 12L
  )

  /** A message that reached no tool, which is the rest of what a session holds. See [AgentSessionCall]. */
  private fun message(
    method: String?,
    input: String,
    output: String? = null,
    error: String? = null,
    over: AgentTransport = AgentTransport.MCP
  ) = AgentSessionCall(
    at = STARTED_AT,
    over = over,
    method = method,
    tool = null,
    reason = null,
    windowId = null,
    heapDumpPath = null,
    place = null,
    arguments = emptyMap(),
    input = input,
    output = output,
    refusal = null,
    error = error,
    outcome = null,
    millis = 3L
  )

  private companion object {
    const val SERVER_VERSION = "1.2.3"
    const val WINDOW_ID = "zvphq4r3"
    const val OBJECT_ID = 0x12d368b8L

    /**
     * An exchange as it crosses the wire: the tool's own name and then several lines of formatted JSON,
     * which one line of the session file escapes.
     */
    const val SENT =
      "describe_object {\n  \"object\": \"0x12d368b8\",\n  \"reason\": \"Reading the holder's fields.\"\n}"
    const val ANSWERED = "{\n  \"object\": \"0x12d368b8\",\n  \"className\": \"com.example.Holder\"\n}"

    val STARTED_AT: Instant = Instant.parse("2026-08-25T18:19:48.035Z")
  }
}
