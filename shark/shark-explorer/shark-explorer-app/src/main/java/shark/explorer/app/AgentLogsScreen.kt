package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
 * Every agent that has worked through this app, under the heap dump it worked on, this window's first.
 *
 * Because an agent works in this window: it reads the dump the person at the machine is reading, sets the
 * verdicts they see and writes into the same notes. So what it did has to be here, in words, rather than in
 * a JSON stream a client happens to have kept — and a row of it has to lead where it went, which is what
 * makes the two of them one investigation instead of two.
 *
 * **Grouped by heap dump, because a session only means anything against one.** An address is an address of
 * one dump, so a session read in the wrong window is a screen of rows naming other objects — which is why a
 * group that isn't this window's opens in a window of *its* dump instead of being read here. This window's
 * group comes first and says so; the dump an agent was handed is usually one nobody has open, so the rest are
 * as much of the screen as it is.
 */
@Composable
internal fun AgentLogsScreen(
  sessions: List<AgentSession>,
  /** Which heap dump this window has open, which is the group that is read here rather than opened. */
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
  val groups = sessions.byHeapDump(heapDumpFile)
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(Place.AGENT_LOGS_LABEL, style = MaterialTheme.typography.titleMedium)
      groups.forEachIndexed { index, group ->
        if (index > 0) {
          HorizontalDivider()
        }
        Text(group.label, style = MaterialTheme.typography.titleSmall)
        if (group.sessions.isEmpty()) {
          Text(NO_SESSIONS, style = MaterialTheme.typography.bodyMedium)
        }
        group.sessions.forEach { session ->
          SessionRow(session, group, onOpen, onCopyLink, onOpenHeapDump)
        }
      }
    }
  }
}

/**
 * One agent's session: read in this window when the heap dump it read is the one open here, and otherwise a
 * way to that dump.
 *
 * A session that read no dump at all — a client that connected and asked nothing — leads nowhere, and neither
 * does one whose dump has been deleted: a session outlives the files it was about.
 */
