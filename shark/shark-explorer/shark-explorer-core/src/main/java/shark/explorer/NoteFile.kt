package shark.explorer

import java.io.File
import java.io.IOException

/**
 * Where the notes about one heap dump are kept: a directory of this app's own, named after the dump, holding
 * one markdown file per place written about.
 *
 * Under [root] rather than beside the heap dump, and named [heapDumpFileKey] the way everything this app
 * keeps about a dump is.
 *
 * Markdown on disk, and readable on its own, because a note about a leak outlives the window it was written
 * in: it gets pasted into an issue, read by an agent, or opened in an editor months later. A format only
 * this app can read would make that a chore, which is the same as making it not happen.
 *
 * A file each rather than one file with the places as sections, because notes are written one place at a
 * time: separate files mean a save touches only the note that was typed into, nothing has to be parsed back
 * out of a document that also holds someone's own headings, and the directory listing is the index — which
 * is what [keysWithNotes] is, and what marks the tabs that have been written about.
 */
class NoteDirectory(
  /** This app's own directory, which the caller decides. */
  root: File,
  /** The heap dump these notes are about, which names the directory and nothing more. */
  heapDumpFile: File
) {

  /** The directory itself, shown in the window so that the notes can be found without this app. */
  val directory: File = File(root, heapDumpFileKey(heapDumpFile))

  /** Where what is written about [place] is kept. */
  fun noteFile(place: Place): NoteFile = NoteFile(File(directory, "${place.noteKey()}$NOTES_SUFFIX"))

  /**
   * Which places this heap dump already has a note about, as [Place.noteKey] keys.
   *
   * A listing rather than a file opened per place, because this answers a question about every tab at once:
   * whether the tab strip marks one. Reading the notes themselves waits until one is looked at.
   */
  fun keysWithNotes(): Set<String> =
    directory.listFiles().orEmpty()
      .filter { it.isFile && it.name.endsWith(NOTES_SUFFIX) }
      .map { it.name.removeSuffix(NOTES_SUFFIX) }
      .toSet()

  companion object {
    private const val NOTES_SUFFIX = ".md"
  }
}

/**
 * What a note is filed under, which is **what the tab is about rather than how it is arranged**.
 *
 * The distinction is the whole of this function. A place carries the state of the screen showing it as well
 * as its subject — what the object list is filtered to, which leaks are unfolded, how many objects a pile of
 * small ones stands for at this window width — and all of that changes while reading. Keyed on the place
 * itself, a note would follow the filter box: typing a letter into it would be moving to a different
 * notepad, and the note just written would be filed under a search nobody will run again.
 *
 * So: one note per object, one per pile, one for the object list however it is filtered, one for the leaks
 * however they are unfolded, one for the starred objects. The whole heap dump is an object like any other
 * here — the first tab of a window — and is the one to write the note that is about the dump rather than
 * about anything in it.
 */
fun Place.noteKey(): String = when (this) {
  is Place.Object ->
    if (objectId == HeapDominatorTreemap.ROOT_OBJECT_ID) {
      HEAP_DUMP_KEY
    } else {
      "$OBJECT_KEY_PREFIX${hexObjectId(objectId)}"
    }
  is Place.SmallerObjects -> "$SMALLER_OBJECTS_KEY_PREFIX${hexObjectId(parentObjectId)}"
  is Place.Objects -> OBJECT_LIST_KEY
  is Place.Leaks -> LEAKS_KEY
  is Place.Starred -> STARRED_KEY
  is Place.AgentLogs -> AGENT_LOGS_KEY
  // Per session, because a note about what one agent did is about that investigation and not about agents.
  is Place.AgentLog -> "$AGENT_LOG_KEY_PREFIX$sessionId"
}

/**
 * The place a [noteKey] was written for, and null for a key this version of the app doesn't know.
 *
 * The other way round from [noteKey] and therefore missing what a key deliberately leaves out — which filter
 * the object list had, which leaks were unfolded — so this answers with the plain list rather than the screen
 * somebody wrote the note from. That is the same note either way, which is the whole point of a key being
 * what a note is about rather than how it was arranged.
 *
 * **For reading a directory of notes back**, which is the one thing that has a key and wants a place: a
 * listing says which places this heap dump has been written about, and that is only worth saying if each of
 * them is somewhere a reader can be sent. A key from a newer version of the app, or a file somebody dropped
 * in the directory by hand, is null rather than an error.
 *
 * One caveat, from [hexObjectId] being the recognisable spelling rather than the exact one: a 32 bit heap
 * dump's sign-widened address and the positive address of the same digits share a key, so they share a note,
 * and this answers with the positive one. The note is right; the tab it opens, for that one dump, may not be.
 */
fun placeOfNoteKeyOrNull(key: String): Place? = when {
  key == HEAP_DUMP_KEY -> Place.wholeHeapDump()
  key == OBJECT_LIST_KEY -> Place.Objects()
  key == LEAKS_KEY -> Place.Leaks()
  key == STARRED_KEY -> Place.Starred
  key == AGENT_LOGS_KEY -> Place.AgentLogs
  key.startsWith(AGENT_LOG_KEY_PREFIX) -> Place.AgentLog(key.removePrefix(AGENT_LOG_KEY_PREFIX))
  key.startsWith(OBJECT_KEY_PREFIX) ->
    objectIdOfHex(key.removePrefix(OBJECT_KEY_PREFIX))?.let { Place.Object(it) }
  // A pile of the objects one rectangle had no room for is drawn from the window's width, so how many
  // objects it stands for and what they weigh are no part of the key and can't be answered here.
  else -> null
}

/** The note about the heap dump as a whole, which is the place its first tab opens on. */
private const val HEAP_DUMP_KEY = "heap-dump"

private const val OBJECT_KEY_PREFIX = "object-"
private const val SMALLER_OBJECTS_KEY_PREFIX = "smaller-objects-"
private const val OBJECT_LIST_KEY = "object-list"
private const val LEAKS_KEY = "leaks"
private const val STARRED_KEY = "starred"
private const val AGENT_LOGS_KEY = "agent-logs"
private const val AGENT_LOG_KEY_PREFIX = "agent-log-"

/**
 * One note: a markdown file, read and written whole.
 *
 * Both halves throw [IOException] rather than swallowing it, so that a note which could not be read is
 * never quietly saved over by an empty one.
 */
class NoteFile internal constructor(
  /** The file itself, which is shown in the window so that the note can be found without this app. */
  val file: File
) {

  /** What is on disk, or nothing at all for a place nobody has written about yet. */
  fun read(): String = if (file.isFile) file.readText() else ""

  /**
   * Puts [text] on disk, through a file of its own and a rename, so that a run killed halfway through a
   * save leaves the last note rather than half of one. The one thing in this app nobody else has a copy
   * of is what someone typed into it.
   *
   * A note cleared out deletes the file rather than leaving an empty one behind for the next run to find
   * and mark a tab with. See [writeWholeFile].
   */
  fun write(text: String) = writeWholeFile(file, text)
}
