package shark.dive.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import shark.SharkLog
import shark.dive.Place
import shark.dive.agent.AgentSession
import shark.dive.agent.AgentSessionCall
import shark.dive.agent.screen
import shark.dive.agent.subject
import shark.dive.agent.verb

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
  /** And the link to it, which names that dump: a row about another one is worth sending, not only clicking. */
  onCopyHeapDumpLink: (File, Place) -> Unit = { file, place ->
    SharkLog.d { "Nothing here to link to $place of $file with" }
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
          SessionRow(session, group, onOpen, onCopyLink, onOpenHeapDump, onCopyHeapDumpLink)
        }
      }
    }
  }
}

/**
 * One agent's session: read in a window of the heap dump it read, which is this one when it read this one.
 *
 * **Every session on this screen leads to itself**, and where it opens is the only question. A session of
 * another dump opens in a window of that dump, because its addresses are that file's; the ones with no such
 * window to be had — this window's own, one whose dump has been deleted, one that read no dump at all — are
 * read here. Reading a session against the wrong heap dump costs the names of the objects in it and nothing
 * else: the verbs, the reasons and the refusals are what the agent said, and an address whose dump has gone
 * resolves to nothing in any window there is.
 */
@Composable
private fun SessionRow(
  session: AgentSession,
  group: HeapDumpSessions,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  onOpenHeapDump: (File, Place) -> Unit,
  onCopyHeapDumpLink: (File, Place) -> Unit
) {
  val place = Place.AgentLog(session.sessionId)
  val title = session.title()
  val summary = session.summary()
  val opensHeapDump = group.heapDumpFile?.takeIf { !group.isThisWindow && it.isFile }
  if (opensHeapDump != null) {
    // No tab to choose, since this opens a window of its own heap dump rather than a tab of this one — and a
    // link all the same, naming that dump: the run this session was read in has usually ended, and the file
    // is what outlived it.
    CopyLinkTarget({ onCopyHeapDumpLink(opensHeapDump, place) }) {
      Column(Modifier.openable { onOpenHeapDump(opensHeapDump, place) }) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      }
    }
    return
  }
  val open: (OpenIn) -> Unit = { openIn -> onOpen(place, openIn) }
  OpenTarget(open, { onCopyLink(place) }) {
    Column(Modifier.openable(open)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
      Text(summary, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
    }
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
      // And said to be gone where it is, since that is why the objects in those sessions have no names:
      // there is no window that can resolve an address of a file nobody has any more.
      label = file.name + (if (seen > 1) " ($seen)" else "") + when {
        isThisWindow -> " ($THIS_HEAP_DUMP)"
        !file.isFile -> " ($MISSING_HEAP_DUMP)"
        else -> ""
      },
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
  /** And the link to it, which names that dump rather than this window. See [AgentLogsScreen]. */
  onCopyHeapDumpLink: (File, Place) -> Unit = { file, place ->
    SharkLog.d { "Nothing here to link to $place of $file with" }
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
          onOpenHeapDump = onOpenHeapDump,
          onCopyHeapDumpLink = onCopyHeapDumpLink
        )
      }
    }
  }
}

/**
 * One call: when, what it did, and why the agent said it was doing it.
 *
 * **What leads somewhere is the thing, never the verb.** A row is a sentence about something — "Looked at
 * MainActivity 0x12d368b8", "Listed the leaks" — and the thing is what a reader wants to go and look at, so
 * it is the only part that is a link. Which is why a verb ends where the thing begins even when that leaves
 * it hanging: "Listed the" is prose and *leaks* is the leaks screen. See `shark.dive.agent.verb`.
 *
 * **And there is a thing for every call that went anywhere.** An object the agent named is named back by
 * this window; a call that named nothing went to a screen of the dump all the same, and the words for that
 * come with the verb. A row with no link is a call about the app rather than about a heap dump — which dumps
 * are open, which devices are connected — or one about a heap dump that has since been deleted.
 */