@Composable
private fun SessionRow(
  session: AgentSession,
  group: HeapDumpSessions,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  onOpenHeapDump: (File, Place) -> Unit
) {
  val place = Place.AgentLog(session.sessionId)
  val title = session.title()
  val summary = session.summary()
  if (group.isThisWindow) {
    val open: (OpenIn) -> Unit = { openIn -> onOpen(place, openIn) }
    OpenTarget(open, { onCopyLink(place) }) {
      Column(Modifier.openable(open)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      }
    }
    return
  }
  val opens = group.heapDumpFile?.takeIf { it.isFile }
  if (opens == null) {
    Column {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
    }
    return
  }
  // No tab to choose and no link to copy: what a link names is a window, and the window this session was
  // read in belongs to a run that has usually ended. The heap dump is what outlived it.
  Column(Modifier.openable { onOpenHeapDump(opens, place) }) {
    Text(title, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
    Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
  }
}

/** The sessions that read one heap dump, under the name this screen calls that dump. */
private class HeapDumpSessions(
  /** What the group is headed with: the file's name, and which of several dumps of that name it is. */
  val label: String,
  /** The dump itself, and null for the sessions that read none. */
  val heapDumpFile: File?,
  /** Whether it is the dump this window has open, which is the one group that is read here. */
  val isThisWindow: Boolean,
  val sessions: List<AgentSession>
)

/**
 * These sessions under the heap dumps they read, this window's dump first and always present.
 *
 * A session that read two dumps is under both, because it is one agent's work on each of them and reading it
 * against either is reading what it did there. One that read none is last, under a heading of its own: it is
 * not about this dump either, and there is no window that isn't a heap dump to list it in.
 *
 * The file's name rather than its path, which is what a reader recognises — and two dumps of the same name
 * from different directories are told apart by a number, since the name on its own would read as one dump
 * whose sessions disagree about what its addresses mean.
 */
private fun List<AgentSession>.byHeapDump(heapDumpFile: File): List<HeapDumpSessions> {
  val thisDump = heapDumpFile.absolutePath
  // Newest session first, which is the order these arrive in, so the dump worked on most recently is the
  // group after this window's.
  val paths = listOf(thisDump) + flatMap { it.heapDumpPaths }.distinct().filter { it != thisDump }
  val names = mutableMapOf<String, Int>()
  val groups = paths.map { path ->
    val file = File(path)
    val seen = names.merge(file.name, 1, Int::plus)!!
    val isThisWindow = path == thisDump
    HeapDumpSessions(
      // Numbered only from the second one on, since a name that is the only one of itself needs no number.
      label = file.name + (if (seen > 1) " ($seen)" else "") +
        (if (isThisWindow) " ($THIS_HEAP_DUMP)" else ""),
      heapDumpFile = file,
      isThisWindow = isThisWindow,
      sessions = filter { path in it.heapDumpPaths }
    )
  }
  val readNothing = filter { it.heapDumpPaths.isEmpty() }
  return groups + if (readNothing.isEmpty()) {
    emptyList()
  } else {
    listOf(
      HeapDumpSessions(
        label = NO_HEAP_DUMP_READ,
        heapDumpFile = null,
        isThisWindow = false,
        sessions = readNothing
      )
    )
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
 * **And what a row names leads to it** — the object, not the verb, since the object is what a reader wants to
 * look at. A call that went on to another heap dump leads to that dump instead, named on the row: a session is
 * one agent's connection and can read as many dumps as were open, and a row leading nowhere would be the app
 * showing somebody what an agent looked at and then declining to show them the thing.
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

/**
 * One call: when, what it did, and why the agent said it was doing it.
 *
 * **What leads somewhere is the object, not the verb.** A row is a sentence about a thing — "Described
 * MainActivity 0x12d368b8" — and the thing is what a reader wants to go and look at, so it is the only part
 * that is a link. Where the call named nothing, the verb is the whole of what it was about and is the link
 * itself: "Listed the leaks" is the leaks screen.
 */
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
  // What the call itself said it was about, and null for the calls that named nothing — where the verb says
  // the whole of it. Only those are named by this window: naming a place derived from which tool it is would
  // put "Leaks" after "Listed the leaks", and a call about another dump names a file this window never read.
  val target = call.subject?.let { subject ->
    if (elsewhere == null) place?.let { placeTitles[it] } ?: subject else subject
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      call.at.clockTime(),
      Modifier.width(TIME_WIDTH),
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
    Column {
      // Nowhere to go for a call about the app rather than about a heap dump — which dumps are open — or
      // about one that has since been deleted.
      val leadsTo = place?.takeIf { elsewhere == null || opens != null }
      // Wrapped rather than truncated, since a class name is as long as it is and the reason under it is a
      // sentence: this row is read, not scanned past.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // The link is the target, or the verb where the call named no target: what a reader clicks is the
        // thing, and a verb that is the whole sentence is the thing.
        val linked = target ?: call.verb
        if (target != null) {
          Text(call.verb, style = MaterialTheme.typography.bodyMedium)
        }
        when {
          leadsTo == null -> Text(linked, style = MaterialTheme.typography.bodyMedium)
          opens != null -> LinkText(linked, Modifier.openable { onOpenHeapDump(opens, leadsTo) })
          else -> {
            val open: (OpenIn) -> Unit = { openIn -> onOpen(leadsTo, openIn) }
            OpenTarget(open, { onCopyLink(leadsTo) }) { LinkText(linked, Modifier.openable(open)) }
          }
        }
        // What the answer came to, and — for a row that opens another dump when clicked — which dump that
        // is: worth knowing before rather than after.
        call.outcome?.let { Text("$LEADS_TO $it", style = MaterialTheme.typography.bodyMedium) }
        elsewhere?.let { Text("$IN ${it.name}", style = MaterialTheme.typography.bodyMedium) }
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

/** One piece of a row that leads somewhere, which is the piece a reader clicks. */
@Composable
private fun LinkText(
  text: String,
  modifier: Modifier
) = Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)

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

/** After the heap dump this window has open, which is the one group of sessions that is read here. */
private const val THIS_HEAP_DUMP = "this heap dump"

/** And over the sessions of a client that connected and read nothing, which no window can be about. */
private const val NO_HEAP_DUMP_READ = "No heap dump"

private const val NO_SESSIONS =
  "No agent has worked on this heap dump. Hand it to one by pointing its MCP client at Shark Explorer, and " +
    "everything it does lands here."

private const val NOTHING_ASKED =
  "This agent connected and asked nothing before it went away."

private const val NO_SUCH_SESSION =
  "There is no session with that name. A link to one leads to the file it was written to, which is kept " +
    "until a hundred newer sessions have pushed it out."
