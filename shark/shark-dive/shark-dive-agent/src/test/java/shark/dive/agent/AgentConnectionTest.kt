package shark.dive.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.DeepLink
import shark.dive.Place
import shark.dive.exactHexObjectId

/**
 * What comes back over the socket, and what goes into the session file, for one line in.
 *
 * The tools are tested against a heap dump in [AgentToolsTest]; what is left here is everything about being
 * talked to by a program that is not this one. Three of them are worth the file on their own: **a refusal
 * comes back as a refusal** rather than as this app falling over, **the method is handed over once per
 * session** across processes that share nothing but a file, and **every line is written down** including the
 * ones that reached no tool. See [AgentServerTest] for the socket under this.
 */
class AgentConnectionTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private lateinit var heapDump: InvestigationHeapDump
  private lateinit var window: FakeAgentHeapDump
  private lateinit var connection: AgentConnection
  private lateinit var sessionsDirectory: File

  @Before
  fun setUp() {
    heapDump = temporaryFolder.applicationHoldsActivityThroughHolder()
    window = FakeAgentHeapDump(heapDump.dive)
    sessionsDirectory = File(temporaryFolder.root, "sessions")
    connection = AgentConnection(
      tools = agentTools(FakeAgentHeapDumps(listOf(window))),
      sessionFile = AgentSessionFile.starting(sessionsDirectory, SERVER_VERSION)
    )
  }

  @After
  fun tearDown() {
    heapDump.close()
  }

  @Test
  fun `a call is answered with the tool's own JSON`() {
    val response = answer(describeHolder("Reading the holder's fields."))

    // Under one key and otherwise untouched: what a tool answers is Shark Dive's model, and an envelope that
    // reshaped it would be a second spelling of everything on this surface. See [AgentWire].
    val answer = requireNotNull(AgentWire.answerOf(response)) { "No answer in $response" }
    assertThat(answer.text("className")).isEqualTo(HOLDER_CLASS_NAME)
    assertThat(AgentWire.refusalOf(response)).isNull()
    assertThat(AgentWire.failureOf(response)).isNull()
  }

  @Test
  fun `a refusal is something the agent reads rather than a failure of this app`() {
    val response = answer(refusedVerdict())

    // The whole reason the wire has three answers rather than two: this is the surface working — a tool
    // sending an agent back to the heap dump with the next thing to do in the message — and a caller told it
    // was a failure would be one told this app fell over. See [AgentWire] and [AgentCommandLine.REFUSED].
    assertThat(AgentWire.refusalOf(response))
      .contains("solvingLeakOf")
      .contains(HOLDER_CLASS_NAME)
    assertThat(AgentWire.answerOf(response)).isNull()
    assertThat(AgentWire.failureOf(response)).isNull()
    // And nothing was written into the heap dump, a refusal being a call that did not happen.
    assertThat(window.notes).isEmpty()
  }

  @Test
  fun `a tool this build does not have is a failure naming the ones it has`() {
    val response = answer(call("solve_the_leak", """"reason":"Trying my luck.""""))

    // A failure rather than a refusal, which is the distinction the third key is for: no tool said no, this
    // build simply has no such tool — a typo, or a name from a newer build. So the list is the answer.
    assertThat(AgentWire.failureOf(response))
      .contains("solve_the_leak")
      .contains("describe_object")
    assertThat(AgentWire.refusalOf(response)).isNull()
  }

  @Test
  fun `a line this app can make no call of is answered rather than dropped`() {
    val notJson = answer("this is not JSON")
    val noTool = answer("""{"arguments":{"reason":"Forgot the name."}}""")

    // Answered, because the caller is waiting on a line: a connection that went quiet is the one failure an
    // agent cannot tell from this app having died, and it would wait for ever either way.
    assertThat(AgentWire.failureOf(notJson)).contains("not one JSON object")
    assertThat(AgentWire.failureOf(noTool)).contains(AgentWire.TOOL)
  }

  @Test
  fun `an answer is the answer, on the first call of a session as on the one after it`() {
    val first = answered(call("list_leak_groups", """"reason":"Starting with what the dump says.""""))
    val second = answered(describeHolder("The holder next."))

    // Both halves of the method used to be fields of an answer — how to work here prepended to whatever a
    // session asked first, and how to find a leak in `list_leak_groups`'s own answer, which is this call. Both are
    // text this build prints now, [AgentCommandLine.SURFACE_METHOD_OPTION] and
    // [AgentCommandLine.LEAK_METHOD_OPTION], so a field here would be a session paying per call for a text it
    // reads once. Which leaves nothing in an answer that the tool did not answer with.
    assertThat(first.keys).doesNotContain(METHOD)
    assertThat(second.keys).doesNotContain(METHOD)
    assertThat(first.text("objectCount")).isNotEmpty()
  }

  @Test
  fun `two processes naming one session write one session`() {
    val sessionId = "cli4821"
    // `--cli` is a process per call, so a session is not a connection: each typed command opens the socket,
    // joins the session by name and makes its one call, and the one after it is an [AgentConnection] that has
    // never answered anything. Which is why these are built one after the other, as the two processes are.
    answered(
      call("list_leak_groups", """"reason":"What it says.""""),
      on = joining(sessionId)
    )
    answered(
      describeHolder("The holder next."),
      on = joining(sessionId)
    )

    val session = sessions().single()
    assertThat(session.sessionId).isEqualTo(sessionId)
    assertThat(session.calls.map { it.tool }).containsExactly("list_leak_groups", "describe_object")
  }

  @Test
  fun `the reason an agent gave is logged before the reads it caused`() {
    answer(describeHolder("Checking whether the holder is the singleton it looks like."))

    val called = log.indexOfFirst { it.startsWith("An agent called describe_object") }
    assertThat(log[called])
      .contains("object=${hex(heapDump.holderObjectId)}")
      .contains("because: Checking whether the holder is the singleton it looks like.")
    assertThat(log.subList(called + 1, log.size)).contains("${hex(heapDump.holderObjectId)} for an agent")
  }

  @Test
  fun `every call is written down with its reason and somewhere to go`() {
    answer(describeHolder("Checking whether the holder is the singleton it looks like."))

    val session = sessions().single()
    assertThat(session.serverVersion).isEqualTo(SERVER_VERSION)
    val call = session.calls.single()
    assertThat(call.verb).isEqualTo("Looked at")
    assertThat(call.subject).isEqualTo(hex(heapDump.holderObjectId))
    assertThat(call.reason).isEqualTo("Checking whether the holder is the singleton it looks like.")
    assertThat(call.refusal).isNull()
    // Which is what makes the row clickable: the place, in the heap dump the call was made against — named
    // by the dump and nothing else, so that the link still opens it once the window it was made in has gone.
    // The session line records the dump's path as well, which the link doesn't have to: a row leads to a
    // file, and a link is looked up.
    assertThat(call.place).isEqualTo(Place.Object(heapDump.holderObjectId))
    assertThat(call.heapDumpPath).isEqualTo(window.heapDumpPath)
    val link = DeepLink.parse(call.link()!!)
    assertThat(link.heapDumpName).isEqualTo(window.heapDumpName)
    assertThat(link.heapDumpPath).isNull()
    assertThat(link.place).isEqualTo(Place.Object(heapDump.holderObjectId))
  }

  @Test
  fun `a call is written down as the text that was sent and the text that came back`() {
    val answer = answered(describeHolder("Reading the holder's fields."))

    // The same string the agent read, not the same JSON printed a second way: what a session is kept for is
    // following what an agent did, and an answer reformatted on its way to disk is one nobody can compare
    // against the caller's own transcript of it. See [AgentWire.pretty].
    val call = sessions().single().calls.single()
    assertThat(call.output).isEqualTo(AgentWire.pretty(answer))
    // And the call as the agent wrote it: the tool it named, then everything it sent, `reason` included —
    // the derived fields leave that out because they have one of their own, and this is the call rather than
    // a reading of it. The name because a set of arguments with nothing saying what they are arguments to is
    // the one form of this that can't be read on its own.
    assertThat(call.input)
      .startsWith("describe_object {")
      .contains(""""object": "${hex(heapDump.holderObjectId)}"""")
      .contains(""""reason": "Reading the holder's fields."""")
  }

  @Test
  fun `a refused call keeps what it sent, its answer being the refusal`() {
    val response = answer(refusedVerdict())

    // Both, and not one of them: `refused` is this app's reading — the method said no — and `output` is the
    // text the agent was handed. Writing only the reading was tried and is what "an answer that isn't logged"
    // looked like from the outside. See [AgentSessionCall.output].
    val call = sessions().single().calls.single()
    val refusal = AgentWire.refusalOf(response)
    assertThat(call.output).isEqualTo(refusal)
    assertThat(call.refusal).isEqualTo(refusal)
    assertThat(call.input)
      .startsWith("set_verdict {")
      .contains(""""reason": "The holder never lets go."""")
  }

  @Test
  fun `a refused call is written down with the refusal and what it was asking about`() {
    answer(refusedVerdict())

    val call = sessions().single().calls.single()
    assertThat(call.verb).isEqualTo("Recorded STUCK on")
    assertThat(call.refusal).contains("solvingLeakOf")
    assertThat(call.reason).isEqualTo("The holder never lets go.")
    // Refused, and still pointing at the object it was refused about: a refusal nobody can follow up on is
    // the half of a session that is worth reading afterwards.
    assertThat(call.place).isEqualTo(Place.Object(heapDump.activityObjectId))
  }

  @Test
  fun `a call that named no place is written down with somewhere to go all the same`() {
    answer(call("list_leak_groups", """"reason":"Starting with what the dump says.""""))

    // The leaks screen to go to, and the words for it, so that a row of the window reads as a sentence with
    // one link in it: "Listed the" and then *leaks*. See [AgentTools.target] and [screenOfTool].
    val call = sessions().single().calls.single()
    assertThat(call.verb).isEqualTo("Listed the")
    assertThat(call.screen).isEqualTo("leaks")
    assertThat(call.place).isEqualTo(Place.Leaks())
    assertThat(call.subject).isNull()
  }

  @Test
  fun `a call about the whole heap dump goes to the whole heap dump`() {
    answer(call("dominator_tree", """"reason":"Where has the memory gone.""""))
    answer(call("find_objects", """"reason":"What the biggest objects are.""""))

    // Both of these mean the whole heap dump when they name nothing in it, and both are something a person
    // does in this window — so both lead there, rather than being the two rows of a session that show a
    // reader what was looked at and then decline to show them the thing.
    val calls = sessions().single().calls
    assertThat(calls.map { it.verb }).containsExactly("Read the", "Listed the")
    assertThat(calls.map { it.screen }).containsExactly("dominator tree", "biggest objects")
    assertThat(calls.map { it.place }).containsExactly(Place.wholeHeapDump(), Place.Objects())
  }

  @Test
  fun `the call that asks which heap dumps are open is written down with the ones that were`() {
    answer(call(LIST_HEAP_DUMPS, """"reason":"Seeing what there is."""", heapDump = null))

    // Off the answer, like a solved leak and unlike everything else: this is the one call whose subject is
    // the app rather than a heap dump, and a row saying it asked without saying what it heard is a row that
    // withholds the answer it is a record of. See [openHeapDumpsOfTool].
    val call = sessions().single().calls.single()
    assertThat(call.openHeapDumps).containsExactly(window.heapDumpPath)
    assertThat(call.place).isNull()
  }

  @Test
  fun `a call that solved a leak is written down with the reference the heap dump named`() {
    answer(
      call(
        "set_verdict",
        """"object":"${hex(heapDump.holderObjectId)}","verdict":"EXPECTED",""" +
          """"solvingLeakOf":"${hex(heapDump.activityObjectId)}",""" +
          """"why":"Holder.INSTANCE is a static singleton.",""" +
          """"reason":"Ruling out the object above the activity.""""
      )
    )

    // The answer rather than the arguments, which is the only line of a session that isn't: what an agent
    // asked is what it typed, and what the heap dump derived from the verdicts is what it came to. Nothing
    // in that call names a reference — this one is the dump's. That is the line the *Agent logs* screen ends
    // a session with, and the one the eval marks against an answer key.
    assertThat(sessions().single().calls.single().outcome).isEqualTo(FAULTY_REFERENCE)
  }

  @Test
  fun `a call to a tool this build does not have is written down under the name it used`() {
    val response = answer(call("solve_the_leak", """"reason":"Trying my luck.""""))

    // Under the name, because a name nothing answers to is exactly what somebody is looking for when a call
    // went nowhere — a typo, or a tool from a build newer than this one.
    val call = sessions().single().calls.single()
    assertThat(call.tool).isEqualTo("solve_the_leak")
    // As it arrived, and not tidied into words: every tool of this build has a verb, so a name with none is a
    // name this build has no tool for, and the exact string is the whole of what a reader is after.
    assertThat(call.verb).isEqualTo("Called solve_the_leak")
    assertThat(call.reason).isEqualTo("Trying my luck.")
    assertThat(call.input).startsWith("solve_the_leak {")
    assertThat(call.output).isEqualTo(AgentWire.failureOf(response))
    assertThat(call.error).contains("solve_the_leak").contains("describe_object")
    // Not a refusal: the surface said no to nothing, this build simply has no such tool.
    assertThat(call.refusal).isNull()
  }

  @Test
  fun `a line that reached no tool keeps the line and the answer that went back`() {
    val notJson = answer("this is not JSON")
    val noTool = answer("""{"arguments":{"reason":"Forgot the name."}}""")

    // The line as it arrived, since there is nothing else to keep — no tool to name it after, no arguments to
    // read a subject out of and nowhere in a heap dump to lead. And the answer, which is the half that was
    // missing: an agent told "that is not JSON" and a session that recorded nothing is a session saying this
    // app went quiet. See [AgentSession.toolCalls].
    val session = sessions().single()
    val calls = session.calls
    assertThat(calls.map { it.input })
      .containsExactly("this is not JSON", """{"arguments":{"reason":"Forgot the name."}}""")
    assertThat(calls.map { it.output })
      .containsExactly(AgentWire.failureOf(notJson), AgentWire.failureOf(noTool))
    assertThat(calls.map { it.tool }).containsExactly(null, null)
    assertThat(calls.map { it.verb }).containsOnly("Sent something this app could not read")
    assertThat(calls.map { it.place }).containsOnly(null)
    assertThat(session.toolCalls).isEmpty()
    assertThat(session.errorCount).isEqualTo(2)
  }

  private fun sessions(): List<AgentSession> = AgentSessionFile.sessionsIn(sessionsDirectory)

  /** One typed `--cli` command's worth of connection, joining the session called [sessionId]. */
  private fun joining(sessionId: String) = AgentConnection(
    tools = agentTools(FakeAgentHeapDumps(listOf(window))),
    sessionFile = AgentSessionFile.continuing(sessionsDirectory, SERVER_VERSION, sessionId)
  )

  /**
   * One line in, and the object that went back.
   *
   * [on] because a `--cli` command line is a process per call and therefore an [AgentConnection] per call,
   * so anything that is once per *session* has to be tried across two of these against one session file.
   */
  private fun answer(
    line: String,
    on: AgentConnection = connection
  ): JsonObject = runBlocking {
    requireNotNull(AgentWire.decodeOrNull(on.answer(line))) { "Nothing readable came back for $line" }
  }

  /** And the answer inside it, for the calls that are meant to have one. */
  private fun answered(
    line: String,
    on: AgentConnection = connection
  ): JsonObject {
    val response = answer(line, on)
    return requireNotNull(AgentWire.answerOf(response)) { "No answer in $response" }
  }

  /**
   * One line in, as an agent types it: the tool, which heap dump it is about, and the rest.
   *
   * The dump is filled in because every tool that reads one requires it to be named — there is no call about
   * "the dump that is open" on this surface — and a test that spelled the same name at every call site would
   * be testing that it can spell it. [heapDump] is null for the calls that are about this app rather than
   * about a heap dump. See [AgentTools] and [`AgentToolsTest.call`].
   */
  private fun call(
    tool: String,
    arguments: String,
    heapDump: String? = window.heapDumpName
  ): String {
    val named = heapDump?.let { """"heapDumpKey":"$it",""" }.orEmpty()
    return """{"tool":"$tool","arguments":{$named$arguments}}"""
  }

  private fun describeHolder(reason: String) = call(
    "describe_object",
    """"object":"${hex(heapDump.holderObjectId)}","reason":"$reason""""
  )

  /**
   * The call the surface refuses: the leak being solved has to be an object this dump reads as stuck.
   *
   * Which is the refusal a connection is tested through rather than any other because it carries everything
   * one has to: a sentence naming the object it is about, the argument to fix, and a place to go. See
   * [AgentToolsTest].
   */
  private fun refusedVerdict() = call(
    "set_verdict",
    """"object":"${hex(heapDump.activityObjectId)}","verdict":"STUCK",""" +
      """"solvingLeakOf":"${hex(heapDump.holderObjectId)}",""" +
      """"why":"The activity is destroyed and still here.",""" +
      """"reason":"The holder never lets go.""""
  )

  private fun hex(objectId: Long) = exactHexObjectId(objectId)

  private companion object {

    const val SERVER_VERSION = "1.2.3"

    /**
     * The field the method used to travel in, which nothing writes now.
     *
     * Kept as a name here because what the test above pins is an absence: see [AgentMethod].
     */
    const val METHOD = "method"

    fun JsonObject.text(name: String): String =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonPrimitive.content
  }
}