@Composable
private fun AgentCallRow(
  call: AgentSessionCall,
  heapDumpFile: File,
  placeTitles: Map<Place, String>,
  onOpen: (Place, OpenIn) -> Unit,
  onCopyLink: (Place) -> Unit,
  onOpenHeapDump: (File, Place) -> Unit,
  onCopyHeapDumpLink: (File, Place) -> Unit
) {
  val place = call.place
  // Which heap dump the row is about when it isn't this window's, and null when it is. An address is an
  // address of one dump, so the same number in another one is another object: this window cannot name it or
  // go there, and the dump that can has to be opened first.
  val elsewhere = call.otherHeapDumpOrNull(heapDumpFile)
  // And whether that is still possible. A session outlives the heap dumps it was about, so a row naming one
  // that has been deleted says which and leads nowhere.
  val opens = elsewhere?.takeIf { it.isFile }
  // What the call was about: the object it named, in this window's words for it, or the screen it went to in
  // the words that came with the verb. A place this window named would be the wrong words for a sentence —
  // "Listed the Leaks" — and a call about another dump names a file this window has never read.
  val target = call.subject?.let { subject ->
    if (elsewhere == null) place?.let { placeTitles[it] } ?: subject else subject
  } ?: call.screen
  // The heap dumps the answer named, for the one call that asks the app which are open: several rows rather
  // than one, so they are behind the verb until somebody asks for them.
  val openHeapDumps = call.openHeapDumps
  var isUnfolded by remember { mutableStateOf(false) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      call.at.clockTime(),
      Modifier.width(TIME_WIDTH),
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
    Column {
      // Nowhere to go for a call about the app rather than about a heap dump, or about one that has since
      // been deleted.
      val leadsTo = place?.takeIf { elsewhere == null || opens != null }
      // Wrapped rather than truncated, since a class name is as long as it is and the reason under it is a
      // sentence: this row is read, not scanned past.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
          openHeapDumps.isNotEmpty() -> UnfoldableVerb(call.verb, isUnfolded) { isUnfolded = !isUnfolded }
          target == null -> Text(call.verb, style = MaterialTheme.typography.bodyMedium)
          else -> {
            Text(call.verb, style = MaterialTheme.typography.bodyMedium)
            when {
              leadsTo == null -> Text(target, style = MaterialTheme.typography.bodyMedium)
              // Another dump: one place to go, and a link that names that dump for somebody to go there
              // without this window.
              opens != null -> CopyLinkTarget({ onCopyHeapDumpLink(opens, leadsTo) }) {
                LinkText(target, Modifier.openable { onOpenHeapDump(opens, leadsTo) })
              }
              else -> {
                val open: (OpenIn) -> Unit = { openIn -> onOpen(leadsTo, openIn) }
                OpenTarget(open, { onCopyLink(leadsTo) }) { LinkText(target, Modifier.openable(open)) }
              }
            }
          }
        }
        // What the answer came to, and — for a row that opens another dump when clicked — which dump that
        // is: worth knowing before rather than after. Not on the row whose answer is a list of dumps: the
        // file that one was recorded against is whichever was open, and what it came back with is below it.
        call.outcome?.let { Text("$LEADS_TO $it", style = MaterialTheme.typography.bodyMedium) }
        elsewhere?.takeIf { openHeapDumps.isEmpty() }
          ?.let { Text("$IN ${it.name}", style = MaterialTheme.typography.bodyMedium) }
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
      if (isUnfolded) {
        openHeapDumps.forEach { path ->
          OpenHeapDumpRow(path, heapDumpFile, onOpenHeapDump, onCopyHeapDumpLink)
        }
      }
    }
  }
}

/**
 * A verb with what is behind it, for the call whose answer is a list rather than a thing.
 *
 * The verb itself is what opens it, since there is no thing on that row to be a link — and the arrow is the
 * same one the leaks screen folds its sections with, because it is the same gesture on a screen somebody
 * reads straight after that one.
 */
@Composable
private fun UnfoldableVerb(
  verb: String,
  isUnfolded: Boolean,
  onToggle: () -> Unit
) {
  Row(
    Modifier.clickableRow(onClick = onToggle),
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Text(
      if (isUnfolded) EXPANDED_ARROW else FOLDED_ARROW,
      style = MaterialTheme.typography.bodyMedium,
      color = MUTED_TEXT
    )
    Text(verb, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * One of the heap dumps a call was answered with, as somewhere to go.
 *
 * Which is why unfolding the row is worth anything: the answer to "which dumps are open" is a list of the
 * files an investigation could have been about, and the run that had them open has ended by the time
 * anybody reads this — so a name with nothing behind it would be the one part of a session a reader is shown
 * and cannot follow. The dump this window has open is the exception, and says so rather than leading to the
 * window it already is.
 */
@Composable
private fun OpenHeapDumpRow(
  path: String,
  heapDumpFile: File,
  onOpenHeapDump: (File, Place) -> Unit,
  onCopyHeapDumpLink: (File, Place) -> Unit
) {
  val file = File(path)
  val name = file.name
  val style = MaterialTheme.typography.bodySmall
  val indent = Modifier.padding(start = UNFOLDED_INSET)
  when {
    path == heapDumpFile.absolutePath ->
      Text("$name ($THIS_HEAP_DUMP)", indent, style = style, color = MUTED_TEXT)
    // Gone, which a list of what *was* open is exactly where somebody finds out.
    !file.isFile -> Text("$name ($MISSING_HEAP_DUMP)", indent, style = style, color = MUTED_TEXT)
    // The whole heap dump, since a dump named without a place in it is the window that dump opens on. No tab
    // to choose, for the reason a session of another dump has none — it opens a window of its own — and a
    // link to that dump all the same, which is a file anybody can be sent.
    else -> CopyLinkTarget({ onCopyHeapDumpLink(file, Place.wholeHeapDump()) }) {
      Text(
        name,
        indent.openable { onOpenHeapDump(file, Place.wholeHeapDump()) },
        style = style,
        color = LINK_COLOR
      )
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

/** The time of day, as every line of this app's own log is stamped. See `shark.dive.SessionLog`. */
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

/**
 * And after one that isn't on this machine any more, which is why the objects in those sessions have no
 * names: an address is an address of a file, and that file has gone.
 */
private const val MISSING_HEAP_DUMP = "missing"

/** How far the rows behind a verb sit in from it, which is the arrow's width and the gap after it. */
private val UNFOLDED_INSET = 20.dp

/** And over the sessions of a client that connected and read nothing, which no window can be about. */
private const val NO_HEAP_DUMP_READ = "No heap dump"

private const val NO_SESSIONS =
  "No agent has worked on this heap dump. Hand it to one by pointing its MCP client at Shark Dive, and " +
    "everything it does lands here."

private const val NOTHING_ASKED =
  "This agent connected and asked nothing before it went away."

private const val NO_SUCH_SESSION =
  "There is no session with that name. A link to one leads to the file it was written to, which is kept " +
    "until a hundred newer sessions have pushed it out."
