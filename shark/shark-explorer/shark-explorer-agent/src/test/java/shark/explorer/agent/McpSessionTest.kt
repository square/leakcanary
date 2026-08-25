package shark.explorer.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.Place
import shark.explorer.exactHexObjectId

/**
 * What a client of this server gets back, as JSON-RPC rather than as Kotlin.
 *
 * The tools are tested against a heap dump in [AgentToolsTest]; what is left here is everything about being
 * spoken to over a socket by a program that is not this one — the handshake, a notification that must not be
 * answered, a refusal arriving as something the model reads rather than as an error the client swallows, and
 * the line the session log gets for every call.
 */
class McpSessionTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private lateinit var heapDump: InvestigationHeapDump
  private lateinit var window: FakeAgentHeapDump
  private lateinit var session: McpSession
  private lateinit var sessionsDirectory: File

  @Before
  fun setUp() {
    heapDump = temporaryFolder.applicationHoldsActivityThroughHolder()
    window = FakeAgentHeapDump(heapDump.explorer)
    sessionsDirectory = File(temporaryFolder.root, "sessions")
    session = McpSession(
      tools = AgentTools(FakeAgentHeapDumps(listOf(window))),
      serverVersion = SERVER_VERSION,
      sessionFile = AgentSessionFile.starting(sessionsDirectory, SERVER_VERSION)
    )
  }

  @After
  fun tearDown() {
    heapDump.close()
  }

  @Test
  fun `the handshake echoes the version the client asked for and hands over the method`() {
    val result = answer(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01",""" +
        """"clientInfo":{"name":"a client from the future"},"capabilities":{}}}"""
    ).result()

    assertThat(result.text("protocolVersion")).isEqualTo("2099-01-01")
    assertThat(result.text("instructions")).isEqualTo(AgentMethod.INSTRUCTIONS)
    assertThat(result.obj("serverInfo").text("name")).isEqualTo("shark-explorer")
    assertThat(result.obj("serverInfo").text("version")).isEqualTo(SERVER_VERSION)
    assertThat(result.obj("capabilities")["tools"]).isNotNull
  }

  @Test
  fun `a client that named no version is answered with the one this was written against`() {
    val result = answer("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""").result()

    assertThat(result.text("protocolVersion")).isEqualTo("2025-06-18")
  }

  @Test
  fun `every tool is listed with a schema that asks for a reason`() {
    val tools = answer("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
      .result().array("tools").map { it.jsonObject }

    assertThat(tools.map { it.text("name") }).containsExactly(
      "open_heap_dumps",
      "list_leaks",
      "describe_object",
      "chain_from_gc_root",
      "ways_held",
      "find_objects",
      "dominator_tree",
      "set_verdict",
      "clear_verdict",
      "read_notes",
      "take_note",
      "show",
      "conclude",
      "open_heap_dump",
      "list_devices",
      "dump_heap"
    )
    tools.forEach { tool ->
      assertThat(tool.text("description")).isNotEmpty()
      val schema = tool.obj("inputSchema")
      assertThat(schema.obj("properties")["reason"]).describedAs(tool.text("name")).isNotNull
      assertThat(schema.array("required").map { it.jsonPrimitive.content })
        .describedAs(tool.text("name"))
        .contains("reason")
    }
  }

  @Test
  fun `a notification is not answered at all`() {
    assertThat(answerOrNull("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).isNull()
  }

  @Test
  fun `the id of a request comes back as it was sent, whatever it was`() {
    val answered = answer("""{"jsonrpc":"2.0","id":"a string id","method":"ping"}""")

    assertThat(answered.text("id")).isEqualTo("a string id")
    assertThat(answered.text("jsonrpc")).isEqualTo("2.0")
  }

  @Test
  fun `a method this server does not have says what it does have`() {
    val error = answer("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""").obj("error")

    assertThat(error.text("code")).isEqualTo("-32601")
    assertThat(error.text("message")).contains("resources/list").contains("tools")
  }

  @Test
  fun `something that is not a JSON-RPC message is answered rather than dropped`() {
    val notJson = answer("this is not JSON").obj("error")
    assertThat(notJson.text("code")).isEqualTo("-32700")

    val noMethod = answer("""{"jsonrpc":"2.0","id":4}""").obj("error")
    assertThat(noMethod.text("code")).isEqualTo("-32600")
  }

  @Test
  fun `a tool answers with the same JSON as text and as structured content`() {
    val result = callTool(
      """{"name":"describe_object","arguments":{"object":"${hex(heapDump.holderObjectId)}",""" +
        """"reason":"Reading the holder's fields."}}"""
    )

    val text = result.array("content").single().jsonObject
    assertThat(text.text("type")).isEqualTo("text")
    assertThat(JSON.parseToJsonElement(text.text("text")).jsonObject)
      .isEqualTo(result.obj("structuredContent"))
    assertThat(result.obj("structuredContent").text("className")).isEqualTo(HOLDER_CLASS_NAME)
    assertThat(result["isError"]).isNull()
  }

  @Test
  fun `a refusal is something the model reads rather than an error the client swallows`() {
    val result = callTool(
      """{"name":"conclude","arguments":{"object":"${hex(heapDump.activityObjectId)}",""" +
        """"rootCause":"The holder never lets go.","reason":"I know what this is."}}"""
    )

    assertThat(result.text("isError")).isEqualTo("true")
    assertThat(result.array("content").single().jsonObject.text("text"))
      .contains("Not concluded")
      .contains(HOLDER_CLASS_NAME)
    assertThat(window.notes).isEmpty()
  }

  @Test
  fun `a tool this server does not have is a refusal naming the ones it has`() {
    val result = callTool("""{"name":"solve_the_leak","arguments":{"reason":"Trying my luck."}}""")

    assertThat(result.text("isError")).isEqualTo("true")
    assertThat(result.array("content").single().jsonObject.text("text"))
      .contains("solve_the_leak")
      .contains("describe_object")
  }

  @Test
  fun `the reason an agent gave is logged before the reads it caused`() {
    callTool(
      """{"name":"describe_object","arguments":{"object":"${hex(heapDump.holderObjectId)}",""" +
        """"reason":"Checking whether the holder is the singleton it looks like."}}"""
    )

    val called = log.indexOfFirst { it.startsWith("An agent called describe_object") }
    assertThat(log[called])
      .contains("object=${hex(heapDump.holderObjectId)}")
      .contains("because: Checking whether the holder is the singleton it looks like.")
    assertThat(log.subList(called + 1, log.size)).contains("${hex(heapDump.holderObjectId)} for an agent")
  }

  @Test
  fun `every call is written down with its reason and somewhere to go`() {
    answer(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
        """"clientInfo":{"name":"claude-code","version":"9.9.9"},"capabilities":{}}}"""
    )
    callTool(
      """{"name":"describe_object","arguments":{"object":"${hex(heapDump.holderObjectId)}",""" +
        """"reason":"Checking whether the holder is the singleton it looks like."}}"""
    )

    val session = sessions().single()
    assertThat(session.client).isEqualTo("claude-code 9.9.9")
    assertThat(session.serverVersion).isEqualTo(SERVER_VERSION)
    val call = session.calls.single()
    assertThat(call.verb).isEqualTo("Described")
    assertThat(call.subject).isEqualTo(hex(heapDump.holderObjectId))
    assertThat(call.reason).isEqualTo("Checking whether the holder is the singleton it looks like.")
    assertThat(call.refusal).isNull()
    // Which is what makes the row clickable: the place, in the window the call was made against.
    assertThat(call.place).isEqualTo(Place.Object(heapDump.holderObjectId))
    assertThat(call.link()).isEqualTo("shark://${window.windowId}/object?id=${hex(heapDump.holderObjectId)}")
    assertThat(call.heapDumpPath).isEqualTo(window.heapDumpPath)
  }

  @Test
  fun `a refused call is written down with the refusal and what it was asking about`() {
    callTool(
      """{"name":"conclude","arguments":{"object":"${hex(heapDump.activityObjectId)}",""" +
        """"rootCause":"The holder never lets go.","reason":"I know what this is."}}"""
    )

    val call = sessions().single().calls.single()
    assertThat(call.verb).isEqualTo("Concluded about")
    assertThat(call.refusal).contains("Not concluded")
    assertThat(call.reason).isEqualTo("I know what this is.")
    // Refused, and still pointing at the object it was refused about: a refusal nobody can follow up on is
    // the half of a session that is worth reading afterwards.
    assertThat(call.place).isEqualTo(Place.Object(heapDump.activityObjectId))
  }

  private fun sessions(): List<AgentSession> = AgentSessionFile.sessionsIn(sessionsDirectory)

  private fun callTool(params: String): JsonObject =
    answer("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":$params}""").result()

  private fun answer(line: String): JsonObject = requireNotNull(answerOrNull(line)) {
    "Nothing was answered to $line"
  }

  private fun answerOrNull(line: String): JsonObject? = runBlocking {
    session.answer(line)?.let { JSON.parseToJsonElement(it).jsonObject }
  }

  private fun hex(objectId: Long) = exactHexObjectId(objectId)

  private companion object {

    const val SERVER_VERSION = "1.2.3"

    val JSON = Json

    fun JsonObject.result(): JsonObject = obj("result")

    fun JsonObject.text(name: String): String =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonPrimitive.content

    fun JsonObject.obj(name: String): JsonObject =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonObject

    fun JsonObject.array(name: String) =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonArray
  }
}
