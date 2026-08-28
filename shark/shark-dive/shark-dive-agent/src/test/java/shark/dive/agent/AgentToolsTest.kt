package shark.dive.agent

import java.time.Instant
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
import shark.dive.AndroidDevice
import shark.dive.DeepLink
import shark.dive.DeviceProcess
import shark.dive.HeapObjectKind
import shark.dive.LeakStatus
import shark.dive.ObjectListFilter
import shark.dive.Place
import shark.dive.exactHexObjectId

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
    window = FakeAgentHeapDump(heapDump.dive)
    tools = agentTools(FakeAgentHeapDumps(listOf(window)))
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
      .isEqualTo(heapDump.dive.heapDumpFile.absolutePath)
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
    tools = agentTools(FakeAgentHeapDumps())

    assertThat(call(OPEN_HEAP_DUMPS).text("problem")).contains("No heap dump is open")
    assertThatThrownBy { call("list_leaks") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining(OPEN_HEAP_DUMPS)
  }

  @Test
  fun `a heap dump still being indexed is named rather than left to be guessed`() {
    tools = agentTools(FakeAgentHeapDumps(indexing = listOf("/eval/runs/4/heap-dump.hprof")))

    val answer = call(OPEN_HEAP_DUMPS)

    assertThat(answer.array("indexing").map { it.jsonPrimitive.content })
      .containsExactly("/eval/runs/4/heap-dump.hprof")
    assertThat(answer.text("problem"))
      .contains("/eval/runs/4/heap-dump.hprof")
      .contains(OPEN_HEAP_DUMP)
  }

  @Test
  fun `the agent log is what has already been tried on this heap dump`() {
    tools = agentTools(
      FakeAgentHeapDumps(listOf(window)),
      sessions = listOf(
        recordedSession("cli7", concluded = "Holder.activity"),
        // Another dump's investigation, which is another dump's addresses: not this window's to answer with.
        recordedSession("cli8", heapDumpPath = "/dumps/another.hprof")
      )
    )

    val answer = call(AGENT_LOG)

    val sessions = answer.array("sessions").map { it.jsonObject }
    assertThat(sessions.map { it.text("session") }).containsExactly("cli7")
    // The one field a reader is looking for, and the one neither this screen nor the eval can work out for
    // itself: what the investigation came to.
    assertThat(sessions.single().text("concluded")).isEqualTo("Holder.activity")
    assertThat(sessions.single().text("refused")).isEqualTo("1")
  }

  @Test
  fun `one session of the log is every call it made, with the reasons`() {
    tools = agentTools(
      FakeAgentHeapDumps(listOf(window)),
      sessions = listOf(recordedSession("cli7", concluded = "Holder.activity"))
    )

    val calls = call(AGENT_LOG, "session" to "cli7").array("calls").map { it.jsonObject }

    // The reasons are the point: a session read as a list of tool names is the protocol showing through.
    assertThat(calls.map { it.text("tool") }).containsExactly("list_leaks", "conclude")
    assertThat(calls.first().text("reason")).isEqualTo("Reading what the dump says about itself.")
    assertThat(calls.last().text("outcome")).isEqualTo("Holder.activity")
    assertThat(calls.first().text("refused")).contains("needs `reason`")
  }

  @Test
  fun `a session that read another heap dump is refused by name here`() {
    tools = agentTools(
      FakeAgentHeapDumps(listOf(window)),
      sessions = listOf(recordedSession("cli7"), recordedSession("cli8", "/dumps/another.hprof"))
    )

    assertThatThrownBy { call(AGENT_LOG, "session" to "cli8") }
      .isInstanceOf(AgentRefusal::class.java)
      // Named, and the ones that did read this dump listed: an address of another dump is another object, so
      // reading that session here would be a screen of rows meaning something else.
      .hasMessageContaining("No session called \"cli8\" read this heap dump")
      .hasMessageContaining("cli7")
  }

  @Test
  fun `a heap dump nothing has been done to says so`() {
    assertThat(call(AGENT_LOG).text("problem")).contains("Nothing has been done to this heap dump")
  }

  @Test
  fun `nothing is said to be indexing when nothing is`() {
    assertThat(call(OPEN_HEAP_DUMPS).jsonObject.keys).doesNotContain("indexing")
  }

  @Test
  fun `two heap dumps open have to be named`() {
    val other = FakeAgentHeapDump(heapDump.dive, windowId = "otherwindow")
    tools = agentTools(FakeAgentHeapDumps(listOf(window, other)))

    assertThatThrownBy { call("list_leaks") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("2 heap dumps are open")
      .hasMessageContaining(window.windowId)
      .hasMessageContaining(other.windowId)

    assertThat(call("list_leaks", HEAP_DUMP to other.windowId).text("objectCount")).isNotEmpty()
  }

  @Test
  fun `a heap dump is named by its file name`() {
    // Which is the whole point of naming it that way: the name is in every answer an agent has been given,
    // and it goes on meaning this dump after the window it was opened in has gone.
    assertThat(call("list_leaks", HEAP_DUMP to window.heapDumpName).text("objectCount")).isNotEmpty()
  }

  @Test
  fun `two windows on one heap dump have to be named by window id`() {
    val other = FakeAgentHeapDump(heapDump.dive, windowId = "otherwindow")
    tools = agentTools(FakeAgentHeapDumps(listOf(window, other)))

    // The one case a file name cannot answer, and so the reason a window id is still on this surface: the
    // same file open twice is two readings of it being compared, and either answer would be the wrong one
    // half the time.
    assertThatThrownBy { call("list_leaks", HEAP_DUMP to window.heapDumpName) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("2 windows have \"${window.heapDumpName}\" open")
      .hasMessageContaining(window.windowId)
      .hasMessageContaining(other.windowId)
  }

  @Test
  fun `a heap dump that is not open is refused by name`() {
    assertThatThrownBy { call("list_leaks", HEAP_DUMP to "closed.hprof") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("No open heap dump is called \"closed.hprof\", and no window is either")
      .hasMessageContaining(window.heapDumpName)
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
    // The one link most worth handing back, so it comes with the conclusion rather than needing a show call
    // after it: it opens the object this conclusion is about, with the conclusion in its notes.
    assertThatLinkOpens(answer, heapDump.activityObjectId)
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
  fun `showing a place answers with the link to it`() {
    val answer = call("show", "place" to hex(heapDump.activityObjectId))

    // The half of showing that outlives the call: an agent writing its answer somewhere else has this to
    // point at, where "open the window and click the activity" is a set of instructions.
    assertThatLinkOpens(answer, heapDump.activityObjectId)
  }

  @Test
  fun `an argument the tool does not take is refused rather than ignored`() {
    assertThatThrownBy { call("find_objects", "query" to HOLDER_CLASS_NAME) }
      .isInstanceOf(AgentRefusal::class.java)
      // Named both ways round, because the mistake is a name from somewhere else — `query` is what the
      // window's own search box is called — and the fix is the name this tool uses.
      .hasMessageContaining("`query`")
      .hasMessageContaining("`className`")

    // Which is worth refusing rather than ignoring because ignoring it answers: a filter nothing was read
    // into matches the whole heap dump, and the largest objects in it read like a list of matches.
    val matched = call("find_objects", "className" to HOLDER_CLASS_NAME)
    assertThat(matched.text("matchCount")).isEqualTo("2")
    assertThat(matched.text("totalCount").toInt()).isGreaterThan(2)
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

  @Test
  fun `the dominator tree comes back as a tree, with what was left out of each level counted`() {
    val answer = call("dominator_tree", "maxDepth" to "1", "maxChildren" to "2")

    // The whole heap dump, which is the node every treemap opens on and the default here.
    assertThat(answer.text("retainedBytes").toLong()).isGreaterThan(0)
    val children = answer.array("dominates").map { it.jsonObject }
    assertThat(children).hasSizeLessThanOrEqualTo(2)
    // Largest first, which is the order every list in this app is in.
    val retained = children.map { it.text("retainedBytes").toLong() }
    assertThat(retained).isEqualTo(retained.sortedDescending())
    // Against the children handed back, so that "this is all of it" is never mistaken for the biggest few.
    assertThat(answer.text("dominatedNodeCount").toInt())
      .isGreaterThanOrEqualTo(children.size)
    // One level asked for is one level answered with, so nothing under these was walked.
    assertThat(children.flatMap { it.array("dominates") }).isEmpty()
  }

  @Test
  fun `the dominator tree under one object is the tree under that object`() {
    val answer = call("dominator_tree", OBJECT to hex(heapDump.holderObjectId), "maxDepth" to "1")

    assertThat(answer.text("node")).isEqualTo(hex(heapDump.holderObjectId))
    assertThat(answer.array("dominates").map { it.jsonObject.text("node") })
      .contains(hex(heapDump.activityObjectId))
  }

  @Test
  fun `an object the tree has no node for is refused rather than walked`() {
    assertThatThrownBy { call("dominator_tree", OBJECT to "0x1") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("is no node of this heap dump's dominator tree")
  }

  @Test
  fun `the notes say where somebody has been before they are read`() {
    val empty = call("read_notes")

    assertThat(empty.text("placeCount")).isEqualTo("0")
    assertThat(empty.text("nothingWritten")).contains("Nobody has written anything")

    call(
      "take_note",
      "place" to hex(heapDump.holderObjectId),
      "text" to "Holder.INSTANCE is assigned in ExampleApplication.onCreate."
    )

    assertThat(call("read_notes").array("places").map { it.jsonPrimitive.content })
      .containsExactly(hex(heapDump.holderObjectId))
    val note = call("read_notes", "place" to hex(heapDump.holderObjectId))
    assertThat(note.text("text")).contains("assigned in ExampleApplication.onCreate")
    assertThat(note.text("characters").toInt()).isGreaterThan(0)
  }

  @Test
  fun `a note can be replaced, which is what correcting one is`() {
    val place = Place.Object(heapDump.holderObjectId)
    call("take_note", "place" to hex(heapDump.holderObjectId), "text" to "A second holder holds it too.")

    val answer = call(
      "take_note",
      "place" to hex(heapDump.holderObjectId),
      "text" to "There is only one holder; I had misread the object list.",
      "replace" to "true"
    )

    assertThat(answer.text("replaced")).isEqualTo("true")
    // In place of what was there rather than under it, so that the wrong paragraph is not what the next
    // reader finds first.
    assertThat(window.notes[place])
      .containsExactly("There is only one holder; I had misread the object list.")
  }

  @Test
  fun `a heap dump nobody has open can be opened by its path`() {
    val other = FakeAgentHeapDump(heapDump.dive, windowId = "openedwindow")
    val heapDumps = FakeAgentHeapDumps(listOf(window), opens = { other })
    tools = agentTools(heapDumps)

    val answer = call("open_heap_dump", "path" to heapDump.dive.heapDumpFile.absolutePath)

    assertThat(answer.text("window")).isEqualTo("openedwindow")
    assertThat(answer.text("opened")).isEqualTo("true")
    assertThat(heapDumps.opened).containsExactly(heapDump.dive.heapDumpFile)
  }

  @Test
  fun `a path with no file at it is refused before anything is opened`() {
    val heapDumps = FakeAgentHeapDumps(listOf(window))
    tools = agentTools(heapDumps)

    assertThatThrownBy { call("open_heap_dump", "path" to "/no/such/dump.hprof") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("There is no file at /no/such/dump.hprof")

    assertThat(heapDumps.opened).isEmpty()
  }

  @Test
  fun `the devices adb is connected to, and then the processes of one`() {
    val device = AndroidDevice(
      serialNumber = "emulator-5554",
      state = "device",
      fingerprint = "google/sdk_gphone64_arm64/emu64a:16/BE1A.250305.005/13103848:userdebug/dev-keys",
      model = "sdk_gphone64_arm64",
      sdkInt = 36,
      isDebuggableBuild = true
    )
    val process = DeviceProcess(processId = 4231, name = "com.example.app")
    tools = agentTools(FakeAgentHeapDumps(listOf(window), devices = mapOf(device to listOf(process))))

    val devices = call("list_devices").array("devices").map { it.jsonObject }
    assertThat(devices.single().text("device")).isEqualTo("emulator-5554")
    // The difference between a device with two dumpable processes on it and one with all of them.
    assertThat(devices.single().text("dumpsAnyProcess")).isEqualTo("true")

    val processes = call("list_devices", "device" to "emulator-5554").array("processes").map { it.jsonObject }
    assertThat(processes.single().text("process")).isEqualTo("com.example.app")
    assertThat(processes.single().text("processId")).isEqualTo("4231")
  }

  @Test
  fun `a machine with nothing plugged in says so rather than answering with an empty list`() {
    tools = agentTools(FakeAgentHeapDumps(listOf(window)))

    assertThat(call("list_devices").text("problem")).contains("connected to no device")
  }

  @Test
  fun `a heap dump taken off a device is opened in a window`() {
    val device = AndroidDevice(
      serialNumber = "emulator-5554",
      state = "device",
      fingerprint = null,
      model = null,
      sdkInt = 36,
      isDebuggableBuild = true
    )
    val process = DeviceProcess(processId = 4231, name = "com.example.app")
    val dumped = FakeAgentHeapDump(heapDump.dive, windowId = "dumpedwindow")
    val heapDumps = FakeAgentHeapDumps(
      open = listOf(window),
      devices = mapOf(device to listOf(process)),
      opens = { dumped }
    )
    tools = agentTools(heapDumps)

    val answer = call("dump_heap", "device" to "emulator-5554", "process" to "com.example.app")

    assertThat(answer.text("window")).isEqualTo("dumpedwindow")
    assertThat(answer.text("dumped")).isEqualTo("true")
    assertThat(heapDumps.dumped).containsExactly("emulator-5554" to "com.example.app")
  }

  @Test
  fun `the agent logs are a place too, since an agent can be asked what another one did`() {
    call("show", "place" to "agent-logs")
    call("show", "place" to "agent-logs:agent-20260825-abcdef")

    assertThat(window.shown).containsExactly(
      Place.AgentLogs,
      Place.AgentLog("agent-20260825-abcdef")
    )
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

  /**
   * A session somebody else already ran on a heap dump: two calls, one of them refused.
   *
   * Written by hand rather than by running the tools, because what `agent_log` answers with is what
   * [AgentSessionFile] read back off disk — and how a call becomes a line of that file is
   * [AgentSessionFileTest]'s.
   */
  private fun recordedSession(
    sessionId: String,
    heapDumpPath: String = heapDump.dive.heapDumpFile.absolutePath,
    concluded: String? = null
  ) = AgentSession(
    sessionId = sessionId,
    startedAt = Instant.parse("2026-08-26T09:15:00Z"),
    client = "claude-code 9.9.9",
    serverVersion = "1.2.3",
    file = temporaryFolder.newFile("agent-$sessionId.jsonl"),
    calls = listOf(
      recordedCall(
        tool = "list_leaks",
        heapDumpPath = heapDumpPath,
        reason = "Reading what the dump says about itself.",
        refusal = "list_leaks needs `reason`, and it was not given."
      ),
      recordedCall(
        tool = "conclude",
        heapDumpPath = heapDumpPath,
        reason = "Naming the reference the chain agrees on.",
        outcome = concluded
      )
    )
  )

  private fun recordedCall(
    tool: String,
    heapDumpPath: String,
    reason: String,
    refusal: String? = null,
    outcome: String? = null
  ) = AgentSessionCall(
    at = Instant.parse("2026-08-26T09:15:01Z"),
    tool = tool,
    reason = reason,
    windowId = window.windowId,
    heapDumpPath = heapDumpPath,
    place = null,
    arguments = emptyMap(),
    refusal = refusal,
    outcome = outcome,
    millis = 3L
  )

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

  /**
   * The link an answer hands back, read as a link rather than compared as text.
   *
   * What matters about it here is what it opens — this object, of this heap dump, in the window the call was
   * made against — and how it is spelled is `DeepLinkTest`'s. It names the heap dump rather than only the
   * window so that an agent can put it in an answer somebody reads after this run has ended, and it names it
   * by file name alone: where that file is, is looked up by whoever follows the link.
   */
  private fun assertThatLinkOpens(
    answer: JsonObject,
    objectId: Long
  ) {
    val link = DeepLink.parse(answer.text("link"))
    assertThat(link.heapDumpName).isEqualTo(window.heapDumpName)
    assertThat(link.heapDumpPath).isNull()
    assertThat(link.place).isEqualTo(Place.Object(objectId))
  }

  private companion object {

    const val OPEN_HEAP_DUMPS = "open_heap_dumps"
    const val OPEN_HEAP_DUMP = "open_heap_dump"
    const val AGENT_LOG = "agent_log"
    const val SET_VERDICT = "set_verdict"
    const val CONCLUDE = "conclude"
    const val HEAP_DUMP = "heapDump"
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
