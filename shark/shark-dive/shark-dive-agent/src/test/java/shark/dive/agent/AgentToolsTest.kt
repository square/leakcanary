package shark.dive.agent

import java.io.File
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.LeakTrace
import shark.dive.AndroidDevice
import shark.dive.DeepLink
import shark.dive.DeviceProcess
import shark.dive.HeapLeaks
import shark.dive.HeapObjectKind
import shark.dive.LeakGroup
import shark.dive.LeakKind
import shark.dive.LeakSection
import shark.dive.Verdict
import shark.dive.LeakingObject
import shark.dive.ObjectListFilter
import shark.dive.Place
import shark.dive.ReachabilityStrength
import shark.dive.exactHexObjectId

/**
 * What an agent gets back from each tool, and what it gets refused for.
 *
 * The story these run through is the one the whole surface exists for: a heap dump that says a destroyed
 * activity shouldn't be there, a path that will not name a single reference while the object above it has no
 * verdict and says which step is unexplained, and then — one verdict later — the same path naming
 * `Holder.activity` with `leakSolved` true, which is the heap dump saying the search is over rather than the
 * agent saying so.
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
  fun `listing the heap dumps says which are open and hands over no method`() {
    val answer = call(LIST_HEAP_DUMPS)

    // Neither half of the method is in any answer — both are text this build prints, so asking what is open
    // does not hand an agent the whole of how to narrow a path before it knows the question is a leak at all.
    // See [AgentMethod].
    assertThat(answer.keys).doesNotContain(METHOD)
    val dumps = answer.array("heapDumps")
    assertThat(dumps).hasSize(1)
    // The key every other command names this dump by, and the path it was opened as. Both, because both are
    // things an agent has in front of it. See [AgentTools.resolvedDump].
    assertThat(dumps.first().jsonObject.text(HEAP_DUMP)).isEqualTo(window.heapDumpName)
    assertThat(dumps.first().jsonObject.text("heapDumpPath"))
      .isEqualTo(heapDump.dive.heapDumpFile.absolutePath)
  }

  @Test
  fun `sizes come back with what a retained size is a share of`() {
    val sizes = call(LIST_HEAP_DUMPS).array("heapDumps").first().jsonObject.obj("sizes")

    assertThat(sizes.text("totalBytes").toLong()).isGreaterThan(0)
    assertThat(sizes.text("stronglyReachableBytes").toLong()).isGreaterThan(0)
    assertThat(sizes.array("byStrength")).isNotEmpty
  }

  @Test
  fun `a window busy with a read of its own is listed in full rather than waited for`() {
    // This is the one call whose subject is every open window at once, so a window in the middle of something
    // would hold up the listing of all of them — measured at 40 seconds behind a leak analysis of a dump the
    // agent had not been asked about, for an answer that is file names and numbers already in memory. A read
    // that never comes back is the whole of what that costs, which is why this window's does not.
    val busy = FakeAgentHeapDump(heapDump.dive, path = BUSY_PATH, beforeRead = { awaitCancellation() })
    tools = agentTools(FakeAgentHeapDumps(listOf(busy, window)))

    val dumps = call(LIST_HEAP_DUMPS).array("heapDumps").map { it.jsonObject }

    assertThat(dumps.map { it.text(HEAP_DUMP) }).containsExactly(busy.heapDumpName, window.heapDumpName)
    assertThat(dumps.first().obj("sizes").text("totalBytes").toLong()).isGreaterThan(0)
    assertThat(busy.reads).isEmpty()
  }

  @Test
  fun `a run with no heap dump open says so rather than answering`() {
    tools = agentTools(FakeAgentHeapDumps())

    assertThat(call(LIST_HEAP_DUMPS).text("problem")).contains("No heap dump is open")
    // And a call that named one: the next step is the same either way, which is why both say it.
    assertThatThrownBy { call(LIST_LEAK_GROUPS, HEAP_DUMP to window.heapDumpName) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining(OPEN_HEAP_DUMP)
  }

  @Test
  fun `a heap dump still being indexed is named rather than left to be guessed`() {
    tools = agentTools(FakeAgentHeapDumps(indexing = listOf("/eval/runs/4/heap-dump.hprof")))

    val answer = call(LIST_HEAP_DUMPS)

    assertThat(answer.array("indexing").map { it.jsonPrimitive.content })
      .containsExactly("/eval/runs/4/heap-dump.hprof")
    assertThat(answer.text("problem"))
      .contains("/eval/runs/4/heap-dump.hprof")
      .contains(OPEN_HEAP_DUMP)
  }

  @Test
  fun `the metadata is LeakCanary's own map, spelled as LeakCanary spells it`() {
    val metadata = call(HEAP_DUMP_METADATA).obj("metadata")

    // The keys are `shark.AndroidMetadataExtractor`'s own words, and the values are the strings it reports,
    // because what this answer is worth is being the same map as the one above a leak trace: a key renamed
    // here, or a number read out of a value, is a figure that no longer matches the one somebody was sent.
    assertThat(metadata.text("Build.VERSION.SDK_INT")).isEqualTo("34")
    assertThat(metadata.text("Build.MANUFACTURER")).isEqualTo("Google")
    assertThat(metadata.text("Instance count").toInt()).isGreaterThan(0)
    assertThat(metadata.text("Heap total bytes").toLong()).isGreaterThan(0)
    // Said rather than left out, which is the difference between a dump LeakCanary didn't write and one
    // whose version an agent is about to go and read the source of.
    assertThat(metadata.text("LeakCanary version")).isEqualTo("Unknown")
  }

  @Test
  fun `a heap dump that is no Android one is refused, with what does answer about it`() {
    temporaryFolder.jvmHeapDump().use { dive ->
      val jvm = FakeAgentHeapDump(dive)
      tools = agentTools(FakeAgentHeapDumps(listOf(jvm)))

      // Refused rather than answered with the handful of lines that aren't Android's, because the whole of
      // this map is: the extractor asks `shark.AndroidBuildMirror` first and throws on a dump without it.
      assertThatThrownBy { call(HEAP_DUMP_METADATA, HEAP_DUMP to jvm.heapDumpName) }
        .isInstanceOf(AgentRefusal::class.java)
        .hasMessageContaining("android.os.Build")
        .hasMessageContaining(LIST_HEAP_DUMPS)
    }
  }

  @Test
  fun `the agent log is what has already been tried on this heap dump`() {
    tools = agentTools(
      FakeAgentHeapDumps(listOf(window)),
      sessions = listOf(
        recordedSession("cli7", solved = "Holder.activity"),
        // Another dump's investigation, which is another dump's addresses: not this window's to answer with.
        recordedSession("cli8", heapDumpPath = "/dumps/another.hprof")
      )
    )

    val answer = call(AGENT_LOG)

    val sessions = answer.array("sessions").map { it.jsonObject }
    assertThat(sessions.map { it.text("session") }).containsExactly("cli7")
    // The one field a reader is looking for, and the one neither this screen nor the eval can work out for
    // itself: which reference that session's verdicts came to.
    assertThat(sessions.single().text("solved")).isEqualTo("Holder.activity")
    assertThat(sessions.single().text("refused")).isEqualTo("1")
  }

  @Test
  fun `one session of the log is every call it made, with the reasons`() {
    tools = agentTools(
      FakeAgentHeapDumps(listOf(window)),
      sessions = listOf(recordedSession("cli7", solved = "Holder.activity"))
    )

    val calls = call(AGENT_LOG, "session" to "cli7").array("calls").map { it.jsonObject }

    // The reasons are the point: a session read as a list of tool names is the protocol showing through.
    assertThat(calls.map { it.text("tool") }).containsExactly(LIST_LEAK_GROUPS, SET_VERDICT)
    assertThat(calls.first().text("reason")).isEqualTo("Reading what the dump says about itself.")
    assertThat(calls.last().text("outcome")).isEqualTo("Holder.activity")
    assertThat(calls.first().text("refused")).contains("needs `reason`")
  }

  @Test
  fun `one session of the log is what each call sent and what it read back`() {
    tools = agentTools(
      FakeAgentHeapDumps(listOf(window)),
      sessions = listOf(recordedSession("cli7", solved = "Holder.activity"))
    )

    val calls = call(AGENT_LOG, "session" to "cli7").array("calls").map { it.jsonObject }

    // Exactly, because an agent asked why an investigation went wrong is looking for the step whose reason
    // reads fine and whose answer didn't say what the reason assumed. A paraphrase hides that by definition.
    assertThat(calls.last().text("input")).isEqualTo(SOLVING_CALL_SENT)
    assertThat(calls.last().text("output")).isEqualTo(SOLVING_CALL_ANSWERED)
    // And a refused call kept what it sent, its answer being the refusal already above it.
    assertThat(calls.first().text("input")).isEqualTo(REFUSED_CALL_SENT)
    assertThat(calls.first()["output"]).isEqualTo(JsonNull)
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
    assertThat(call(LIST_HEAP_DUMPS).jsonObject.keys).doesNotContain("indexing")
  }

  @Test
  fun `every call says which heap dump it is about, whether or not there is a choice`() {
    // One dump open and it still has to be named, which is the rule this surface is built on: an agent
    // investigating two dumps at once and an agent investigating one make the same calls, so there is no
    // state to get wrong and no answer that is about whichever dump was opened last.
    assertThatThrownBy { callWith(LIST_LEAK_GROUPS, buildJsonObject { put(REASON, "Reading the leaks.") }) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("$LIST_LEAK_GROUPS needs `$HEAP_DUMP`")
  }

  @Test
  fun `a heap dump is named by its file name, or by the path it was given as`() {
    // Both, because both are things an agent has in front of it: the name is in every answer it has been
    // given, and the path is what somebody handing it a dump says.
    assertThat(call(LIST_LEAK_GROUPS, HEAP_DUMP to window.heapDumpName).text("objectCount")).isNotEmpty()
    assertThat(call(LIST_LEAK_GROUPS, HEAP_DUMP to window.heapDumpPath).text("objectCount")).isNotEmpty()
  }

  @Test
  fun `two heap dumps open are answered about one at a time`() {
    val other = FakeAgentHeapDump(heapDump.dive, path = OTHER_PATH)
    tools = agentTools(FakeAgentHeapDumps(listOf(window, other)))

    assertThat(call(CLOSE_HEAP_DUMP, HEAP_DUMP to other.heapDumpName).text("closed"))
      .isEqualTo(other.heapDumpName)
    // And the other one is still open, since what a call names is the whole of which dump it is about.
    assertThat(call(LIST_HEAP_DUMPS).array("heapDumps").map { it.jsonObject.text(HEAP_DUMP) })
      .containsExactly(window.heapDumpName)
  }

  @Test
  fun `a heap dump that is not open is refused by name`() {
    assertThatThrownBy { call(LIST_LEAK_GROUPS, HEAP_DUMP to "closed.hprof") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("No open heap dump is called \"closed.hprof\"")
      .hasMessageContaining(window.heapDumpName)
      .hasMessageContaining(OPEN_HEAP_DUMP)
  }

  @Test
  fun `every command requires a reason, and a tool that does not use one answers without it`() {
    // Required of every schema, because `required` is what makes a client send it. Where it is *enforced* is
    // the command line, which is the one place that knows the other half of the rule: a session named with
    // `--session=` is an agent and is refused without a reason, and a command line that named no session is a
    // person typing one command. See [AgentCommandLine.run] and [AgentArguments.reason].
    assertThat(tools.all.filter { REASON !in it.schema.requiredArguments() }).isEmpty()

    val answer = callWith(
      LIST_LEAK_GROUPS,
      buildJsonObject { put(HEAP_DUMP, window.heapDumpName) }
    )

    assertThat(answer.array("sections")).isNotEmpty()
  }

  /**
   * The same `reason`, on every tool, with the same words.
   *
   * It was not always: `conclude` took the root cause it was reporting under this name, so its description
   * said something else and its argument was the finding rather than a line of the log. Nothing asks an agent
   * for a finding now — what an investigation comes to is derived from its verdicts — so a `reason` means one
   * thing across the surface. See [schema].
   */
  @Test
  fun `the reason means the same thing on every tool`() {
    val descriptions = tools.all.map { tool ->
      tool.schema.obj("properties").obj(REASON).text("description")
    }

    assertThat(descriptions.distinct()).hasSize(1)
    assertThat(descriptions.first()).contains("Why you are making this call")
  }

  @Test
  fun `no answer carries the leak method, and the leaks say where it is instead`() {
    // It was in this answer, and an investigation of four leaks is four calls: the method arrived with each
    // of them, unchanged, for a session that had read it before the second. So `list_leak_groups` names the
    // command that prints it — text this build carries, read once. See [AgentCommandLine.LEAK_METHOD_OPTION].
    assertThat(call(LIST_LEAK_GROUPS).keys).doesNotContain(METHOD)
    assertThat(tools.byName(LIST_LEAK_GROUPS)!!.description)
      .contains(AgentCommandLine.LEAK_METHOD_OPTION)
      // And it says to read it before the first path, since a method read after one is a path read twice.
      .contains("before the first path")
  }

  @Test
  fun `the leaks are what the heap dump says shouldn't be there`() {
    val leaks = call(LIST_LEAK_GROUPS)

    val objects = leaks.array("sections").flatMap { section ->
      section.jsonObject.array("groups").flatMap { it.jsonObject.array("objects") }
    }
    assertThat(objects.map { it.jsonObject.text("object") })
      .contains(exactHexObjectId(heapDump.activityObjectId))
    assertThat(objects.map { it.jsonObject.text("className") }).contains(ACTIVITY_CLASS_NAME)
  }

  @Test
  fun `a leak is named here the way the leaks screen names it`() {
    val groups = leakGroups()

    // One list with two readers — the person watching and the agent working — so a leak named `Holder.activity
    // →` on the row and spelled out as an array here is two leaks to whoever is reading both. See LeakGroup.name.
    assertThat(groups.map { it.text("name") }).isNotEmpty.allMatch { it.isNotEmpty() }
    assertThat(groups.map { it.text("name") }).anyMatch { ACTIVITY_FIELD_NAME in it }
    // And what is between the two ends of one is on the path for both of them, rather than in the answer for
    // one of them: the row draws a gap there and `path_from_gc_roots` is where either reader goes.
    assertThat(groups.map { it["suspectPath"] }).allMatch { it == null }
  }

  /**
   * A group's objects, and nothing naming one of them as the one to work.
   *
   * There was a `representativeObject`, documented as the object whose own path produced the group's
   * references and warned to be some other object than `objects` first. It was `objects` first every time —
   * both lists are sorted by retained size off the same number — so it was a second name for the same
   * address, dressed as a choice someone had made. Any object of a group solves the group, which the
   * description is what says. See [shark.dive.LeakGroup.objects].
   */
  @Test
  fun `the leaks name no object to solve a group through, since any of them does`() {
    val group = leakGroups().single { ACTIVITY_FIELD_NAME in it.text("name") }

    assertThat(group.keys).doesNotContain("representativeObject")
    assertThat(group.array("objects").map { it.jsonObject.text("object") })
      .contains(exactHexObjectId(heapDump.activityObjectId))
    assertThat(tools.byName(LIST_LEAK_GROUPS)!!.description)
      .contains("one address out of its `objects`")
      .contains("the first is as good as any")
  }

  /**
   * Addresses to go and ask about, and no path for any of the leaks in the list.
   *
   * Each group carried one object's path, both ways round, and a list of three leaks came back three and a
   * half times longer for it — 11,037 bytes against 38,657, measured on a real dump. What it bought was one
   * `path_from_gc_roots` saved on the single group a reader goes on to work, and the rest was paths for
   * leaks nobody opens. So the list says which leaks there are, and the path is the next question rather
   * than part of this answer.
   */
  @Test
  fun `the leaks carry no path, in either form`() {
    val group = leakGroups().single { ACTIVITY_FIELD_NAME in it.text("name") }

    assertThat(group.keys).doesNotContain(
      "representativeLeakTrace",
      "representativeHumanLeakTrace",
      // And not what each group retains either, which was read off the same walk.
      "retainedBytes"
    )
    // What takes their place, and it is one call: an object off the list, walked when it is wanted.
    val first = group.array("objects").first().jsonObject.text("object")
    val walked = call(PATH_FROM_GC_ROOTS, OBJECT to first)
    assertThat(walked.text(HUMAN_LEAK_TRACE)).contains(ACTIVITY_CLASS_NAME)
  }

  /**
   * What the subtitle of a row says, as a field per thing it was saying.
   *
   * One field holding a library leak's description under one section and a sentence about the garbage
   * collector under another is a field whose meaning a reader works out from where it is. Which is a
   * subtitle — the right shape for a row of a screen, and the wrong one for JSON, where a reader takes a
   * name for what the value is. See [shark.dive.LeakGroup.subtitle].
   *
   * Built here rather than read off a dump because the two things that subtitle holds are in two sections
   * the investigation dump has neither of, and the point of the split is that they come back under
   * different names.
   */
  @Test
  fun `what a subtitle was saying is a field per thing it said`() {
    val answer = AgentJson.leaks(
      HeapLeaks(
        listOf(
          section(LeakKind.APPLICATION, group(title = "Holder.activity", subtitle = null)),
          section(LeakKind.LIBRARY, group(title = "Looper.mQueue", subtitle = "Android holds it forever")),
          section(LeakKind.UNREACHABLE, group(title = "Bitmap", subtitle = LeakKind.UNREACHABLE.subtitle))
        )
      )
    )

    val sections = answer.array("sections").map { it.jsonObject }
    assertThat(sections.flatMap { it.array("groups") }.map { it.jsonObject["subtitle"] })
      .hasSize(3).allMatch { it == null }
    // The library leak's description, under the one section where a group has one of its own.
    assertThat(sections.map { it.array("groups").single().jsonObject["libraryLeakDescription"] })
      .containsExactly(null, JsonPrimitive("Android holds it forever"), null)
    // And the sentence about the collector as the explanation of the section it is about, which is what it
    // was: every group of that section carried the same words, and they are a property of the kind.
    assertThat(sections.map { it["explanation"] }).containsExactly(
      JsonPrimitive(LeakKind.APPLICATION.explanation),
      JsonPrimitive(LeakKind.LIBRARY.explanation),
      JsonPrimitive(LeakKind.UNREACHABLE.subtitle)
    )
  }

  @Test
  fun `describing an object reads its fields with the address of each value`() {
    val holder = call("describe_object", OBJECT to hex(heapDump.holderObjectId))

    assertThat(holder.text("className")).isEqualTo(HOLDER_CLASS_NAME)
    assertThat(holder.text("verdict")).isEqualTo(Verdict.UNKNOWN.name)
    val activityField = holder.array("fields")
      .single { it.jsonObject.text("name") == ACTIVITY_FIELD_NAME }
      .jsonObject
    assertThat(activityField.text("valueObject")).isEqualTo(hex(heapDump.activityObjectId))
  }

  @Test
  fun `an object the inspectors know about comes back with their verdict and their words`() {
    val activity = call("describe_object", OBJECT to hex(heapDump.activityObjectId))

    assertThat(activity.text("verdict")).isEqualTo(Verdict.STUCK.name)
    assertThat(activity.text("verdictReason")).contains("mDestroyed")
  }

  @Test
  fun `the path names no reference while an object in it has no verdict`() {
    val answer = call(PATH_FROM_GC_ROOTS, OBJECT to hex(heapDump.activityObjectId))

    val objects = answer.obj(LEAK_TRACE).array("path").map { it.jsonObject }
    assertThat(objects.map { it.text("object") }).containsExactly(
      hex(heapDump.applicationObjectId),
      hex(heapDump.holderObjectId),
      hex(heapDump.activityObjectId)
    )
    // Both references are still candidates, which is what a path marks while an object between them has no
    // verdict — a reference ruled out is what a verdict buys, and no verdict has been set here yet.
    assertThat(objects.mapNotNull { it["reference"]?.jsonObject?.text("isSuspect") })
      .containsOnly("true")
    val says = answer.obj(INVESTIGATION)
    assertThat(says.text("state")).isEqualTo(InvestigationState.NARROWED.name)
    assertThat(says.text(LEAK_SOLVED)).isEqualTo("false")
    // Which is the field an agent reads to know whether it is done, so an unsolved path leaves the reference
    // out rather than answering with something that could be mistaken for a name.
    assertThat(says["faultyReference"]).isNull()
    // Counted in references and not in objects, which is the thing to get right about a narrowed path: one
    // object with no verdict leaves the reference into it and the reference out of it, and its own verdict
    // rules one of them out. So the candidates are two and the object to decide about is one.
    assertThat(says.text("suspectReferenceCount")).isEqualTo("2")
    // Which references they are is on the path and not here, so the two are never two things to keep in
    // step: the candidates are the references marked `isSuspect` above.
    assertThat(says["suspectReferences"]).isNull()
    assertThat(says["undecidedObjects"]).isNull()
    // And the object to go and settle is named in the sentence, by address, since that is what the next
    // call takes.
    assertThat(says.text("next"))
      .contains(hex(heapDump.holderObjectId))
      .contains("describe_object")
      .contains(SET_VERDICT)
  }

  /**
   * A path is objects, and the reference out of each of them is on the object that holds it.
   *
   * Which is the direction a leak trace is printed in and the direction a reader walks one. The references
   * were on the object each one *reached* instead, so every reference of a path sat one object too low and
   * the GC rooted object at the top was the one entry with none — an asymmetry that reads as the walk having
   * dropped something rather than as a choice.
   */
  @Test
  fun `each object of the path carries the reference it holds the next one through`() {
    val answer = call(PATH_FROM_GC_ROOTS, OBJECT to hex(heapDump.activityObjectId))

    val objects = answer.obj(LEAK_TRACE).array("path").map { it.jsonObject }
    // The application holds the holder and the holder holds the activity, so those are the two references
    // the two objects above the activity carry — and the activity, which the path ends at, carries none.
    assertThat(objects.map { it["reference"]?.jsonObject?.text("name") })
      .containsExactly(HOLDER_FIELD_NAME, ACTIVITY_FIELD_NAME, null)
    // The field is declared on the object carrying it, which is what makes the direction checkable: a
    // reference read off the application is a field of the application.
    assertThat(objects.first()["reference"]?.jsonObject?.text("ownerClassName"))
      .isEqualTo(APPLICATION_CLASS_NAME.substringAfterLast('.'))
    // And the kind of root in Shark's own vocabulary rather than the window's label for it, which reads
    // "GC root: JNI global reference" and so says what this field's own name already says.
    assertThat(answer.obj(LEAK_TRACE).text("gcRootType"))
      .isEqualTo(LeakTrace.GcRootType.JNI_GLOBAL.name)
    assertThat(answer.obj(LEAK_TRACE).text("objectCount")).isEqualTo("3")
  }

  @Test
  fun `a path with nothing stuck on it says that is why it names nothing`() {
    val answer = call(PATH_FROM_GC_ROOTS, OBJECT to hex(heapDump.applicationObjectId))

    val says = answer.obj(INVESTIGATION)
    assertThat(says.text("state")).isEqualTo(InvestigationState.NOTHING_STUCK.name)
    // Nothing to be at fault, so nothing named: a path with no stuck object on it has no candidates either.
    assertThat(says.text("suspectReferenceCount")).isEqualTo("0")
    assertThat(says["faultyReference"]).isNull()
    // And no progress, which the formula alone would read as finished: no candidates out of two references
    // is `1 - 0/2`. A search that hasn't begun is 0. See [shark.dive.leakSolvingProgressRatio].
    assertThat(says.text(LEAK_SOLVING_PROGRESS_RATIO)).isEqualTo("0.0")
    assertThat(says.text("next")).contains(Verdict.STUCK.name)
  }

  /**
   * The leak trace, on the path as it is on the leak, so that an agent quoting one never builds it itself.
   *
   * Both tools that answer with a path carry it for that reason: an agent reaches a person from whichever of
   * them it last called, and a surface that has it on one of the two is a surface that invites the retelling
   * on the other. See [AgentJson.humanLeakTrace].
   */
  @Test
  fun `the path carries the leak trace too, and says how far the verdicts have narrowed it`() {
    val answer = call(PATH_FROM_GC_ROOTS, OBJECT to hex(heapDump.activityObjectId))

    assertThat(answer.text(HUMAN_LEAK_TRACE)).startsWith("┬───").contains(ACTIVITY_CLASS_NAME)
    // Two of the three references are still candidates: the GC root holding the application is the one
    // ruled out, by the inspector that knows an application belongs in memory. Which is the number a verdict
    // moves, and the reason it is here rather than counted off the path by whoever reads it.
    assertThat(answer.obj(INVESTIGATION).text(LEAK_SOLVING_PROGRESS_RATIO)).isEqualTo("0.33")
    assertThat(answer.obj(LEAK_TRACE).text("gcRootIsSuspect")).isEqualTo("false")
  }

  @Test
  fun `a verdict narrows the path to the stuck object to one reference`() {
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to Verdict.EXPECTED.name,
      SOLVING_LEAK_OF to hex(heapDump.activityObjectId),
      WHY to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )

    assertThat(answer.text("set")).isEqualTo("true")
    assertThat(answer.text("verdictsFlipped")).isEqualTo("0")
    // The one field the whole surface is worked towards, and it is the heap dump's answer rather than the
    // agent's: nothing was said about which reference is at fault, only that this one object's work is done.
    assertThat(answer.text(LEAK_SOLVED)).isEqualTo("true")
    val says = answer.obj(INVESTIGATION)
    assertThat(says.text("state")).isEqualTo(InvestigationState.SOLVED.name)
    assertThat(says.text(LEAK_SOLVED)).isEqualTo("true")
    // One candidate left, which is the same fact as the investigation being over.
    assertThat(says.text("suspectReferenceCount")).isEqualTo("1")
    assertThat(says.text("next")).contains("$FAULTY_REFERENCE is the faulty reference")
    // Named in the same words the window's `Leak solved` section uses, so that an agent quoting it to its
    // human names what the human is looking at — and with where it is, which is the pointer rather than a
    // string to go and search the path for.
    val faulty = says.obj("faultyReference")
    assertThat(faulty.text("reference")).isEqualTo(FAULTY_REFERENCE)
    // The index of the object that *holds* it, which is the holder rather than the activity: a path carries
    // each reference on the object it is read off.
    val objects = answer.obj(LEAK_TRACE).array("path").map { it.jsonObject }
    val index = faulty.text("objectIndex").toInt()
    assertThat(objects[index].text("object")).isEqualTo(hex(heapDump.holderObjectId))
    assertThat(objects[index].obj("reference").text("isSuspect")).isEqualTo("true")
    // And the one reference left: the one above it was ruled out by the verdict this call set.
    assertThat(objects.mapNotNull { it["reference"]?.jsonObject?.text("isSuspect") })
      .containsExactly("false", "true")
    // With the leak trace, which is what that agent hands over: this is the last call of an investigation,
    // so a trace it had to go back for is a trace it would have typed instead.
    assertThat(answer.text(HUMAN_LEAK_TRACE)).contains(ACTIVITY_CLASS_NAME)
  }

  /**
   * A `why` links its sources, and the leak trace prints the `why` beside the object. The text form goes in
   * front of a person, where nothing renders a link, so the link is its text there and the target is only in
   * the fields. See [AgentJson.humanLeakTrace].
   */
  @Test
  fun `the leak trace prints a link in a why as its text`() {
    val link = "idea://open?file=/Users/someone/app/src/main/java/com/example/Holder.kt&line=12"
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to Verdict.EXPECTED.name,
      SOLVING_LEAK_OF to hex(heapDump.activityObjectId),
      WHY to "Holder.INSTANCE is a static singleton, assigned once in [Holder.kt:12]($link)."
    )

    assertThat(answer.text(HUMAN_LEAK_TRACE))
      .contains("assigned once in Holder.kt:12.")
      .doesNotContain("idea://")
    val holder = answer.obj(LEAK_TRACE).array("path").map { it.jsonObject }
      .single { it.text("object") == hex(heapDump.holderObjectId) }
    assertThat(holder.text("verdictReason")).contains("[Holder.kt:12]($link)")
  }

  /**
   * The GC root is a reference too, the one holding the first object of the path, so a stuck object a root
   * holds directly is a leak of that root. Nothing is above a root to be expected, and nothing needs to be.
   * See [shark.dive.isGcRootFaulty].
   */
  @Test
  fun `a stuck object a GC root holds is a leak of that root`() {
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.applicationObjectId),
      "verdict" to Verdict.STUCK.name,
      SOLVING_LEAK_OF to hex(heapDump.activityObjectId),
      WHY to "Recorded as stuck to read the path a GC root holding a stuck object makes."
    )

    assertThat(answer.text(LEAK_SOLVED)).isEqualTo("true")
    val says = answer.obj(INVESTIGATION)
    assertThat(says.text("state")).isEqualTo(InvestigationState.SOLVED.name)
    // One candidate left, which is the root, as for any solved leak.
    assertThat(says.text("suspectReferenceCount")).isEqualTo("1")
    assertThat(says.text("next")).contains("$JNI_GLOBAL_ROOT is the faulty reference")
    // Named the way the window's path draws its first line, and with no index: no object of the path holds
    // the root, so there is no `path[objectIndex].reference` for it to be.
    val faulty = says.obj("faultyReference")
    assertThat(faulty.text("reference")).isEqualTo(JNI_GLOBAL_ROOT)
    assertThat(faulty["objectIndex"]).isNull()
    // And the candidate is marked where the path says what the root is, rather than on a reference of an
    // object, none of which could be at fault now.
    val leakTrace = answer.obj(LEAK_TRACE)
    assertThat(leakTrace.text("gcRootIsSuspect")).isEqualTo("true")
    assertThat(leakTrace.array("path").mapNotNull { it.jsonObject["reference"]?.jsonObject?.text("isSuspect") })
      .containsOnly("false")
  }

  /**
   * What the verdict did, rather than where the path ended up.
   *
   * The pair and not the new number alone, which is the whole reason `solvingLeakOf` exists: a verdict that
   * ruled nothing out and a verdict that finished the investigation leave the same single count behind them,
   * and an agent reading one number cannot tell which move it just made.
   */
  @Test
  fun `a verdict says what it narrowed, before and after`() {
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to Verdict.EXPECTED.name,
      SOLVING_LEAK_OF to hex(heapDump.activityObjectId),
      WHY to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )

    val narrowed = answer.obj("narrowedBy")
    assertThat(narrowed.text("suspectReferencesBefore")).isEqualTo("2")
    assertThat(narrowed.text("suspectReferencesAfter")).isEqualTo("1")
    assertThat(narrowed.text("progressRatioBefore")).isEqualTo("0.33")
    // Two of this path's three references ruled out, the GC root being the other one. Not 1.0, and that is
    // not an off-by-one: a solved leak still has the faulty reference as a candidate, so what says it is
    // over is `leakSolved`.
    assertThat(narrowed.text("progressRatioAfter")).isEqualTo("0.67")
  }

  @Test
  fun `a verdict set without naming the stuck object says to read that path again`() {
    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to Verdict.EXPECTED.name,
      WHY to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )

    assertThat(answer.text("set")).isEqualTo("true")
    assertThat(answer.text("next")).contains(PATH_FROM_GC_ROOTS).contains(SOLVING_LEAK_OF)
    assertThat(answer["path"]).isNull()
    assertThat(answer[LEAK_SOLVED]).isNull()
  }

  /**
   * The leak being solved is a stuck object, and naming anything else is refused rather than answered with.
   *
   * The mistake this catches is one name away from the right call: `object` is what the verdict is about and
   * `solvingLeakOf` is the leak it is part of, and an agent that passes the object it is ruling out gets a
   * path with nothing stuck on it — whose numbers would all be about nothing, and whose progress would read
   * as a verdict that achieved zero.
   */
  @Test
  fun `the leak being solved has to be an object this heap dump reads as stuck`() {
    assertThatThrownBy {
      call(
        SET_VERDICT,
        OBJECT to hex(heapDump.holderObjectId),
        "verdict" to Verdict.EXPECTED.name,
        SOLVING_LEAK_OF to hex(heapDump.holderObjectId),
        WHY to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining(Verdict.UNKNOWN.name)
      .hasMessageContaining(SOLVING_LEAK_OF)
      .hasMessageContaining(LIST_LEAK_GROUPS)

    // And nothing was recorded, the refusal being about the call rather than about what it did.
    assertThat(window.verdicts.isEmpty).isTrue()
  }

  /**
   * The two justifications a `set_verdict` call carries, which are one argument each on purpose.
   *
   * `reason` is why this call was made and lives as long as this session's log; the `why` is the verdict's
   * own and stays in the heap dump's verdict file for whoever reads it next. Asserting that the
   * `reason` is *not* what got kept is the half worth having: it is what they were before, and one argument
   * doing both jobs is what had a model sending both names and getting refused.
   */
  @Test
  fun `the why is kept with the verdict, and the reason of the call is not`() {
    call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to Verdict.EXPECTED.name,
      WHY to "Holder.INSTANCE is a static singleton.",
      "reason" to "Ruling out the object above the activity."
    )

    val verdict = window.verdicts[heapDump.holderObjectId]
    assertThat(verdict?.verdict).isEqualTo(Verdict.EXPECTED)
    assertThat(verdict?.reason).isEqualTo("Holder.INSTANCE is a static singleton.")
  }

  @Test
  fun `a verdict with no why is refused, since the next reader has nothing else to check it by`() {
    assertThatThrownBy {
      call(
        SET_VERDICT,
        OBJECT to hex(heapDump.holderObjectId),
        "verdict" to Verdict.EXPECTED.name,
        "reason" to "Ruling out the object above the activity."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("needs `$WHY`")

    assertThat(window.verdicts.isEmpty).isTrue()
  }

  /**
   * A solved leak writes nothing, which is what replaced `conclude`.
   *
   * That tool wrote a *## Root cause* note into the object it was called about, so finishing an
   * investigation left a paragraph behind whether or not anybody wanted one. What a run leaves now is the
   * verdicts it recorded, each with its evidence — and a note if the agent has something to add, through
   * [TAKE_NOTE] like any other note. See [AgentTools].
   */
  @Test
  fun `solving a leak leaves the verdicts behind it and nothing else`() {
    call(
      SET_VERDICT,
      OBJECT to hex(heapDump.holderObjectId),
      "verdict" to Verdict.EXPECTED.name,
      SOLVING_LEAK_OF to hex(heapDump.activityObjectId),
      WHY to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )

    assertThat(window.notes).isEmpty()
    // And nothing was opened either: what to show a person is the agent's to decide, with `show`.
    assertThat(window.shown).isEmpty()
    assertThat(window.verdicts[heapDump.holderObjectId]?.verdict).isEqualTo(Verdict.EXPECTED)
  }

  @Test
  fun `a verdict that contradicts one already recorded is refused until it is told to flip it`() {
    setHolderExpected()

    assertThatThrownBy {
      call(
        SET_VERDICT,
        OBJECT to hex(heapDump.applicationObjectId),
        "verdict" to Verdict.STUCK.name,
        WHY to "This isn't the real Application, it is a copy left over from a test."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("contradicts 1 verdict(s)")
      .hasMessageContaining(hex(heapDump.holderObjectId))
      // The verdict it disagrees with, in the words it was given, since that is what decides which of the
      // two is wrong. As a sentence and not as JSON: this refusal is drawn on the *Agent logs* screen, which
      // exists to not show the protocol. See AgentTools.asSentence.
      .hasMessageContaining("Holder.INSTANCE is a static singleton")
      .hasMessageContaining("Keeping yours makes it ${Verdict.STUCK}")
      .hasMessageNotContaining("{\"")
      .hasMessageContaining("solveConflicts")

    val answer = call(
      SET_VERDICT,
      OBJECT to hex(heapDump.applicationObjectId),
      "verdict" to Verdict.STUCK.name,
      "solveConflicts" to "true",
      WHY to "This isn't the real Application, it is a copy left over from a test."
    )

    assertThat(answer.text("verdictsFlipped")).isEqualTo("1")
    assertThat(window.verdicts[heapDump.holderObjectId]?.verdict).isEqualTo(Verdict.STUCK)
    assertThat(window.verdicts[heapDump.holderObjectId]?.reason)
      .contains("Holder.INSTANCE is a static singleton")
  }

  @Test
  fun `unknown is refused as a verdict, because it is what no verdict already is`() {
    assertThatThrownBy {
      call(
        SET_VERDICT,
        OBJECT to hex(heapDump.holderObjectId),
        "verdict" to Verdict.UNKNOWN.name,
        WHY to "I could not work out what this is."
      )
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("clear_verdict")
  }

  @Test
  fun `clearing a verdict says what it was, and clearing nothing is refused`() {
    setHolderExpected()

    val answer = call("clear_verdict", OBJECT to hex(heapDump.holderObjectId))

    assertThat(answer.text("was")).isEqualTo(Verdict.EXPECTED.name)
    assertThat(answer.text(WHY)).contains("static singleton")
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
  fun `what points at an object comes back with each reference and whether it holds the object`() {
    val answer = call("referrers", OBJECT to hex(heapDump.activityObjectId))

    assertThat(answer.text("referrerCount")).isEqualTo("1")
    assertThat(answer.text("holdingReferrerCount")).isEqualTo("1")
    assertThat(answer["nextOffset"]).isEqualTo(JsonNull)
    val referrer = answer.array("referrers").single().jsonObject
    assertThat(referrer.text("object")).isEqualTo(hex(heapDump.holderObjectId))
    assertThat(referrer.text("className")).isEqualTo(HOLDER_CLASS_NAME)
    // A row of a list of objects, which is on no path: nothing reaches it, and no verdict is read for it.
    assertThat(referrer.keys).doesNotContain("reference", "verdict")
    val reference = referrer.array("references").single().jsonObject
    assertThat(reference.text("name")).isEqualTo(ACTIVITY_FIELD_NAME)
    assertThat(reference.text("holds")).isEqualTo("true")
    assertThat(reference.keys).doesNotContain("isSuspect")
  }

  @Test
  fun `a gc root on an object is a referrer with its type in place of an object`() {
    val answer = call("referrers", OBJECT to hex(heapDump.applicationObjectId))

    assertThat(answer.text("referrerCount")).isEqualTo("1")
    assertThat(answer.text("holdingReferrerCount")).isEqualTo("1")
    val gcRoot = answer.array("referrers").single().jsonObject
    assertThat(gcRoot.text("gcRootType")).isEqualTo(LeakTrace.GcRootType.JNI_GLOBAL.name)
    assertThat(gcRoot.text("holds")).isEqualTo("true")
    assertThat(gcRoot.keys).doesNotContain("object", "references")
  }

  @Test
  fun `a page of referrers past the last one is refused with how many there are`() {
    assertThatThrownBy { call("referrers", OBJECT to hex(heapDump.activityObjectId), "offset" to "1") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("`offset` is 1")
      .hasMessageContaining("has a `referrerCount` of 1")
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
        put(HEAP_DUMP, window.heapDumpName)
        put("className", HOLDER_CLASS_NAME)
        put("exactMatch", true)
        put("kinds", jsonArrayOf(HeapObjectKind.INSTANCE.name))
        put(REASON, "Checking whether the holder is the singleton it looks like.")
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
          put(HEAP_DUMP, window.heapDumpName)
          put("kinds", jsonArrayOf("BITMAPS"))
          put(REASON, "Looking for the bitmaps.")
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
    call("show", "place" to "metadata")
    call("show", "place" to hex(heapDump.activityObjectId))

    assertThat(window.shown).containsExactly(
      Place.Leaks(),
      Place.Objects(),
      Place.Objects(ObjectListFilter(query = HOLDER_CLASS_NAME)),
      Place.Starred,
      Place.Metadata,
      Place.Object(heapDump.activityObjectId)
    )

    assertThatThrownBy { call("show", "place" to "the leak") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("is no place of a heap dump")
  }

  /**
   * Showing one object, asked for the way the rest of this surface asks about one.
   *
   * Which is the call `show` is nearly always made as — an agent reaches it holding an address, straight
   * after `describe_object` — and it is the one that used to be refused, `place` having been the only name
   * this tool took.
   */
  @Test
  fun `an object is shown under the name every other tool takes one by`() {
    val answer = call("show", OBJECT to hex(heapDump.activityObjectId))

    assertThat(window.shown).containsExactly(Place.Object(heapDump.activityObjectId))
    // The half of showing that outlives the call: an agent writing its answer somewhere else has this to
    // point at, where "open the window and click the activity" is a set of instructions.
    assertThatLinkOpens(answer, heapDump.activityObjectId)
  }

  /**
   * The two arguments, either and never both, since a call naming a screen and an object has said two things.
   *
   * Both refusals matter for the same reason the tool takes two names at all: this is the one tool on the
   * surface with a choice to get wrong, so what it says has to be the next call rather than that the last one
   * was wrong.
   */
  @Test
  fun `showing nothing and showing two things are both refused with what to name`() {
    assertThatThrownBy { call("show") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("needs to be told what to show")
      .hasMessageContaining("`$OBJECT`")
      .hasMessageContaining("`place`")

    assertThatThrownBy {
      call("show", OBJECT to hex(heapDump.activityObjectId), "place" to PLACE_LEAKS)
    }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("two places to open in one call")

    assertThat(window.shown).isEmpty()
  }

  /**
   * A screen asked for as an object, answered with the syntax that shows it rather than with "that is no
   * address".
   *
   * The mistake this half exists for is the mirror of the one above: every tool here takes `$OBJECT`, so that
   * is the name an agent reaches for when what it wants shown is a screen, and a class name is the other thing
   * it reaches for — many objects rather than one, which the object list is for.
   */
  @Test
  fun `a screen or a class name asked for as an object is answered with the syntax that shows it`() {
    assertThatThrownBy { call("show", OBJECT to PLACE_LEAKS) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("`place=$PLACE_LEAKS`")

    assertThatThrownBy { call("show", OBJECT to HOLDER_CLASS_NAME) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("`place=objects:$HOLDER_CLASS_NAME`")

    // And a word that is neither gets the refusal for an address, which is what it is.
    assertThatThrownBy { call("show", OBJECT to "the leak") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("is no object address")

    assertThat(window.shown).isEmpty()
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
      .hasMessageContaining("Never a decimal number")
  }

  @Test
  fun `a class name is refused with the call that turns it into an address`() {
    // Which is what the method tells an agent to do with `android.os.Build$VERSION`, and it did: a class is
    // an object of the dump, and the only thing between its name and its static fields is the lookup.
    assertThatThrownBy { call("describe_object", OBJECT to "android.os.Build\$VERSION") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("find_objects")
      .hasMessageContaining("className=android.os.Build\$VERSION")
      .hasMessageContaining("kinds=CLASS")
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
    val opened = FakeAgentHeapDump(heapDump.dive)
    val heapDumps = FakeAgentHeapDumps(opens = { opened })
    tools = agentTools(heapDumps)

    val answer = call(OPEN_HEAP_DUMP, PATH to heapDump.dive.heapDumpFile.absolutePath)

    assertThat(answer.text(HEAP_DUMP)).isEqualTo(opened.heapDumpName)
    assertThat(answer.text("wasAlreadyOpen")).isEqualTo("false")
    assertThat(heapDumps.opened).containsExactly(heapDump.dive.heapDumpFile)
    // And no method, which is the other half of moving it: this is the first call of most investigations, so
    // it used to be the place the whole of it was handed over. What is here instead is the sentence naming
    // the option that has it, because an investigation that never read that option never read the method.
    assertThat(answer.keys).doesNotContain(METHOD)
    assertThat(answer.text("next")).contains(AgentCommandLine.LEAK_METHOD_OPTION)
  }

  @Test
  fun `a heap dump somebody already has open is handed over rather than opened again`() {
    // What an agent pointed at a dump does first, and it must not cost a second window and a second index of
    // the same gigabyte — nor a listing of every dump on the machine to find out that this one is already up.
    val heapDumps = FakeAgentHeapDumps(listOf(window))
    tools = agentTools(heapDumps)

    val answer = call(OPEN_HEAP_DUMP, PATH to heapDump.dive.heapDumpFile.absolutePath)

    assertThat(answer.text(HEAP_DUMP)).isEqualTo(window.heapDumpName)
    assertThat(answer.text("wasAlreadyOpen")).isEqualTo("true")
    assertThat(heapDumps.opened).isEmpty()
  }

  @Test
  fun `a dump nobody has worked on says so, and one somebody has says what to read`() {
    val untouched = call(OPEN_HEAP_DUMP, "path" to heapDump.dive.heapDumpFile.absolutePath)

    // The normal case, and what makes a count worth more here than advice: an agent that reads a zero and an
    // empty list of verdicts knows nobody has been here without spending a call to find out.
    assertThat(untouched.text("placesWithANote")).isEqualTo("0")
    assertThat(untouched.array("verdictsSetByHand")).isEmpty()
    assertThat(untouched.keys).doesNotContain("alreadyWorkedOn")

    call("take_note", "place" to hex(heapDump.holderObjectId), "text" to "The holder is a static singleton.")

    val worked = call(OPEN_HEAP_DUMP, "path" to heapDump.dive.heapDumpFile.absolutePath)

    // And only then is there something to say, which is the other half of it: this sentence used to be in
    // `agent_log`'s own description, where every agent on every dump read it.
    assertThat(worked.text("placesWithANote")).isEqualTo("1")
    assertThat(worked.text("alreadyWorkedOn")).contains("read_notes").contains(AGENT_LOG)
  }

  @Test
  fun `a heap dump already open is refused by its file name, which is a key and not a path`() {
    // `heapDumpKey` is how every other command names an open dump, so one accepted here too would make this
    // field mean a file to open or a window to find, told apart by whether that window happens to exist.
    val heapDumps = FakeAgentHeapDumps(listOf(window))
    tools = agentTools(heapDumps)

    assertThatThrownBy { call(OPEN_HEAP_DUMP, PATH to heapDump.dive.heapDumpFile.name) }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("is not an absolute path")
      // And what is open, because a bare name is an agent reaching for a key: the list is what says which.
      .hasMessageContaining(window.heapDumpName)

    assertThat(heapDumps.opened).isEmpty()
  }

  @Test
  fun `a call can name its heap dump by the path it was given`() {
    val second = temporaryFolder.applicationHoldsActivityThroughHolder("another-dump.hprof")
    second.use {
      tools = agentTools(FakeAgentHeapDumps(listOf(window, FakeAgentHeapDump(second.dive))))

      val answer = call(LIST_LEAK_GROUPS, HEAP_DUMP to heapDump.dive.heapDumpFile.absolutePath)

      // Two dumps are open, so this resolved by path or not at all: shortening a path to a file name is work
      // for an agent that has the path in front of it.
      assertThat(answer.text("objectCount").toInt()).isGreaterThan(0)
    }
  }

  @Test
  fun `a second open dump of one name is named by an increment`() {
    val other = secondDumpOfTheSameName()
    tools = agentTools(FakeAgentHeapDumps(listOf(window, other)))

    val keys = call(LIST_HEAP_DUMPS).array("heapDumps").map { it.jsonObject.text(HEAP_DUMP) }

    // `crash.hprof` pulled off two devices is two files of one name, and the name alone left the second one
    // unnameable: it resolved to the first, so a call meant for one was answered about the other and nothing
    // said so. In the order they were opened, so the first keeps the plain name. See [AgentTools.keyed].
    assertThat(keys).containsExactly(window.heapDumpName, "${window.heapDumpName}#2")
    // And the increment reaches the dump it names. Both fakes read the same heap dump, so which of them
    // recorded the read is the whole of what there is to assert here.
    call(LIST_LEAK_GROUPS, HEAP_DUMP to "${window.heapDumpName}#2")
    assertThat(other.reads).isNotEmpty()
    assertThat(window.reads).isEmpty()
    // And so does the path, which is the spelling that doesn't move — see the test below for what moves.
    call(LIST_LEAK_GROUPS, HEAP_DUMP to other.heapDumpPath)
    assertThat(window.reads).isEmpty()
  }

  @Test
  fun `closing the first of two dumps of one name leaves the other under the plain name`() {
    val other = secondDumpOfTheSameName()
    tools = agentTools(FakeAgentHeapDumps(listOf(window, other)))

    call(CLOSE_HEAP_DUMP, HEAP_DUMP to window.heapDumpName)

    // A key says which of the dumps open right now this is, so the survivor of two is the first of one. Which
    // is the cost of a key being readable: a session holding `#2` across that close is holding a key for a
    // dump that no longer has it, and the refusal below is what that gets rather than the wrong dump's answer.
    assertThat(call(LIST_HEAP_DUMPS).array("heapDumps").map { it.jsonObject.text(HEAP_DUMP) })
      .containsExactly(other.heapDumpName)
    assertThatThrownBy { call(LIST_LEAK_GROUPS, HEAP_DUMP to "${window.heapDumpName}#2") }
      .isInstanceOf(AgentRefusal::class.java)
      .hasMessageContaining("#2")
      .hasMessageContaining(other.heapDumpPath)
    // The path it was opened as still names it, which is why an answer hands that back beside the key.
    call(LIST_LEAK_GROUPS, HEAP_DUMP to other.heapDumpPath)
    assertThat(other.reads).isNotEmpty()
  }

  @Test
  fun `a path with no file at it is refused before anything is opened`() {
    val heapDumps = FakeAgentHeapDumps(listOf(window))
    tools = agentTools(heapDumps)

    assertThatThrownBy { call(OPEN_HEAP_DUMP, PATH to "/no/such/dump.hprof") }
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

    val processes = call("list_processes", "device" to "emulator-5554").array("processes").map { it.jsonObject }
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
    val dumped = FakeAgentHeapDump(heapDump.dive, path = DUMPED_PATH)
    val heapDumps = FakeAgentHeapDumps(
      open = listOf(window),
      devices = mapOf(device to listOf(process)),
      opens = { dumped }
    )
    tools = agentTools(heapDumps)

    val answer = call("dump_heap", "device" to "emulator-5554", "process" to "com.example.app")

    assertThat(answer.text(HEAP_DUMP)).isEqualTo("com.example.app.hprof")
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
      "verdict" to Verdict.EXPECTED.name,
      WHY to "Holder.INSTANCE is a static singleton, so it is meant to be in memory."
    )
  }

  /** Every group of every section, which is the list an investigation picks an object out of. */
  private fun leakGroups(): List<JsonObject> = call(LIST_LEAK_GROUPS).array("sections")
    .flatMap { it.jsonObject.array("groups") }
    .map { it.jsonObject }

  /** One section of a leaks list assembled by hand, for the cases the investigation dump hasn't got. */
  private fun section(
    kind: LeakKind,
    group: LeakGroup
  ): LeakSection = LeakSection(kind = kind, groups = listOf(group))

  /** One leak of such a section, with the single object a group is never empty of. */
  private fun group(
    title: String,
    subtitle: String?
  ): LeakGroup = LeakGroup(
    leakFingerprint = title,
    title = title,
    suspectPath = listOf(title),
    subtitle = subtitle,
    objects = listOf(
      LeakingObject(
        objectId = heapDump.activityObjectId,
        className = ACTIVITY_CLASS_NAME,
        kind = HeapObjectKind.INSTANCE,
        content = null,
        retainedSize = 1,
        retainedCount = 1,
        strength = ReachabilityStrength.STRONG,
        verdictReason = null,
        watcher = null
      )
    )
  )

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
    solved: String? = null
  ) = AgentSession(
    sessionId = sessionId,
    startedAt = Instant.parse("2026-08-26T09:15:00Z"),
    serverVersion = "1.2.3",
    file = temporaryFolder.newFile("agent-$sessionId.jsonl"),
    calls = listOf(
      recordedCall(
        tool = LIST_LEAK_GROUPS,
        heapDumpPath = heapDumpPath,
        reason = "Reading what the dump says about itself.",
        input = REFUSED_CALL_SENT,
        refusal = "$LIST_LEAK_GROUPS needs `reason`, and it was not given."
      ),
      recordedCall(
        tool = SET_VERDICT,
        heapDumpPath = heapDumpPath,
        reason = "Ruling out the object above the activity.",
        input = SOLVING_CALL_SENT,
        output = SOLVING_CALL_ANSWERED,
        outcome = solved
      )
    )
  )

  private fun recordedCall(
    tool: String,
    heapDumpPath: String,
    reason: String,
    input: String? = null,
    output: String? = null,
    refusal: String? = null,
    outcome: String? = null
  ) = AgentSessionCall(
    at = Instant.parse("2026-08-26T09:15:01Z"),
    tool = tool,
    reason = reason,
    heapDumpPath = heapDumpPath,
    place = null,
    arguments = emptyMap(),
    input = input,
    output = output,
    refusal = refusal,
    error = null,
    outcome = outcome,
    millis = 3L
  )

  /** A second dump open under the same file name, which is two files in two directories. */
  private fun secondDumpOfTheSameName(): FakeAgentHeapDump = FakeAgentHeapDump(
    heapDump.dive,
    path = File(temporaryFolder.newFolder("off-another-device"), heapDump.dive.heapDumpFile.name).absolutePath
  )

  /**
   * One call, with the two arguments a test of what a tool *answers* would otherwise spell every time.
   *
   * `reason` is on every call and `heapDumpKey` on every one that is about a heap dump, both enforced rather
   * than only asked for in the schema — and the tests that are about that are
   * [`every command requires a reason, and a tool that does not use one answers without it`] and
   * [`every call says which heap dump it is about, whether or not there is a choice`], which go through
   * [callWith] so that nothing is filled in for them.
   */
  private fun call(
    name: String,
    vararg arguments: Pair<String, String>
  ): JsonObject {
    val takes = requireNotNull(tools.byName(name)) { "There is no tool called $name" }
      .schema["properties"]?.jsonObject?.keys.orEmpty()
    val given = arguments.map { it.first }
    return callWith(
      name,
      buildJsonObject {
        arguments.forEach { (key, value) -> put(key, value) }
        if (REASON !in given) {
          put(REASON, "Testing $name")
        }
        if (HEAP_DUMP in takes && HEAP_DUMP !in given) {
          put(HEAP_DUMP, window.heapDumpName)
        }
      }
    )
  }

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

    // Spelled here rather than taken from [AgentTools], whose names for these are private: a test that read
    // them off the registry would pass a rename that every agent's own notes were written against.
    const val CLOSE_HEAP_DUMP = "close_heap_dump"
    const val HEAP_DUMP_METADATA = "heap_dump_metadata"
    const val LIST_LEAK_GROUPS = "list_leak_groups"
    const val AGENT_LOG = "agent_log"
    const val PATH_FROM_GC_ROOTS = "path_from_gc_roots"
    const val SET_VERDICT = "set_verdict"

    // The two halves of a path in an answer, which every tool that carries one carries under these names.
    const val LEAK_TRACE = "leakTrace"
    const val HUMAN_LEAK_TRACE = "humanLeakTrace"
    const val REASON = "reason"
    const val HEAP_DUMP = "heapDumpKey"
    const val PATH = "path"
    const val OBJECT = "object"
    const val WHY = "why"
    const val SOLVING_LEAK_OF = "solvingLeakOf"
    const val PLACE_LEAKS = "leaks"

    /** The two fields an investigation is worked towards, on every answer that can move them. */
    const val LEAK_SOLVED = "leakSolved"

    /** What holds the application of the fixture, as the window's path names it. */
    const val JNI_GLOBAL_ROOT = "GC root: JNI global reference"
    const val LEAK_SOLVING_PROGRESS_RATIO = "leakSolvingProgressRatio"

    /** And what they, and the rest of how far the search has got, are answered under beside a path. */
    const val INVESTIGATION = "investigation"

    /**
     * A second and a third heap dump, as paths, for the tests about more than one being open.
     *
     * Paths of files that don't exist, because what these are is a name to be answered about: two dumps open
     * in one run are two files — a second open of one file joins the first — and nothing in these tests reads
     * the second dump. See [FakeAgentHeapDump.path].
     */
    const val BUSY_PATH = "/dumps/busy.hprof"
    const val OTHER_PATH = "/dumps/other.hprof"

    /** Named after the process it came off, which is what `dump_heap` calls a dump it took. */
    const val DUMPED_PATH = "/dumps/com.example.app.hprof"

    /** The field the method used to travel in, which nothing writes now. See [AgentMethod]. */
    const val METHOD = "method"

    /**
     * What the calls of a recorded session sent and read back, as the text they were: the tool's own name
     * and then what was sent to it.
     *
     * The refused one has no output, its answer having been the refusal — which is the shape a reader of one
     * of these has to be able to tell from a call whose answer went missing.
     */
    const val REFUSED_CALL_SENT = "list_leak_groups {\n  \"heapDumpKey\": \"leak.hprof\"\n}"
    const val SOLVING_CALL_SENT =
      "set_verdict {\n  \"object\": \"0x12d368b8\",\n  \"verdict\": \"EXPECTED\"\n}"
    const val SOLVING_CALL_ANSWERED = "{\n  \"leakSolved\": true\n}"

    /**
     * One value of a field of the answer, whatever it is, as text.
     *
     * As text because that is what an agent reads a JSON value as at the far end of a socket, and because an
     * assertion that has to say which of `jsonPrimitive`, `boolean` and `long` a field is, is an assertion
     * about kotlinx rather than about the answer.
     */
    fun JsonObject.text(name: String): String =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonPrimitive.content

    fun JsonObject.obj(name: String): JsonObject =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonObject

    fun JsonObject.array(name: String): JsonArray =
      requireNotNull(this[name]) { "$name is not in $this" }.jsonArray

    /** Which arguments a schema says a call cannot be made without. See [schema]. */
    fun JsonObject.requiredArguments(): List<String> =
      array("required").map { it.jsonPrimitive.content }

    fun jsonArrayOf(vararg values: String): JsonArray =
      buildJsonArray { values.forEach { add(it) } }
  }
}
