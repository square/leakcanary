package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import shark.explorer.Place
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionCall
import shark.explorer.agent.subject
import shark.explorer.agent.verb

/**
 * Every agent that has worked on a heap dump through this app, one row each.
 *
 * Because an agent works in this window: it reads the dump the person at the machine is reading, sets the
 * verdicts they see and writes into the same notes. So what it did has to be here, in words, rather than in
 * a JSON stream a client happens to have kept — and a row of it has to lead where it went, which is what
 * makes the two of them one investigation instead of two.
 *
 * Not per heap dump, unlike the notes and the verdicts: a session is one agent's connection to this app and
 * can read whichever dumps were open. Whether a row is about *this* window's dump is what decides whether
 * clicking it goes anywhere. See [AgentLogScreen].
 */
@Composable
internal fun AgentLogsScreen(
  sessions: List<AgentSession>,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(Place.AGENT_LOGS_LABEL, style = MaterialTheme.typography.titleMedium)
      if (sessions.isEmpty()) {
        Text(NO_SESSIONS, style = MaterialTheme.typography.bodyMedium)
      }
      sessions.forEach { session ->
        val place = Place.AgentLog(session.sessionId)
        val open: (OpenIn) -> Unit = { openIn -> onOpen(place, openIn) }
        OpenTarget(open, { onCopyLink(place) }) {
          Column(Modifier.openable(open)) {
            Text(session.title(), style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
            Text(session.summary(), style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
          }
        }
      }
    }
  }
}

/**
 * What one agent did, call by call, in the order it made them.
 *
 * **Verbs and object names rather than the protocol.** What is worth reading here is whether the steps follow
 * from each other, and that is a question about what was asked and why — a screen of JSON is the same
 * information in the one form nobody reads. So a row is what the call did, what it was about, and the
 * sentence the agent gave for making it, which is its own words and not a paraphrase. An agent names objects
 * by address, and this names them the way the rest of the window does, so that a row and the tab it opens are
 * recognisably the same object.
 *
 * A row about an object of the heap dump this window has open leads to it, like every other way to an
 * object here. One about another dump says which, and leads nowhere: a session can span windows, and
 * silently landing on the wrong dump's object at the same address would be worse than not moving.
 */
@Composable
internal fun AgentLogScreen(
  session: AgentSession?,
  /** Which heap dump this window has open, which is what decides whether a row leads anywhere. */
  heapDumpFile: File,
  /**
   * What this window calls each place a call was about — `MainActivity 0x12d368b8` — for the places it has
   * been asked about yet. A place that isn't in here is drawn as the address the agent wrote, which is what
   * a call about another heap dump stays as: naming it would mean reading a dump this window doesn't have.
   */
  placeTitles: Map<Place, String>,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      if (session == null) {
        Text(NO_SUCH_SESSION, style = MaterialTheme.typography.bodyMedium)
        return@Column
      }
      Text(session.title(), style = MaterialTheme.typography.titleMedium)
      Text(session.summary(), style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      // The file, because a session outlives this window: it gets read by a script, pasted into an issue,
      // or opened in an editor months later, and none of that can happen if only this screen knows where it
      // is. Selectable for the same reason the addresses are.
      SelectionContainer {
        Text(session.file.path, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      }
      HorizontalDivider()
      if (session.calls.isEmpty()) {
        Text(NOTHING_ASKED, style = MaterialTheme.typography.bodyMedium)
      }
      session.calls.forEach { call ->
        AgentCallRow(
          call = call,
          heapDumpFile = heapDumpFile,
          title = call.place?.let { placeTitles[it] },
          onOpen = onOpen,
          onCopyLink = onCopyLink
        )
      }
    }
  }
}

/** One call: when, what it did, and why the agent said it was doing it. */
@Composable
private fun AgentCallRow(
  call: AgentSessionCall,
  heapDumpFile: File,
  /** What this window calls what the call was about, and null while it hasn't been read or can't be. */
  title: String?,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit
) {
  // A row leads somewhere only when the place it names is a place of the dump this window has open. An
  // address is an address of one heap dump, so the same one in another dump is a different object.
  val place = call.place?.takeIf { call.isAbout(heapDumpFile) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      call.at.clockTime(),
      Modifier.width(TIME_WIDTH),
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
    Column {
      if (place == null) {
        Text(call.line(title), style = MaterialTheme.typography.bodyMedium)
      } else {
        val open: (OpenIn) -> Unit = { openIn -> onOpen(place, openIn) }
        OpenTarget(open, { onCopyLink(place) }) {
          Text(
            call.line(title),
            Modifier.openable(open),
            style = MaterialTheme.typography.bodyMedium,
            color = LINK_COLOR
          )
        }
      }
      call.reason?.let { reason ->
        // The agent's own sentence, indented under what it did: read down the column of these and a session
        // either follows from itself or doesn't, which is the whole of what this screen is for.
        Text("$BECAUSE $reason", style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      }
      call.refusal?.let { refusal ->
        Text(
          "$REFUSED $refusal",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
        )
      }
    }
  }
}

/** Whether the call was about the heap dump this window has open. See [AgentLogScreen]. */
private fun AgentSessionCall.isAbout(heapDumpFile: File): Boolean =
  heapDumpPath == null || heapDumpPath == heapDumpFile.absolutePath

/**
 * What the call did and what it was about, as one line: "Described MainActivity 0x12d368b8".
 *
 * [title] is what this window calls that object, which is what a tab on it is called too — the row and the
 * tab it opens have to read the same. Without one, the address the agent wrote: a call about another heap
 * dump, or one this window hasn't read yet.
 */
private fun AgentSessionCall.line(title: String?): String =
  listOfNotNull(verb, title ?: subject).joinToString(" ")

/** What a session is called: who connected, and when. */
private fun AgentSession.title(): String = listOfNotNull(
  client ?: A_CLIENT_THAT_DID_NOT_SAY,
  startedAt?.let { "at ${it.clockTime()}" }
).joinToString(" ")

/**
 * What it did, in numbers: how many calls, how many of those were refused, and which heap dumps it read.
 *
 * The refusals are here rather than only in the session because they are the number worth seeing before
 * opening one: a session that was refused half its calls is a session where the method was being enforced,
 * which is either an agent that was made to go back and look, or a refusal message that isn't landing.
 */
private fun AgentSession.summary(): String {
  val dumps = calls.mapNotNull { it.heapDumpPath }.distinct().map { File(it).name }
  return listOfNotNull(
    "${calls.size} call(s)",
    "$refusedCount refused".takeIf { refusedCount > 0 },
    dumps.joinToString(", ").takeIf { it.isNotEmpty() },
    sessionId
  ).joinToString(" · ")
}

/** The time of day, as every line of this app's own log is stamped. See `shark.explorer.SessionLog`. */
private fun Instant.clockTime(): String = CLOCK_TIME.format(this)

private val CLOCK_TIME: DateTimeFormatter =
  DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Wide enough for the clock and no wider, so that the verbs line up down the screen. */
private val TIME_WIDTH = 60.dp

private const val BECAUSE = "because:"

private const val REFUSED = "Refused:"

private const val A_CLIENT_THAT_DID_NOT_SAY = "An agent"

private const val NO_SESSIONS =
  "No agent has connected to this app yet. Hand a heap dump to one by pointing its MCP client at Shark " +
    "Explorer, and everything it does lands here."

private const val NOTHING_ASKED =
  "This agent connected and asked nothing before it went away."

private const val NO_SUCH_SESSION =
  "There is no session with that name. A link to one leads to the file it was written to, which is kept " +
    "until a hundred newer sessions have pushed it out."
