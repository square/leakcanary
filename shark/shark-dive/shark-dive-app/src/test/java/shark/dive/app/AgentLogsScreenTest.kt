package shark.dive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.Adb
import shark.dive.AdbOutput
import shark.dive.DeepLink
import shark.dive.DeviceHeapDumps
import shark.dive.HeapDominatorTreemap
import shark.dive.Place
import shark.dive.agent.AgentSession
import shark.dive.agent.AgentSessionCall
import shark.dive.exactHexObjectId
import shark.dive.hexObjectId

/**
 * What an agent did, in the window it did it in.
 *
 * An investigation an agent ran and one a person ran are the same investigation: it reads this heap dump,
 * sets the verdicts they see and writes the same notes. So what it did is read here in words, and **a row
 * leads where the call went** — which is what these tests are about, along with the boundary of that: a
 * window is one heap dump, so the agents listed in it are the ones that read that dump, and the rest are
 * reached by opening theirs.
 */
@OptIn(ExperimentalTestApi::class)
class AgentLogsScreenTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  private lateinit var heapDump: LeakyHeapDump

  @Before fun setUp() {
    heapDump = testFolder.leakyHeapDump()
  }

  @Test fun `the button leads to the sessions, and a session to what the agent did`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call()))))

      onNodeWithText(CLIENT, substring = true).assertIsDisplayed()
      onNodeWithText(CLIENT, substring = true).performClick()

      // The verb, the object named the way a tab on it is named, and the agent's own sentence for why it
      // asked: no JSON and no bare address on any of it.
      waitUntilAtLeastOneExists(hasText(REASON, substring = true), OPEN_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(hasText(LOOKED_AT), OPEN_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(hasText(activityName()), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `the object a call was about is the link, and the verb is not`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call()))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(activityName()), OPEN_TIMEOUT_MILLIS)

      // What a reader wants to go and look at is the object, so that is the whole of what moves the window:
      // a row where clicking the word "Looked at" navigates is a row with a hand cursor over prose. What the
      // verb does instead is unfold the call in place, which leaves the tab where it was.
      onNodeWithText(activityName()).assertHasClickAction()
      onNodeWithText(LOOKED_AT).performClick()
      selectedTab().assertTextContains(Place.AgentLog(SESSION_ID).title)
    }
  }

  @Test fun `a call that named nothing links the words for where it went, and not the verb`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(leaksCall()))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(LISTED_THE), OPEN_TIMEOUT_MILLIS)

      // "Listed the leaks" is a sentence about the leaks screen, so *leaks* is the link and what comes
      // before it is prose. Linking the whole of it was underlining a verb; naming the place from the tool
      // instead read as "Listed the leaks Leaks". The verb unfolds the call and goes nowhere.
      onNodeWithText(LISTED_THE).performClick()
      selectedTab().assertTextContains(Place.AgentLog(SESSION_ID).title)
      onNodeWithText(LEAKS).assertHasClickAction()
      onNodeWithText(LEAKS).performClick()

      // The leaks screen, named by the reference each leak is: the same screen the agent was reading.
      waitUntilAtLeastOneExists(hasText(ACTIVITY_LEAK_NAME, substring = true), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a call that read the tree from its root leads to the whole heap dump`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(wholeDumpCall(tool = "dominator_tree")))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(DOMINATOR_TREE), OPEN_TIMEOUT_MILLIS)

      // Reading the tree from its root is what this window opens on, so a row saying an agent did it leads
      // there. Anything an agent can do that a person can do here is a row that goes where they went.
      // The tab is on the agent's log until the row moves it — which the verb does not do, it being the fold
      // over the call — and then on the tree from its root, which is where a window opens and what the row
      // said the agent read.
      onNodeWithText(READ_THE).performClick()
      selectedTab().assertTextContains(Place.AgentLog(SESSION_ID).title)
      onNodeWithText(DOMINATOR_TREE).performClick()

      waitUntilAtLeastOneExists(
        hasText(HeapDominatorTreemap.ROOT_LABEL) and isTab() and isSelected(),
        OPEN_TIMEOUT_MILLIS
      )
    }
  }

  @Test fun `a search of the whole heap dump leads to the list of every object`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(wholeDumpCall(tool = "find_objects")))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(BIGGEST_OBJECTS), OPEN_TIMEOUT_MILLIS)

      // A search with no class name is the biggest objects of the dump, which is the object list unfiltered
      // — and reads as that rather than as "Searched for" with nothing after it.
      onNode(hasText(Place.OBJECTS_LABEL) and isTab()).assertDoesNotExist()
      onNodeWithText(BIGGEST_OBJECTS).performClick()

      waitUntilAtLeastOneExists(hasText(Place.OBJECTS_LABEL) and isTab(), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a row unfolds onto what the call sent and what it read back`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call()))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(LOOKED_AT), OPEN_TIMEOUT_MILLIS)

      // Behind the verb until asked for, because a session read straight through is sentences: the exchange
      // is what somebody opens one call for when the sentences stopped explaining the next step.
      onNodeWithText(SENT, substring = true).assertDoesNotExist()
      onNodeWithText(LOOKED_AT).performClick()

      // Both halves, whole, and as they were: this is the one thing on the screen that is not this window's
      // reading of what happened.
      waitUntilAtLeastOneExists(hasText(SENT), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(ANSWERED).assertIsDisplayed()
    }
  }

  @Test fun `a refused call unfolds onto what it sent, its answer being the refusal on the row`() {
    diveUiTest {
      openAgentLogs(
        listOf(session(calls = listOf(call(tool = "conclude", output = null, refusal = REFUSAL))))
      )
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(CONCLUDED_ABOUT), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(CONCLUDED_ABOUT).performClick()

      // The refusal is already on the row in full, so unfolding adds what was sent and does not print the
      // same sentence a second time in a box.
      waitUntilAtLeastOneExists(hasText(SENT), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$REFUSED $REFUSAL").assertIsDisplayed()
    }
  }

  @Test fun `a session recorded before the exchange was kept says so rather than unfolding onto nothing`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(input = null, output = null)))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(LOOKED_AT), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(LOOKED_AT).performClick()

      // A gap where the exchange should be reads as an app that lost it; this is the app saying it was never
      // given one, which is a thing about that session rather than about this window.
      waitUntilAtLeastOneExists(hasText(NOTHING_KEPT, substring = true), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a refused call says so, and still says what it was about`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(tool = "conclude", refusal = REFUSAL)))))
      onNodeWithText(CLIENT, substring = true).performClick()

      waitUntilAtLeastOneExists(hasText(REFUSAL, substring = true), OPEN_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(hasText(CONCLUDED_ABOUT), OPEN_TIMEOUT_MILLIS)
      // Refused, and still leading to the object it was refused about: the refusals are the half of a
      // session worth reading afterwards.
      onNodeWithText(activityName()).assertHasClickAction()
    }
  }

  @Test fun `the row that concluded says which reference it came to`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(tool = "conclude", outcome = FAULTY_REFERENCE)))))
      onNodeWithText(CLIENT, substring = true).performClick()

      // The row anybody scrolling a session is looking for: what the agent asked, and what it came to, on
      // one line — so that finding the answer isn't reading every reason down the screen.
      waitUntilAtLeastOneExists(hasText(CONCLUDED_ABOUT), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(activityName()).assertIsDisplayed()
      onNodeWithText("→ $FAULTY_REFERENCE").assertIsDisplayed()
    }
  }

  @Test fun `a row leads to the object the call was about`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call()))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(REASON, substring = true), OPEN_TIMEOUT_MILLIS)

      waitUntilAtLeastOneExists(hasText(activityName()), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(activityName()).performClick()

      // What the inspectors made of the object the agent was reading, which is the whole promise of the
      // screen: reading what it did and going to look at it are one move.
      waitUntilAtLeastOneExists(hasText("mDestroyed", substring = true), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `an agent that worked on another heap dump is listed apart, and opens in a window of that dump`() {
    val otherHeapDump = testFolder.newFile("another.hprof")
    var opened: Pair<File, Place>? = null
    diveUiTest {
      openAgentLogs(
        sessions = listOf(session(calls = listOf(call(heapDumpPath = otherHeapDump.absolutePath)))),
        onOpenHeapDump = { file, place -> opened = file to place }
      )

      // Not among this window's agents, because a window is a heap dump and this one read another: its
      // addresses are addresses of that file. Still reachable, since a dump handed to an agent is usually
      // one nobody has open.
      onNodeWithText(NO_AGENT_YET, substring = true).assertIsDisplayed()
      // The dump this window has open is the first group and says so; the other is a group of its own,
      // headed with the file name a reader recognises rather than with a path.
      onNodeWithText("${heapDump.file.name} (this heap dump)").assertIsDisplayed()
      onNodeWithText(otherHeapDump.name).assertIsDisplayed()
      onNodeWithText(CLIENT, substring = true).performClick()
    }

    // Its own log, in a window of its own heap dump, rather than read here against the wrong one.
    assertThat(opened).isEqualTo(otherHeapDump to Place.AgentLog(SESSION_ID))
  }

  @Test fun `a session about another heap dump is worth sending as well as opening`() {
    val otherHeapDump = testFolder.newFile("another.hprof")
    val copied = mutableListOf<String>()
    diveUiTest {
      openAgentLogs(
        sessions = listOf(session(calls = listOf(call(heapDumpPath = otherHeapDump.absolutePath)))),
        copyToClipboard = { copied += it }
      )

      onNodeWithText(CLIENT, substring = true).performMouseInput { rightClick() }
      onNodeWithText(COPY_LINK).performClick()
    }

    // That dump, and no window id: this window is not one of that file's, so there is none to prefer — and
    // a link names the heap dump, which is what makes a row about somebody else's dump something to send
    // rather than only something to click. See [DeepLink].
    assertThat(copied).containsExactly(DeepLink(otherHeapDump, Place.AgentLog(SESSION_ID)).toUri())
  }

  @Test fun `a call that went on to another heap dump reads as the address, and leads to that dump`() {
    val otherHeapDump = testFolder.newFile("another.hprof")
    var opened: Pair<File, Place>? = null
    diveUiTest {
      openAgentLogs(
        sessions = listOf(
          session(calls = listOf(call(), call(heapDumpPath = otherHeapDump.absolutePath)))
        ),
        onOpenHeapDump = { file, place -> opened = file to place }
      )
      // Listed under both dumps, because it is one agent's work on each of them; the group read here is
      // this window's, which is the first.
      thisWindowsSession().performClick()
      waitUntilAtLeastOneExists(hasText(activityName()), OPEN_TIMEOUT_MILLIS)

      // The address as the agent wrote it, and the file it means something in: this window has never read
      // that dump, so what the number stands for there is not a question it can answer.
      onNodeWithText("in ${otherHeapDump.name}").assertIsDisplayed()
      onNodeWithText(hex(activityObjectId())).performClick()
    }

    // Going there means opening that dump, where the same address is that dump's object.
    assertThat(opened).isEqualTo(otherHeapDump to Place.Object(activityObjectId()))
  }

  @Test fun `the call that asked which heap dumps are open unfolds into them`() {
    val otherHeapDump = testFolder.newFile("another.hprof")
    var opened: Pair<File, Place>? = null
    diveUiTest {
      openAgentLogs(
        sessions = listOf(session(calls = listOf(openHeapDumpsCall(otherHeapDump)))),
        onOpenHeapDump = { file, place -> opened = file to place }
      )
      thisWindowsSession().performClick()
      waitUntilAtLeastOneExists(hasText(ASKED_WHICH_ARE_OPEN), OPEN_TIMEOUT_MILLIS)

      // Behind the verb until somebody asks, because what this one came back with is a list where every
      // other row of a session is a sentence.
      onNodeWithText(otherHeapDump.name).assertDoesNotExist()
      onNodeWithText(ASKED_WHICH_ARE_OPEN).performClick()

      // The dump this window has open says so and leads nowhere — it is already here — and the other one is
      // a window away, which is what makes a list of what *was* open worth keeping.
      waitUntilAtLeastOneExists(hasText(thisHeapDumpRow()), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(thisHeapDumpRow()).assertHasNoClickAction()
      onNodeWithText(otherHeapDump.name).performClick()
    }

    assertThat(opened).isEqualTo(otherHeapDump to Place.wholeHeapDump())
  }

  @Test fun `a session about a heap dump that has gone is read here, there being no window for it`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(heapDumpPath = DELETED_HEAP_DUMP)))))

      // Headed as gone, which is the answer to why the objects in it have no names: an address is an
      // address of a file, and that file isn't here.
      onNodeWithText("deleted.hprof ($MISSING)").assertIsDisplayed()
      onNodeWithText(CLIENT, substring = true).performClick()

      // And read here rather than nowhere. There is no window that could name those addresses, so what is
      // left is what the agent said — the verbs, the reasons and the refusals — which is worth reading.
      waitUntilAtLeastOneExists(hasText(REASON, substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("in deleted.hprof").assertIsDisplayed()
    }
  }

  @Test fun `a call about a heap dump that has gone leads nowhere`() {
    diveUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(), call(heapDumpPath = DELETED_HEAP_DUMP)))))
      thisWindowsSession().performClick()
      waitUntilAtLeastOneExists(hasText(activityName()), OPEN_TIMEOUT_MILLIS)

      // Still worth reading, and there is nothing to open: a session outlives the heap dumps it was about.
      onNodeWithText("in deleted.hprof").assertIsDisplayed()
      onNodeWithText(hex(activityObjectId())).assertHasNoClickAction()
    }
  }

  @Test fun `a window no agent has connected to says what would put something here`() {
    diveUiTest {
      openAgentLogs(emptyList())

      onNodeWithText(NO_AGENT_YET, substring = true).assertIsDisplayed()
    }
  }

  /** Opens the window on [leakyHeapDump] with [sessions] as the agents that have worked through it. */
  private fun ComposeUiTest.openAgentLogs(
    sessions: List<AgentSession>,
    onOpenHeapDump: (File, Place) -> Unit = { _, _ -> },
    copyToClipboard: (String) -> Unit = {}
  ) {
    setContent {
      MaterialTheme {
        DiveApp(
          heapDumpFile = heapDump.file,
          onHeapDumpChosen = { _, _ -> },
          // Given rather than read off this machine: the sessions under whoever is running the tests are
          // their investigations, and none of this window's business.
          agentSessions = { sessions },
          // Where a row about another heap dump goes, which is a question about every window of the run and
          // so answered outside this one. See [DiveWindowTest].
          onOpenHeapDump = onOpenHeapDump,
          copyToClipboard = copyToClipboard,
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
    screenButton(Place.AGENT_LOGS_LABEL).performClick()
  }

  private fun session(calls: List<AgentSessionCall>) = AgentSession(
    sessionId = SESSION_ID,
    startedAt = STARTED_AT,
    client = CLIENT,
    serverVersion = "1.2.3",
    file = File(testFolder.root, "sessions/agent-$SESSION_ID.jsonl"),
    calls = calls
  )

  /** The call every test here is about: the agent reading one of the destroyed activities. */
  private fun call(
    tool: String = "describe_object",
    heapDumpPath: String = heapDump.file.absolutePath,
    input: String? = SENT,
    output: String? = ANSWERED,
    refusal: String? = null,
    outcome: String? = null
  ) = AgentSessionCall(
    at = STARTED_AT,
    tool = tool,
    reason = REASON,
    windowId = "zvphq4r3",
    heapDumpPath = heapDumpPath,
    place = Place.Object(activityObjectId()),
    arguments = mapOf("object" to hex(activityObjectId())),
    input = input,
    output = output,
    refusal = refusal,
    outcome = outcome,
    millis = 12L
  )

  /**
   * The session as this window's group of them lists it, which is the first: a session that read two dumps is
   * listed under both, and only this window's group is read here.
   */
  private fun ComposeUiTest.thisWindowsSession() = onAllNodesWithText(CLIENT, substring = true)[0]

  /** The one call that names nothing: the leaks screen is the whole of what it was about. */
  private fun leaksCall() = AgentSessionCall(
    at = STARTED_AT,
    tool = "list_leaks",
    reason = REASON,
    windowId = "zvphq4r3",
    heapDumpPath = heapDump.file.absolutePath,
    place = Place.Leaks(),
    arguments = emptyMap(),
    input = SENT,
    output = ANSWERED,
    refusal = null,
    outcome = null,
    millis = 12L
  )

  /**
   * A call that named nothing in the dump and so was about the whole of it: the tree from its root, or the
   * list of every object. See `AgentTools.placeOrNull`.
   */
  private fun wholeDumpCall(tool: String) = AgentSessionCall(
    at = STARTED_AT,
    tool = tool,
    reason = REASON,
    windowId = "zvphq4r3",
    heapDumpPath = heapDump.file.absolutePath,
    place = if (tool == "find_objects") Place.Objects() else Place.wholeHeapDump(),
    arguments = emptyMap(),
    input = SENT,
    output = ANSWERED,
    refusal = null,
    outcome = null,
    millis = 12L
  )

  /** The first call of most sessions: which dumps are open, answered with the ones that were. */
  private fun openHeapDumpsCall(otherHeapDump: File) = AgentSessionCall(
    at = STARTED_AT,
    tool = "open_heap_dumps",
    reason = REASON,
    windowId = "zvphq4r3",
    heapDumpPath = heapDump.file.absolutePath,
    // Nowhere to go: this one asks the app rather than a heap dump. What it came back with is where it goes.
    place = null,
    arguments = emptyMap(),
    input = SENT,
    output = ANSWERED,
    refusal = null,
    outcome = null,
    openHeapDumps = listOf(heapDump.file.absolutePath, otherHeapDump.absolutePath),
    millis = 12L
  )

  /** How the dump this window has open reads in a list of the dumps that were open. */
  private fun thisHeapDumpRow() = "${heapDump.file.name} (this heap dump)"

  private fun activityObjectId() = heapDump.activityObjectIds.first()

  /** How the window names the activity: the same title the tab a row opens carries. */
  private fun activityName() =
    "${LEAKING_ACTIVITY_CLASS_NAME.substringAfterLast('.')} ${hexObjectId(activityObjectId())}"

  private fun hex(objectId: Long) = exactHexObjectId(objectId)

  /**
   * A button on the row of screens an open heap dump can be read through, as against the tab of the same
   * name that clicking it opens. See [DiveAppTest] for why the role is what tells them apart.
   */
  private fun ComposeUiTest.screenButton(label: String) = onNode(hasText(label) and isButton())

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  /** Where the window is, since a row of this screen moves the tab it is read in. */
  private fun ComposeUiTest.selectedTab() = onNode(isTab() and isSelected())

  /** The tab a row opened, as against the button of the same name that would have opened it too. */
  private fun isTab(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  private companion object {

    const val SESSION_ID = "1a2b3c4d"
    const val CLIENT = "claude-code 9.9.9"
    const val REASON = "Checking whether this activity is really destroyed."
    const val REFUSAL = "3 step(s) have no verdict"

    /** In front of a refusal on a row, which is where the answer to a refused call already is. */
    const val REFUSED = "Refused:"

    /**
     * What a call sent and what came back, as the text they were: several lines of formatted JSON, which is
     * what reaches the model and so what a session keeps. See [shark.dive.agent.AgentSessionCall.input].
     */
    const val SENT = "{\n  \"object\": \"0x12d368b8\",\n  \"reason\": \"$REASON\"\n}"
    const val ANSWERED = "{\n  \"object\": \"0x12d368b8\",\n  \"verdict\": \"UNKNOWN\"\n}"

    /** And what a row of a session recorded before either of them was kept unfolds onto. */
    const val NOTHING_KEPT = "recorded before Shark Dive kept what was sent and answered"
    const val FAULTY_REFERENCE = "Holder.activity"
    const val NO_AGENT_YET = "No agent has worked on this heap dump"

    /** A heap dump a session was about and nobody has any more. */
    const val DELETED_HEAP_DUMP = "/dumps/deleted.hprof"

    /** After the name of one, wherever this screen names a dump that isn't there. */
    const val MISSING = "missing"

    /** The verbs the rows read as, which are [shark.dive.agent.verb]'s and not this screen's. */
    const val LOOKED_AT = "Looked at"
    const val CONCLUDED_ABOUT = "Concluded about"
    const val ASKED_WHICH_ARE_OPEN = "Asked which heap dumps are open"

    /**
     * And the verbs of the calls that named nothing, which stop where the link starts: the words after each
     * of these are [shark.dive.agent.screen]'s. See [LEAKS], [DOMINATOR_TREE] and [BIGGEST_OBJECTS].
     */
    const val LISTED_THE = "Listed the"
    const val READ_THE = "Read the"

    const val LEAKS = "leaks"
    const val DOMINATOR_TREE = "dominator tree"
    const val BIGGEST_OBJECTS = "biggest objects"

    val STARTED_AT: Instant = Instant.parse("2026-08-25T18:19:48.035Z")

    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
