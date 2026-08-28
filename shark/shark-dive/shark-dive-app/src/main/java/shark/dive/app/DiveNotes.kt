package shark.dive.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.dive.Note
import shark.dive.NoteDirectory
import shark.dive.NoteFile
import shark.dive.Place
import shark.dive.noteKey

/**
 * The notes of every heap dump this run has open.
 *
 * Per run rather than per window, like [UpdateNotice], and for a sharper reason than that one: **the same
 * heap dump is often open in two windows**, that being how two views of it are compared, and two notepads
 * over one file would mean each window saving over the other's notes without either of them ever showing
 * the loss. One notepad shared by both windows can't do that, and typing in one shows up in the other.
 *
 * Plain state rather than a composable's, so that it can be handed to a window and to a test. See
 * `notes/decisions.md`.
 */
internal class DiveNotes(private val root: File = NOTES_DIRECTORY) {

  private val byDirectory = mutableMapOf<String, HeapDumpNotes>()

  /** The notes about [heapDumpFile], the same ones every time they are asked for. */
  fun of(heapDumpFile: File): HeapDumpNotes = synchronized(byDirectory) {
    val directory = NoteDirectory(root, heapDumpFile)
    byDirectory.getOrPut(directory.directory.path) { HeapDumpNotes(directory) }
  }

  companion object {
    /** Beside the logs and the published runs, which is everything else this app keeps between runs. */
    private val NOTES_DIRECTORY = File(SHARK_DIVE_DIRECTORY, "notes")
  }
}

/**
 * What has been written about one heap dump: a note per place, and which places have one.
 *
 * Which places have one is here rather than in each note because it is a question about every tab at once —
 * whether the strip marks it — and answering it by opening a file per tab would be a read per tab per
 * recomposition. One directory listing answers it for all of them. See [NoteDirectory.keysWithNotes].
 */
@Stable
internal class HeapDumpNotes(private val directory: NoteDirectory) {

  private val byPlace = mutableMapOf<String, PlaceNotes>()

  /**
   * The places with something written about them, as [noteKey] keys.
   *
   * From the directory to start with, and from each save after that: what the mark on a tab means is that
   * there is a note to come back to, so a draft nobody has saved yet is not one.
   */
  var writtenAbout: Set<String> by mutableStateOf(emptySet())
    private set

  private var isListed = false

  /** Whether [place] has a note, which is what puts a mark on its tab. */
  fun hasNote(place: Place): Boolean = place.noteKey() in writtenAbout

  /** What has been written about [place], the same notepad every time it is asked for. */
  fun of(place: Place): PlaceNotes = synchronized(byPlace) {
    val key = place.noteKey()
    byPlace.getOrPut(key) {
      PlaceNotes(
        noteFile = directory.noteFile(place),
        onSavedChanged = { hasNote -> written(key, hasNote) }
      )
    }
  }

  /**
   * Reads which places have a note, once per run of the app.
   *
   * Off the UI thread, because it is a directory listing: on a machine where the disk has gone away, not a
   * fast one. A listing that fails leaves no tab marked, which is the same as the notes not being there —
   * a window that can't reach them says so where it matters, which is when one is opened. See [PlaceNotes].
   */
  suspend fun list() {
    if (isListed) {
      return
    }
    val keys = try {
      withContext(Dispatchers.IO) { directory.keysWithNotes() }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not list the notes in ${directory.directory}" }
      return
    }
    isListed = true
    // Whatever has been typed since the listing started is written about too, and its file may not have
    // landed yet: the union rather than the listing, so that a mark never comes off a tab.
    writtenAbout = writtenAbout + keys
    SharkLog.d { "${keys.size} places of ${directory.directory.name} have notes" }
  }

  private fun written(
    key: String,
    hasNote: Boolean
  ) {
    writtenAbout = if (hasNote) writtenAbout + key else writtenAbout - key
  }
}

