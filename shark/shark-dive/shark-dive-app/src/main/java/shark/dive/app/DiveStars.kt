package shark.dive.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.dive.StarredFile
import shark.dive.hexObjectId

/**
 * The objects starred in every heap dump this run has open.
 *
 * Per run rather than per window, for the reason [DiveNotes] and [DiveLeakStatuses] are: the same heap dump is
 * often open in two windows, and two of these over one file would mean each window saving over the other's.
 *
 * Plain state rather than a composable's, so that it can be handed to a window and to a test.
 */
internal class DiveStars(private val root: File = STARRED_DIRECTORY) {

  private val byFile = mutableMapOf<String, HeapDumpStars>()

  /** What is starred in [heapDumpFile], the same ones every time they are asked for. */
  fun of(heapDumpFile: File): HeapDumpStars = synchronized(byFile) {
    val file = StarredFile(root, heapDumpFile)
    byFile.getOrPut(file.file.path) { HeapDumpStars(file) }
  }

  companion object {
    /** Beside the notes, the leak statuses and the logs, which is everything else this app keeps. */
    private val STARRED_DIRECTORY = File(SHARK_DIVE_DIRECTORY, "starred")
  }
}

/**
 * What is starred in one heap dump: the addresses, and how to star or unstar one.
 *
 * **Nothing is starred that wasn't written**, for the reason [HeapDumpLeakStatuses] applies to a status: until
 * the file has been read, [objectIds] is empty because nothing was read rather than because nothing is
 * starred, and saving over that would take the star off everything to say the disk was slow.
 */
@Stable
internal class HeapDumpStars(private val starredFile: StarredFile) {

  /** Where these are kept, so a working set can be found without this app. */
  val file: File get() = starredFile.file

  /** The starred objects, in the order they were starred, which is the order the screen lists them in. */
  var objectIds: List<Long> by mutableStateOf(emptyList())
    private set

  /** Whether the file has been read, which is what makes writing safe. */
  var isRead by mutableStateOf(false)
    private set

  /** What went wrong reading or writing the file. Null while nothing has. */
  var problem: String? by mutableStateOf(null)
    private set

  fun isStarred(objectId: Long): Boolean = objectId in objectIds

  /** Reads the file, once per run of the app. */
  suspend fun read() {
    if (isRead) {
      return
    }
    val read = try {
      withContext(Dispatchers.IO) { starredFile.read() }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not read the starred objects in $file" }
      problem = "Could not read $file: $throwable"
      return
    }
    isRead = true
    problem = null
    objectIds = read
  }

  /**
   * Stars [objectId], or takes the star off it, and puts the lot on disk.
   *
   * One at a time, because the whole list is written every time: two stars set faster than a disk writes
   * would both work out what to write from the list before either of them, and the second would save over
   * the first. [NonCancellable] for the same reason from the other side — a star is set by a click that
   * leaves nothing on screen waiting for it, so a save given up on half way would leave a file that
   * disagrees with the screen above it.
   */
  suspend fun toggle(objectId: Long) {
    saving.withLock {
      if (!isRead) {
        SharkLog.d { "Not starring ${hexObjectId(objectId)}: $file has not been read yet" }
        return
      }
      val wasStarred = objectId in objectIds
      // Appended rather than sorted in: the order is the order they were starred. See [StarredFile].
      val next = if (wasStarred) objectIds - objectId else objectIds + objectId
      val written = withContext(Dispatchers.IO + NonCancellable) {
        try {
          starredFile.write(next)
          true
        } catch (throwable: Throwable) {
          SharkLog.d(throwable) { "Could not save the starred objects to $file" }
          problem = "Could not save $file: $throwable"
          false
        }
      }
      if (!written) {
        return
      }
      SharkLog.d {
        "${if (wasStarred) "Unstarred" else "Starred"} ${hexObjectId(objectId)} in $file"
      }
      objectIds = next
      problem = null
    }
  }

  private val saving = Mutex()
}
