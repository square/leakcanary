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
import shark.SharkLog
import shark.explorer.Place
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionCall
import shark.explorer.agent.subject
import shark.explorer.agent.verb

/**
 * Every agent that has worked on **this** heap dump, one row each, and a way to the ones that worked on
 * another.
 *
 * Because an agent works in this window: it reads the dump the person at the machine is reading, sets the
 * verdicts they see and writes into the same notes. So what it did has to be here, in words, rather than in
 * a JSON stream a client happens to have kept — and a row of it has to lead where it went, which is what
 * makes the two of them one investigation instead of two.
 *
 * Per heap dump, like the notes and the verdicts, because a window is a heap dump: a session listed in the
 * wrong window is one whose addresses mean nothing here. The sessions that read other dumps are still worth
 * reaching from here — an agent is usually handed a dump nobody has open yet — and each of those is opened
 * in a window of *its* dump rather than read in this one. There is no window that is not a heap dump for
 * them to be listed in on their own.
 */
@Composable
internal fun AgentLogsScreen(
  sessions: List<AgentSession>,
  /** Which heap dump this window has open, which is what decides which sessions are this window's. */
  heapDumpFile: File,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  /**
   * Where a session about another heap dump goes: that dump, in the window that has it or one of its own.
   *
   * Nothing by default, because routing this is a question about every window of the run and a screen
   * composed without an answer must not silently look like a screen whose rows lead somewhere.
   */
  onOpenHeapDump: (File, Place) -> Unit = { file, place ->
    SharkLog.d { "Nothing here to open $place of $file with" }
  },
  modifier: Modifier = Modifier
) {
  val here = sessions.filter { heapDumpFile.absolutePath in it.heapDumpPaths }
  // Which leaves a session that read no heap dump at all — a client that connected and asked nothing — with
  // the ones about other dumps, since it is not about this one either.
  val elsewhere = sessions - here.toSet()
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(Place.AGENT_LOGS_LABEL, style = MaterialTheme.typography.titleMedium)
      if (here.isEmpty()) {
        Text(NO_SESSIONS, style = MaterialTheme.typography.bodyMedium)
      }
      here.forEach { session ->
        val place = Place.AgentLog(session.sessionId)
        val open: (OpenIn) -> Unit = { openIn -> onOpen(place, openIn) }
        OpenTarget(open, { onCopyLink(place) }) {
          Column(Modifier.openable(open)) {
            Text(session.title(), style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
            Text(session.summary(), style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
          }
        }
      }
      if (elsewhere.isNotEmpty()) {
        HorizontalDivider()
        Text(OTHER_HEAP_DUMPS, style = MaterialTheme.typography.titleMedium)
        elsewhere.forEach { session -> OtherHeapDumpSessionRow(session, onOpenHeapDump) }
      }
    }
  }
}

/**
 * One agent that worked on another heap dump: what it did, and that dump to open it in.
 *
 * Not opened here. An address is an address of one heap dump, so a session read against the wrong one is a
 * screen of rows that name other objects than the ones the agent saw — which is the whole reason this list is
 * per dump. A session that read no dump at all, or one whose dump has been deleted, has nowhere to be opened
 * and says which file it wanted.
 */
@Composable
private fun OtherHeapDumpSessionRow(
  session: AgentSession,
  onOpenHeapDump: (File, Place) -> Unit
) {
  val opens = session.heapDumpPaths.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }
  val title = session.title()
  val summary = session.summary()
  if (opens == null) {
    Column {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
    }
    return
  }
  // No tab to choose and no link to copy: what a link names is a window, and the window this session was
  // read in belongs to a run that has usually ended. The heap dump is what outlived it.
  val open = { onOpenHeapDump(opens, Place.AgentLog(session.sessionId)) }
  Column(Modifier.openable { open() }) {
    Text(title, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
    Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
  }
}

/**
 * What one agent did, call by call, in the order it made them.
 *
 * **Verbs and object names rather than the protocol.** What is worth reading here is whether the steps follow
 * from each other, and that is a question about what was asked and why — a screen of JSON is the same
 * information in the one form nobody reads. So a row is what the call did, what it was about, and the
 * sentence the agent gave for making it, which is its own words and not a paraphrase.
 *
 * **An agent names objects by address, and a row names them the way the rest of the window does**, so that a
 * row and the tab it opens are recognisably the same object. Which is a read of the heap dump this window has
 * open — the same read that names a tab — and it is why this screen is reached from the sessions about *this*
 * dump: a window can only speak for the dump it has. See [AgentLogsScreen] and [placeTitles].
 *
 * **And every row that names a place leads to it.** The exception is the call of a session that went on to
 * another heap dump, which names that dump and opens it: a session is one agent's connection and can read as
 * many dumps as were open, so a row leading nowhere would be the app showing somebody what an agent looked at
 * and then declining to show them the thing.
 */