/**
 * What has been written about one place: the note as saved, the [Note] it was read as, and the draft being
 * typed if there is one.
 *
 * All three here rather than in the composable, so that a draft outlives the section that was drawing it:
 * clicking another tab half way through a sentence and coming back finds the sentence, since this is the
 * run's notepad for that place rather than the screen's.
 *
 * **Nothing touches the disk until a note is saved**, beyond the one read of the file. Which is also what
 * keeps the tests off the developer's own notes.
 */
@Stable
internal class PlaceNotes(
  private val noteFile: NoteFile,
  /** How [HeapDumpNotes] hears that this place has, or no longer has, a note. */
  private val onSavedChanged: (Boolean) -> Unit
) {

  /** Where this is kept, shown while writing so the note can be found without this app. */
  val file: File get() = noteFile.file

  /** The markdown as it was last saved, which is what the section draws and what a draft starts from. */
  var text by mutableStateOf("")
    private set

  /**
   * What that markdown means, which is what the section draws once there is something to draw.
   *
   * Worked out by the window rather than here, since resolving the names in it is a read of the heap dump.
   * For a beat after a save this is the note with its mentions unanswered. See [HeapDumpDive].
   */
  var note by mutableStateOf(Note.EMPTY)
    private set

  /** What is being typed, and null when nothing is: the section is either writing or reading, never both. */
  var draft: String? by mutableStateOf(null)
    private set

  /**
   * Whether the file has been read, which is what makes writing safe.
   *
   * Until it is true, [text] is empty because nothing has been read rather than because nothing was
   * written — and saving over that would be deleting a note to say the disk was slow. Which is why the
   * button that starts one is disabled until then.
   */
  var isRead by mutableStateOf(false)
    private set

  /** What went wrong reading or writing the file, shown in the section. Null while nothing has. */
  var problem: String? by mutableStateOf(null)
    private set

  /** Starts writing, from whatever was last saved. */
  fun edit() {
    if (draft == null) {
      draft = text
    }
  }

  fun edited(draft: String) {
    this.draft = draft
  }

  /** Throws the draft away, which is what the section's cancel promises and nothing else does. */
  fun cancel() {
    draft = null
  }

  /** What [text] was read as, which the window works out and hands back. */
  fun parsed(note: Note) {
    this.note = note
  }

  /**
   * Reads the file, once per run of the app.
   *
   * Off the UI thread, because it is a file: small, and on a machine where the disk has gone away, not
   * small at all.
   */
  suspend fun read() {
    if (isRead) {
      return
    }
    val read = try {
      withContext(Dispatchers.IO) { noteFile.read() }
    } catch (throwable: Throwable) {
      // In the section as well as in the log, because a note is the one thing in this window that nobody
      // else has a copy of: a reader who is told nothing would type over it.
      SharkLog.d(throwable) { "Could not read the note at $file" }
      problem = "Could not read $file: $throwable"
      return
    }
    SharkLog.d { "Read ${read.length} characters of notes from $file" }
    text = read
    isRead = true
    problem = null
    if (read.isNotEmpty()) {
      onSavedChanged(true)
    }
  }

  /**
   * Puts the draft on disk and stops writing, or says why it couldn't and keeps it.
   *
   * [NonCancellable] because the section that started this goes away the moment the draft does, and a save
   * cancelled halfway is the one thing this class exists to prevent.
   */
  suspend fun save() {
    val draft = draft ?: return
    if (!isRead) {
      // Which the disabled button already prevents; here too, because this is where it would cost a note.
      SharkLog.d { "Not saving $file: it has not been read yet" }
      return
    }
    val written = withContext(Dispatchers.IO + NonCancellable) {
      try {
        noteFile.write(draft)
        true
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not save the note to $file" }
        problem = "Could not save $file: $throwable"
        false
      }
    }
    if (!written) {
      return
    }
    SharkLog.d { "Saved ${draft.length} characters of notes to $file" }
    text = draft
    this.draft = null
    problem = null
    onSavedChanged(draft.isNotEmpty())
  }
}
