package shark.explorer.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.HeapObjectKind
import shark.explorer.LeakStatus
import shark.explorer.ObjectListFilter
import shark.explorer.Place
import shark.explorer.exactHexObjectId

/**
 * What an agent gets back from each tool, and what it gets refused for.
 *
 * The story these run through is the one the whole surface exists for: a heap dump that says a destroyed
 * activity shouldn't be there, a chain that will not name a single reference while the object above it has no
 * verdict, a refusal to conclude that says which step is unexplained, and then — a verdict later — the same
 * chain naming `Holder.activity` and a conclusion the software agreed to.
 */
class AgentToolsTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private lateinit var heapDump: InvestigationHeapDump
  private lateinit var window: FakeAgentHeapDump
  private lateinit var tools: AgentTools

  @Before
  fun setUp() {
    heapDump = temporaryFolder.applicationHoldsActivityThroughHolder()
    window = FakeAgentHeapDump(heapDump.explorer)
    tools = AgentTools { listOf(window) }
  }

  @After
  fun tearDown() {
    heapDump.close()
  }

  @Test
  fun `open heap dumps hands over the method with the dump`() {
    val answer = call(OPEN_HEAP_DUMPS)

    assertThat(answer.text("method")).isEqualTo(AgentMethod.INSTRUCTIONS)
    val dumps = answer.array("heapDumps")
    assertThat(dumps).hasSize(1)
    assertThat(dumps.first().jsonObject.text("window")).isEqualTo(window.windowId)
    assertThat(dumps.first().jsonObject.text("heapDumpPath"))
      .isEqualTo(heapDump.explorer.heapDumpFile.absolutePath)
  }

  @Test
  fun `sizes come back with what a retained size is a share of`() {
    val sizes = call(OPEN_HEAP_DUMPS).array("heapDumps").first().jsonObject.obj("sizes")

    assertThat(sizes.text("totalBytes").toLong()).isGreaterThan(0)
    assertThat(sizes.text("stronglyReachableBytes").toLong()).isGreaterThan(0)
    assertThat(sizes.array("byStrength")).isNotEmpty
  }

  @Test
  fun `a run with no heap dump open says so rather than answering`() {
    tools = AgentTools { emptyList() }

    assertThat(call(OPEN_HEAP_DUMPS).text("problem")).contains("No heap dump is open")
    assertThatThrownBy { call("list_leaks") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining(OPEN_HEAP_DUMPS)
  }

  @Test
  fun `two heap dumps open have to be named`() {
    val other = FakeAgentHeapDump(heapDump.explorer, windowId = "otherwindow")
    tools = AgentTools { listOf(window, other) }

    assertThatThrownBy { call("list_leaks") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("2 heap dumps are open")
      .hasMessageContaining(window.windowId)
      .hasMessageContaining(other.windowId)

    assertThat(call("list_leaks", "window" to other.windowId).text("objectCount")).isNotEmpty()
  }

  @Test
  fun `a window that is not open is refused by name`() {
    assertThatThrownBy { call("list_leaks", "window" to "closedwindow") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("No window is called \"closedwindow\"")
      .hasMessageContaining(window.windowId)
  }

  @Test
  fun `every call needs a reason`() {
    assertThatThrownBy { callWith("list_leaks", buildJsonObject { }) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("list_leaks needs `reason`")
  }

  @Test
  fun `the leaks are what the heap dump says shouldn't be there`() {
    val leaks = call("list_leaks")

    val objects = leaks.array("sections").flatMap { section ->
      section.jsonObject.array("groups").flatMap { it.jsonObject.array("objects") }
    }
    assertThat(objects.map { it.jsonObject.text("object") })
      .contains(exactHexObjectId(heapDump.activityObjectId))
    assertThat(objects.map { it.jsonObject.text("className") }).contains(ACTIVITY_CLASS_NAME)
  }

  @Test
  fun `describing an object reads its fields with the address of each value`() {
    val holder = call("describe_object", OBJECT to hex(heapDump.holderObjectId))

    assertThat(holder.text("className")).isEqualTo(HOLDER_CLASS_NAME)
    assertThat(holder.text("verdict")).isEqualTo(LeakStatus.UNKNOWN.name)
    val activityField = holder.array("fields")
      .single { it.jsonObject.text("name") == ACTIVITY_FIELD_NAME }
      .jsonObject
    assertThat(activityField.text("valueObject")).isEqualTo(hex(heapDump.activityObjectId))
  }

  @Test
  fun `an object the inspectors know about comes back with their verdict and their words`() {
    val activity = call("describe_object", OBJECT to hex(heapDump.activityObjectId))

    assertThat(activity.text("verdict")).isEqualTo(LeakStatus.STUCK.name)
    assertThat(activity.text("verdictReason")).contains("mDestroyed")
  }

  @Test
  fun `the chain names no reference while a step in it has no verdict`() {
    val answer = call("chain_from_gc_root", OBJECT to hex(heapDump.activityObjectId))

    val steps = answer.obj("chain").array("steps").map { it.jsonObject }
    assertThat(steps.map { it.text("object") }).containsExactly(
      hex(heapDump.applicationObjectId),
      hex(heapDump.holderObjectId),
      hex(heapDump.activityObjectId)
    )
    assertThat(steps.mapNotNull { it["reference"]?.jsonObject?.text("isFaulty") })
      .containsOnly("false")
    // Which is the field an agent reads to know whether it is done, so an unsolved chain has to leave it
    // out rather than answer with something that could be mistaken for a name.
    assertThat(answer.obj("chain")["faultyReference"]).isEqualTo(JsonNull)
    assertThat(answer.text("whatTheChainSays"))
      .contains("1 step(s)")
      .contains(hex(heapDump.holderObjectId))
      .contains(HOLDER_CLASS_NAME)
  }

  @Test
  fun `a chain with nothing stuck on it says that is why it names nothing`() {
    val answer = call("chain_from_gc_root", OBJECT to hex(heapDump.applicationObjectId))

    assertThat(answer.text("whatTheChainSays")).contains("Nothing on this chain")
  }

  @Test
  fun `concluding is refused while a step of the chain has no verdict`() {
    assertThatThrownBy {
      call(
        CONCLUDE,
        OBJECT to hex(heapDump.activityObjectId),
        "rootCause" to "The holder is a singleton that never lets go of the activity."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("Not concluded")
      .hasMessageContaining("1 step(s)")
      .hasMessageContaining(HOLDER_CLASS_NAME)
      .hasMessageContaining("describe_object")

    assertThat(window.notes).isEmpty()
  }

  @Test
  fun `a verdict narrows the chain to the stuck object to one reference`() {
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to LeakStatus.EXPECTED.name,
      "chainTo" to hex(heapDump.activityObjectId),
      "reason" to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )

    assertThat(answer.text("set")).isEqualTo("true")
    assertThat(answer.text("verdictsFlipped")).isEqualTo("0")
    assertThat(answer.text("canConclude")).isEqualTo("true")
    assertThat(answer.text("whatTheChainSays")).contains("$FAULTY_REFERENCE is the faulty reference")
    val faulty = answer.obj("chain").array("steps")
      .single { it.jsonObject["reference"]?.jsonObject?.text("isFaulty") == "true" }
      .jsonObject
    assertThat(faulty.text("object")).isEqualTo(hex(heapDump.activityObjectId))
    // And named at the top of the chain in the same words the window's `Leak solved` section uses, so that
    // an agent quoting it to its human names what the human is looking at.
    assertThat(answer.obj("chain").text("faultyReference")).isEqualTo(FAULTY_REFERENCE)
  }

  @Test
  fun `a verdict set without naming the stuck object says to read that chain again`() {
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to LeakStatus.EXPECTED.name,
      "reason" to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )

    assertThat(answer.text("set")).isEqualTo("true")
    assertThat(answer.text("next")).contains("chain_from_gc_root").contains("chainTo")
    assertThat(answer["chain"]).isNull()
  }

  @Test
  fun `the reason of a call is the reason kept with the verdict`() {
    call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to LeakStatus.EXPECTED.name,
      "reason" to "Holder.INSTANCE is a static singleton."
    )

    val verdict = window.verdicts[heapDump.holderObjectId]
    assertThat(verdict?.status).isEqualTo(LeakStatus.EXPECTED)
    assertThat(verdict?.reason).isEqualTo("Holder.INSTANCE is a static singleton.")
  }

  @Test
  fun `concluding names the faulty reference and writes it where the window shows it`() {
    setHolderExpected()

    val answer = call(
      CONCLUDE,
      OBJECT to hex(heapDump.activityObjectId),
      "rootCause" to "Holder.activity is assigned in onCreate and nothing clears it in onDestroy.",
      "howToReproduce" to "Open the screen, rotate, press back.",
      "notChecked" to "Whether the second instance of the holder is reached the same way.",
      "reason" to "The chain names one reference and the code says why it is still set."
    )

    assertThat(answer.text("concluded")).isEqualTo("true")
    val faulty = answer.array("faultyReference").single().jsonObject
    assertThat(faulty.text("reference")).isEqualTo(FAULTY_REFERENCE)
    assertThat(faulty.text("field")).isEqualTo(ACTIVITY_FIELD_NAME)
    assertThat(faulty.text("heldObject")).isEqualTo(hex(heapDump.activityObjectId))
    assertThat(faulty.text("heldClassName")).isEqualTo(ACTIVITY_CLASS_NAME)
  }

  @Test
  fun `the conclusion is written into the notes of the object it explains`() {
    setHolderExpected()

    call(
      CONCLUDE,
      OBJECT to hex(heapDump.activityObjectId),
      "rootCause" to "Nothing clears Holder.activity in onDestroy.",
      "notChecked" to "Whether anything else holds the holder.",
      "reason" to "One reference, and the code says why it is still set."
    )

    val place = Place.Object(heapDump.activityObjectId)
    assertThat(window.notes[place]?.single())
      .contains("## Root cause")
      .contains("`$FAULTY_REFERENCE`")
      .contains("Nothing clears Holder.activity in onDestroy.")
      .contains("**Not checked:** Whether anything else holds the holder.")
      .contains("One reference, and the code says why it is still set.")
    assertThat(window.shown).contains(place)
  }

  @Test
  fun `a verdict that contradicts one already recorded is refused until it is told to flip it`() {
    setHolderExpected()

    assertThatThrownBy {
      call(
        SET_VERDICT,
        OBJECT to hex(heapDump.applicationObjectId),
        "verdict" to LeakStatus.STUCK.name,
        "reason" to "This isn't the real Application, it is a copy left over from a test."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("contradicts 1 verdict(s)")
      .hasMessageContaining(hex(heapDump.holderObjectId))
      // The verdict it disagrees with, in the words it was given, since that is what decides which of the
      // two is wrong. As a sentence and not as JSON: this refusal is drawn on the *Agent logs* screen, which
      // exists to not show the protocol. See AgentTools.asSentence.
      .hasMessageContaining("Holder.INSTANCE is a static singleton")
      .hasMessageContaining("Keeping yours makes it ${LeakStatus.STUCK}")
      .hasMessageNotContaining("{\"")
      .hasMessageContaining("solveConflicts")

    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.applicationObjectId),
      "verdict" to LeakStatus.STUCK.name,
      "solveConflicts" to "true",
      "reason" to "This isn't the real Application, it is a copy left over from a test."
    )

    assertThat(answer.text("verdictsFlipped")).isEqualTo("1")
    assertThat(window.verdicts[heapDump.holderObjectId]?.status).isEqualTo(LeakStatus.STUCK)
    assertThat(window.verdicts[heapDump.holderObjectId]?.reason)
      .contains("Holder.INSTANCE is a static singleton")
  }

  @Test
  fun `unknown is refused as a verdict, because it is what no verdict already is`() {
    assertThatThrownBy {
      call(
        SET_VERDICT,
        OBJECT to hex(heapDump.holderObjectId),
        "verdict" to LeakStatus.UNKNOWN.name,
        "reason" to "I could not work out what this is."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("clear_verdict")
  }

  @Test
  fun `clearing a verdict says what it was, and clearing nothing is refused`() {
    setHolderExpected()

    val answer = call("clear_verdict", OBJECT to hex(heapDump.holderObjectId))

    assertThat(answer.text("was")).isEqualTo(LeakStatus.EXPECTED.name)
    assertThat(answer.text("itsReason")).contains("static singleton")
    assertThat(window.verdicts.isEmpty).isTrue()

    assertThatThrownBy { call("clear_verdict", OBJECT to hex(heapDump.holderObjectId)) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("Nothing to clear")
  }

  @Test
  fun `every way an object is held comes back with whether that is all of them`() {
    val answer = call("ways_held", OBJECT to hex(heapDump.activityObjectId))

    assertThat(answer.text("pathCount")).isEqualTo("1")
    assertThat(answer.text("hasMore")).isEqualTo("false")

    val between = call(
      "ways_held",
      OBJECT to hex(heapDump.activityObjectId),
      "from" to hex(heapDump.applicationObjectId)
    )
    assertThat(between.text("pathCount")).isEqualTo("1")
  }

  @Test
  fun `finding objects counts every match rather than the rows it showed`() {
    val capped = call("find_objects", "className" to "com.example", "limit" to "1")

    assertThat(capped.array("objects")).hasSize(1)
    assertThat(capped.text("matchCount").toInt()).isGreaterThan(1)
    assertThat(capped.text("isComplete")).isEqualTo("false")
  }

  @Test
  fun `one class can be asked for the instances of it and nothing else`() {
    val answer = callWith(
      "find_objects",
      buildJsonObject {
        put("className", HOLDER_CLASS_NAME)
        put("exactMatch", true)
        put("kinds", jsonArrayOf(HeapObjectKind.INSTANCE.name))
        put("reason", "Checking whether the holder is the singleton it looks like.")
      }
    )

    assertThat(answer.text("matchCount")).isEqualTo("1")
    assertThat(answer.text("isComplete")).isEqualTo("true")
    assertThat(answer.array("objects").single().jsonObject.text("object"))
      .isEqualTo(hex(heapDump.holderObjectId))
  }

  @Test
  fun `an object kind that does not exist is refused by name`() {
    assertThatThrownBy {
      callWith(
        "find_objects",
        buildJsonObject {
          put("kinds", jsonArrayOf("BITMAPS"))
          put("reason", "Looking for the bitmaps.")
        }
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("\"BITMAPS\" is no object kind")
  }

  @Test
  fun `a note is appended to the place it is about`() {
    val answer = call(
      "take_note",
      "place" to hex(heapDump.holderObjectId),
      "text" to "Holder.INSTANCE is assigned in ExampleApplication.onCreate."
    )

    assertThat(answer.text("written")).isEqualTo("true")
    assertThat(window.notes[Place.Object(heapDump.holderObjectId)])
      .containsExactly("Holder.INSTANCE is assigned in ExampleApplication.onCreate.")
  }

  @Test
  fun `every kind of place can be shown, and nothing else can`() {
    call("show", "place" to PLACE_LEAKS)
    call("show", "place" to "objects")
    call("show", "place" to "objects:$HOLDER_CLASS_NAME")
    call("show", "place" to "starred")
    call("show", "place" to hex(heapDump.activityObjectId))

    assertThat(window.shown).containsExactly(
      Place.Leaks(),
      Place.Objects(),
      Place.Objects(ObjectListFilter(query = HOLDER_CLASS_NAME)),
      Place.Starred,
      Place.Object(heapDump.activityObjectId)
    )

    assertThatThrownBy { call("show", "place" to "the leak") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("is no place of a heap dump")
  }

  @Test
  fun `an address written as a decimal number is refused as one`() {
    assertThatThrownBy {
      call("describe_object", OBJECT to heapDump.activityObjectId.toString())
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("never a decimal number")
  }

  @Test
  fun `an address of no object of this heap dump is refused as one`() {
    assertThatThrownBy { call("describe_object", OBJECT to "0x1") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("is no object of this heap dump")
  }

  @Test
  fun `every read of the heap dump says what it was for`() {
    call("describe_object", OBJECT to hex(heapDump.activityObjectId))

    assertThat(window.reads).containsExactly("${hex(heapDump.activityObjectId)} for an agent")
  }

  /** What the whole investigation turns on: the object in between is one somebody read the code about. */
  private fun setHolderExpected() {
    call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to LeakStatus.EXPECTED.name,
      "reason" to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )
  }

  private fun call(
    name: String,
    vararg arguments: Pair<String, String>
  ): JsonObject = callWith(
    name,
    buildJsonObject {
      arguments.forEach { (key, value) -> put(key, value) }
      if (arguments.none { it.first == "reason" }) {
        put("reason", "Testing $name")
      }
    }
  )

  private fun callWith(
    name: String,
    arguments: JsonObject
  ): JsonObject = runBlocking {
    val tool = requireNotNull(tools.byName(name)) { "There is no tool called $name" }
    tool.call(arguments)
  }

  private fun hex(objectId: Long) = exactHexObjectId(objectId)

  private companion object {

    const val OPEN_HEAP_DUMPS = "open_heap_dumps"
    const val SET_VERDICT = "set_verdict"
    const val CONCLUDE = "conclude"
    const val OBJECT = "object"
    const val PLACE_LEAKS = "leaks"

    /**
     * One value of a field of the answer, whatever it is, as text.
     *
     * As text because that is what a client of this protocol reads a JSON value as at the far end of a
     * socket, and because an assertion that has to say which of `jsonPrimitive`, `boolean` and `long` a
     * field is, is an assertion about kotlinx rather than about the answer.
     */
    fun JsonObject.text(name: String): String =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonPrimitive.content

    fun JsonObject.obj(name: String): JsonObject =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonObject

    fun JsonObject.array(name: String): JsonArray =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonArray

    fun jsonArrayOf(vararg values: String): JsonArray =
      buildJsonArray { values.forEach { add(it) } }
  }
}