@Composable
internal fun AgentLogScreen(
  session: AgentSession?,
  /** Which heap dump this window has open, which is what decides whether a row moves this window. */
  heapDumpFile: File,
  /** What this window calls the places the agent asked about, for the calls about its own heap dump. */
  placeTitles: Map<Place, String>,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  /** Where a row about another heap dump goes. See [AgentLogsScreen]. */
  onOpenHeapDump: (File, Place) -> Unit = { file, place ->
    SharkLog.d { "Nothing here to open $place of $file with" }
  },
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
          placeTitles = placeTitles,
          onOpen = onOpen,
          onCopyLink = onCopyLink,
          onOpenHeapDump = onOpenHeapDump
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
  placeTitles: Map<Place, String>,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  onOpenHeapDump: (File, Place) -> Unit
) {
  val place = call.place
  // Which heap dump the row is about when it isn't this window's, and null when it is. An address is an
  // address of one dump, so the same number in another one is another object: this window cannot name it or
  // go there, and the dump that can has to be opened first.
  val elsewhere = call.otherHeapDumpOrNull(heapDumpFile)
  // And whether that is still possible. A session outlives the heap dumps it was about, so a row naming one
  // that has been deleted says which and leads nowhere.
  val opens = elsewhere?.takeIf { it.isFile }
  // Named for a call about this window's own heap dump, and not for one about another: this window has never
  // read that file, so what a number in it stands for is not a question it can answer.
  val named = if (elsewhere == null) place?.let { placeTitles[it] } else null
  val line = call.line(named, elsewhere)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      call.at.clockTime(),
      Modifier.width(TIME_WIDTH),
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
    Column {
      when {
        // Asking which heap dumps are open is about the app rather than about one of them.
        place == null -> Text(line, style = MaterialTheme.typography.bodyMedium)
        opens != null -> Text(
          line,
          Modifier.openable { onOpenHeapDump(opens, place) },
          style = MaterialTheme.typography.bodyMedium,
          color = LINK_COLOR
        )
        // The heap dump it names is gone, so there is nothing left to open it on.
        elsewhere != null -> Text(line, style = MaterialTheme.typography.bodyMedium)
        else -> {
          val open: (OpenIn) -> Unit = { openIn -> onOpen(place, openIn) }
          OpenTarget(open, { onCopyLink(place) }) {
            Text(
              line,
              Modifier.openable(open),
              style = MaterialTheme.typography.bodyMedium,
              color = LINK_COLOR
            )
          }
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

/**
 * The heap dump the call was about when it is one this window hasn't got open, and null when it has.
 *
 * Null as well for a call about no heap dump at all, which is asking the app which dumps are open. Whether
 * the file is still there is a separate question, and the one that decides whether the row leads anywhere:
 * a deleted dump is worth naming and impossible to open. See [AgentLogScreen].
 */
private fun AgentSessionCall.otherHeapDumpOrNull(heapDumpFile: File): File? = heapDumpPath
  ?.takeIf { it != heapDumpFile.absolutePath }
  ?.let { File(it) }

/**
 * What the call did and what it was about, as one line: "Described MainActivity 0x12d368b8".
 *
 * With what it came to on the end where there is one — "Concluded about MainActivity → MainActivity$2.this$0"
 * — since the row that says what was concluded is the row anybody scrolling a session is looking for.
 *
 * And with [otherHeapDump] named at the end of a row about a dump this window hasn't got open, because
 * clicking that row opens a heap dump: which one is a thing to know before rather than after. Those are the
 * rows with no [named] to show, where the address the agent wrote stands for itself.
 */
private fun AgentSessionCall.line(
  named: String?,
  otherHeapDump: File?
): String = listOfNotNull(
  verb,
  named ?: subject,
  outcome?.let { "$LEADS_TO $it" },
  otherHeapDump?.let { "$IN ${it.name}" }
).joinToString(" ")

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
  val dumps = heapDumpPaths.map { File(it).name }
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

/** In front of what a call came to, which reads as the row's own arrow rather than as a word. */
private const val LEADS_TO = "→"

/** And in front of the heap dump a row is about, for the rows that are about another one. */
private const val IN = "in"

private const val REFUSED = "Refused:"

private const val A_CLIENT_THAT_DID_NOT_SAY = "An agent"

/** The sessions that read another dump, which open in a window of that dump. See [AgentLogsScreen]. */
private const val OTHER_HEAP_DUMPS = "Other heap dumps"

private const val NO_SESSIONS =
  "No agent has worked on this heap dump. Hand it to one by pointing its MCP client at Shark Explorer, and " +
    "everything it does lands here."

private const val NOTHING_ASKED =
  "This agent connected and asked nothing before it went away."

private const val NO_SUCH_SESSION =
  "There is no session with that name. A link to one leads to the file it was written to, which is kept " +
    "until a hundred newer sessions have pushed it out."
