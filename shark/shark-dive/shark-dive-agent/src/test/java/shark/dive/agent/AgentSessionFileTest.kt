package shark.dive.agent

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
    assertThat(session.serverVersion).isEqualTo(SERVER_VERSION)
    val call = session.calls.single()
    assertThat(call.tool).isEqualTo("describe_object")
    assertThat(call.reason).isEqualTo("Reading the holder's fields.")
    assertThat(call.place).isEqualTo(Place.Object(OBJECT_ID))
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
        """{"at":"$STARTED_AT","tool":"list_leak_groups","reason":"What the dump says.",""" +
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
  fun `a line that reached no tool is a line like any other`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(unreadable(input = "this is not JSON", failure = "That is not one JSON object."))
    file.called(call(tool = LIST_LEAK_GROUPS))

    // Both, since the full traffic is what a session is: the calls are the subset that got as far as a tool,
    // and everything else is why nothing did. See [AgentSession.toolCalls].
    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.calls).hasSize(2)
    assertThat(session.toolCalls.map { it.tool }).containsExactly(LIST_LEAK_GROUPS)
    assertThat(session.errorCount).isEqualTo(1)
    val line = session.calls.first()
    assertThat(line.tool).isNull()
    assertThat(line.input).isEqualTo("this is not JSON")
    assertThat(line.error).isEqualTo("That is not one JSON object.")
    // Which is what the row of it says, since there is no tool to name it after and no place to lead to.
    assertThat(line.verb).isEqualTo("Sent something this app could not read")
    assertThat(line.place).isNull()
  }

  @Test
  fun `a call that was refused says so, and still says what it was about`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(
      call(tool = SET_VERDICT, place = Place.Object(OBJECT_ID), refusal = "Not set: STUCK contradicts 3")
    )

    val call = AgentSessionFile.sessionsIn(directory).single().calls.single()
    assertThat(call.refusal).isEqualTo("Not set: STUCK contradicts 3")
    assertThat(call.place).isEqualTo(Place.Object(OBJECT_ID))
  }

  @Test
  fun `a call that solved a leak says which reference the heap dump named`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(call(tool = SET_VERDICT, place = Place.Object(OBJECT_ID), outcome = FAULTY_REFERENCE))

    // The one line a session is read for, and the one the eval scores against the answer key: a leak solved
    // whose reference wasn't written down is a run nobody can mark. See `notes/agent-eval.md`.
    assertThat(AgentSessionFile.sessionsIn(directory).single().calls.single().outcome)
      .isEqualTo(FAULTY_REFERENCE)
  }

  /**
   * What a session came to, lifted off the answer, which is the one thing here that isn't what the agent
   * typed.
   *
   * Both halves have to say it: `leakSolved` is the heap dump's reading of the verdicts recorded about the
   * objects on that path, and `faultyReference` is the reference it derived from them. So a run reaches an
   * outcome by setting verdicts that narrow the path, and there is no way for one to type a reference into
   * this field — which is what the `conclude` it replaced let a run do.
   */
  @Test
  fun `what a run came to is read off the answer, and only the two calls that solve a leak have one`() {
    val solved = solvedAnswer(FAULTY_REFERENCE)

    assertThat(outcomeOfTool(SET_VERDICT, solved)).isEqualTo(FAULTY_REFERENCE)
    // The same answer from the other call that can carry it: a path read after the last verdict is this dump
    // saying the same thing, so it is the same outcome rather than a second one.
    assertThat(outcomeOfTool(PATH_FROM_GC_ROOTS, solved)).isEqualTo(FAULTY_REFERENCE)
    // Every other tool answers with data rather than an outcome, and a row saying what a read came back with
    // would be the answer printed twice.
    assertThat(outcomeOfTool("describe_object", solved)).isNull()
  }

  @Test
  fun `a path that has not been narrowed to one reference is no outcome`() {
    // Which is most of them: a path read before the verdicts add up names no reference, and a row claiming
    // one would be a session reading as solved from its first call.
    val narrowed = buildJsonObject {
      putJsonObject("investigation") { put("leakSolved", false) }
    }

    assertThat(outcomeOfTool(PATH_FROM_GC_ROOTS, narrowed)).isNull()
    // And a build that changed one half of the answer without the other reads as null here rather than
    // recording an outcome from a path that has none.
    assertThat(outcomeOfTool(PATH_FROM_GC_ROOTS, buildJsonObject { put("leakSolved", true) })).isNull()
  }

  @Test
  fun `a session whose last line was cut off keeps the calls before it`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(call(tool = LIST_LEAK_GROUPS, place = Place.Leaks()))
    // Which is what a session whose app was killed mid-write looks like on disk.
    file.file.appendText("""{"at":"2026-08-25T18:19:48.0""")

    val session = AgentSessionFile.sessionsIn(directory).single()
    assertThat(session.calls.map { it.tool }).containsExactly(LIST_LEAK_GROUPS)
    assertThat(log).anyMatch { it.contains("is not one JSON object") }
  }

  @Test
  fun `newest first, whichever order the files were listed in`() {
    AgentSessionFile.starting(directory, SERVER_VERSION, startedAt = STARTED_AT, sessionId = "aaaaaaaa")
      .called(call(tool = LIST_LEAK_GROUPS))
    AgentSessionFile.starting(
      directory,
      SERVER_VERSION,
      startedAt = STARTED_AT.plusSeconds(60),
      sessionId = "bbbbbbbb"
    ).called(call(tool = LIST_LEAK_GROUPS))

    assertThat(AgentSessionFile.sessionsIn(directory).map { it.sessionId })
      .containsExactly("bbbbbbbb", "aaaaaaaa")
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
      ).called(call(tool = LIST_LEAK_GROUPS))
    }

    assertThat(AgentSessionFile.sessionsIn(directory).map { it.sessionId })
      .containsExactly("session3", "session2")
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
          put("heapDump", "leak.hprof")
          put("heapDumpPath", "/dumps/leak.hprof")
        }
      }
    }

    assertThat(openHeapDumpsOfTool(LIST_HEAP_DUMPS, answered)).containsExactly("/dumps/leak.hprof")
    // Every other call is about a heap dump rather than about which ones there are, and a row of them
    // listing the dumps would be the window's own state printed against somebody's investigation.
    assertThat(openHeapDumpsOfTool(LIST_LEAK_GROUPS, answered)).isEmpty()
  }

  @Test
  fun `the heap dumps a call was answered with are read back as somewhere to go`() {
    val file = AgentSessionFile.starting(directory, SERVER_VERSION)
    file.called(
      call(tool = LIST_HEAP_DUMPS, openHeapDumps = listOf("/dumps/leak.hprof", "/dumps/other.hprof"))
    )

    // In the order they were open in, because that is the order the window unfolds them in — and paths,
    // since a path is what opens a dump whose run has ended by the time this is read.
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
    openHeapDumps: List<String> = emptyList()
  ) = AgentSessionCall(
    at = STARTED_AT,
    tool = tool,
    reason = reason,
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

  /**
   * A line this app could make no call of, which is the rest of what a session holds. See [AgentConnection].
   *
   * The failure is in `output` as well as in `error`, and that is not the same string twice: one is this app's
   * reading of what happened and the other is the text the agent was handed.
   */
  private fun unreadable(
    input: String,
    failure: String
  ) = AgentSessionCall(
    at = STARTED_AT,
    tool = null,
    reason = null,
    heapDumpPath = null,
    place = null,
    arguments = emptyMap(),
    input = input,
    output = failure,
    refusal = null,
    error = failure,
    outcome = null,
    millis = 3L
  )

  private companion object {
    const val SERVER_VERSION = "1.2.3"

    // Spelled here rather than read off the registry, like [AgentToolsTest]'s: a test that took the names
    // from the code it is testing would pass a rename that every session already on disk was written under.
    const val LIST_LEAK_GROUPS = "list_leak_groups"
    const val PATH_FROM_GC_ROOTS = "path_from_gc_roots"
    const val SET_VERDICT = "set_verdict"

    /**
     * A field only the sessions already on this machine have, which this build neither writes nor reads.
     *
     * Which is why it is still in the two hand-written lines below: a session written by an older build has
     * `window` on every call, and the one thing that must not happen when a field goes is those files
     * becoming unreadable. See [AgentSessionCall.link].
     */
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

    /**
     * The answer of a call that solved a leak, as the two tools that can hand one back write it.
     *
     * Both halves, because that is what [outcomeOfTool] reads: the path with the reference the heap dump
     * derived, and the heap dump's own reading of the verdicts on it saying the search is over.
     */
    fun solvedAnswer(faultyReference: String) = buildJsonObject {
      putJsonObject("investigation") {
        put("leakSolved", true)
        putJsonObject("faultyReference") {
          put("reference", faultyReference)
          put("objectIndex", 1)
        }
      }
    }
  }
}
