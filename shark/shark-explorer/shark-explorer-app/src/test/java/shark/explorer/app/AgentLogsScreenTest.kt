package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import java.time.Instant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceHeapDumps
import shark.explorer.Place
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionCall
import shark.explorer.exactHexObjectId
import shark.explorer.hexObjectId

/**
 * What an agent did, in the window it did it in.
 *
 * An investigation an agent ran and one a person ran are the same investigation: it reads this heap dump,
 * sets the verdicts they see and writes the same notes. So what it did is read here in words, and **a row
 * leads where the call went** — which is what these tests are about, along with the one case where it must
 * not: a call about another heap dump, whose addresses mean nothing here.
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
    explorerUiTest {
      openAgentLogs(listOf(session(calls = listOf(call()))))

      onNodeWithText(CLIENT, substring = true).assertIsDisplayed()
      onNodeWithText(CLIENT, substring = true).performClick()

      // The verb, the object named the way a tab on it is named, and the agent's own sentence for why it
      // asked: no JSON and no bare address on any of it.
      waitUntilAtLeastOneExists(hasText(REASON, substring = true), OPEN_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(hasText(describedRow()), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a refused call says so, and still says what it was about`() {
    explorerUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(tool = "conclude", refusal = REFUSAL)))))
      onNodeWithText(CLIENT, substring = true).performClick()

      waitUntilAtLeastOneExists(hasText(REFUSAL, substring = true), OPEN_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(
        hasText("Concluded about ${activityName()}"),
        OPEN_TIMEOUT_MILLIS
      )
    }
  }

  @Test fun `the row that concluded says which reference it came to`() {
    explorerUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(tool = "conclude", outcome = FAULTY_REFERENCE)))))
      onNodeWithText(CLIENT, substring = true).performClick()

      // The row anybody scrolling a session is looking for: what the agent asked, and what it came to, on
      // one line — so that finding the answer isn't reading every reason down the screen.
      waitUntilAtLeastOneExists(
        hasText("Concluded about ${activityName()} → $FAULTY_REFERENCE"),
        OPEN_TIMEOUT_MILLIS
      )
    }
  }

  @Test fun `a row leads to the object the call was about`() {
    explorerUiTest {
      openAgentLogs(listOf(session(calls = listOf(call()))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(REASON, substring = true), OPEN_TIMEOUT_MILLIS)

      waitUntilAtLeastOneExists(hasText(describedRow()), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(describedRow()).performClick()

      // What the inspectors made of the object the agent was reading, which is the whole promise of the
      // screen: reading what it did and going to look at it are one move.
      waitUntilAtLeastOneExists(hasText("mDestroyed", substring = true), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a call about another heap dump is read here and leads nowhere`() {
    explorerUiTest {
      openAgentLogs(listOf(session(calls = listOf(call(heapDumpPath = "/dumps/another.hprof")))))
      onNodeWithText(CLIENT, substring = true).performClick()
      waitUntilAtLeastOneExists(hasText(REASON, substring = true), OPEN_TIMEOUT_MILLIS)

      // An address is an address of one heap dump, so the same one here is a different object — or no
      // object at all. Read it as the agent wrote it, and don't follow it.
      onNodeWithText("Described ${hex(activityObjectId())}").assertHasNoClickAction()
    }
  }

  @Test fun `a window no agent has connected to says what would put something here`() {
    explorerUiTest {
      openAgentLogs(emptyList())

      onNodeWithText(NO_AGENT_YET, substring = true).assertIsDisplayed()
    }
  }

  /** Opens the window on [leakyHeapDump] with [sessions] as the agents that have worked through it. */
  private fun ComposeUiTest.openAgentLogs(sessions: List<AgentSession>) {
    setContent {
      MaterialTheme {
        ExplorerApp(
          heapDumpFile = heapDump.file,
          onHeapDumpChosen = { _, _ -> },
          // Given rather than read off this machine: the sessions under whoever is running the tests are
          // their investigations, and none of this window's business.
          agentSessions = { sessions },
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
    refusal = refusal,
    outcome = outcome,
    millis = 12L
  )

  private fun activityObjectId() = heapDump.activityObjectIds.first()

  /** How the window names the activity: the same title the tab a row opens carries. */
  private fun activityName() =
    "${LEAKING_ACTIVITY_CLASS_NAME.substringAfterLast('.')} ${hexObjectId(activityObjectId())}"

  private fun describedRow() = "Described ${activityName()}"

  private fun hex(objectId: Long) = exactHexObjectId(objectId)

  /**
   * A button on the row of screens an open heap dump can be read through, as against the tab of the same
   * name that clicking it opens. See [ExplorerAppTest] for why the role is what tells them apart.
   */
  private fun ComposeUiTest.screenButton(label: String) = onNode(hasText(label) and isButton())

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  private companion object {

    const val SESSION_ID = "1a2b3c4d"
    const val CLIENT = "claude-code 9.9.9"
    const val REASON = "Checking whether this activity is really destroyed."
    const val REFUSAL = "3 step(s) have no verdict"
    const val FAULTY_REFERENCE = "Holder.activity"
    const val NO_AGENT_YET = "No agent has connected"

    val STARTED_AT: Instant = Instant.parse("2026-08-25T18:19:48.035Z")

    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
