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
import shark.dive.LeakStatusFile
import shark.dive.LeakStatusOverride
import shark.dive.LeakStatusOverrides
import shark.dive.hexObjectId

/**
 * The leaking statuses set by hand on every heap dump this run has open.
 *
 * Per run rather than per window, for the reason [DiveNotes] is: the same heap dump is often open in two
 * windows, and two of these over one file would mean each window saving over the other's statuses. It also
 * means a status set in one window is the status the other one draws, which is what makes the two windows two
 * views of one heap dump rather than two readings of it.
 *
 * Plain state rather than a composable's, so that it can be handed to a window and to a test.
 */
internal class DiveLeakStatuses(private val root: File = LEAK_STATUSES_DIRECTORY) {

  private val byFile = mutableMapOf<String, HeapDumpLeakStatuses>()

  /** The statuses set on [heapDumpFile], the same ones every time they are asked for. */
  fun of(heapDumpFile: File): HeapDumpLeakStatuses = synchronized(byFile) {
    val file = LeakStatusFile(root, heapDumpFile)
    byFile.getOrPut(file.file.path) { HeapDumpLeakStatuses(file) }
  }

  companion object {
    /** Beside the notes, the logs and the published runs, which is everything else this app keeps. */
    private val LEAK_STATUSES_DIRECTORY = File(SHARK_DIVE_DIRECTORY, "leak-statuses")
  }
}

/**
 * What has been set by hand about one heap dump: a status per object, and how to change one.
 *
 * **Nothing is applied that wasn't written**, which is the opposite way round from the notes beside it: a
 * note is what someone is typing and a status is a conclusion the window then reads the heap dump through, so
 * one that only lives in this process is a chain explained by a reason that will be gone next run. A save that
 * fails leaves the heap dump as it was and says why.
 */
@Stable
internal class HeapDumpLeakStatuses(private val statusFile: LeakStatusFile) {

  /** Where these are kept, shown while setting one so they can be found without this app. */
  val file: File get() = statusFile.file

  /** Every status set by hand, which is what the window reads the heap dump with. */
  var overrides: LeakStatusOverrides by mutableStateOf(LeakStatusOverrides.NONE)
    private set

  /**
   * Whether the file has been read, which is what makes writing safe.
   *
   * Until it is true, [overrides] is empty because nothing has been read rather than because nothing was
   * set — and saving over that would delete every status of the heap dump to say the disk was slow. Which is
   * why the button that changes one is disabled until then.
   */
  var isRead by mutableStateOf(false)
    private set

  /** What went wrong reading or writing the file, shown where the status is. Null while nothing has. */
  var problem: String? by mutableStateOf(null)
    private set

  /** Reads the file, once per run of the app. */
  suspend fun read() {
    if (isRead) {
      return
    }
    val read = try {
      withContext(Dispatchers.IO) { statusFile.read() }
    } catch (throwable: Throwable) {
      // In the window as well as in the log, because these are somebody's conclusions about this heap dump:
      // a reader who is told nothing would take the inspectors' answer for the whole of it.
      SharkLog.d(throwable) { "Could not read the statuses set by hand in $file" }
      problem = "Could not read $file: $throwable"
      return
    }
    isRead = true
    problem = null
    overrides = read
  }

  /**
   * Sets [override], along with whatever solving its conflicts flipped, and puts the lot on disk.
   *
   * [NonCancellable] because the dialog that asked for this closes as soon as it has, and a save that stopped
   * half way through would leave a heap dump whose statuses contradict each other — which is the one state
   * the conflict step exists to prevent.
   */
  suspend fun set(
    override: LeakStatusOverride,
    /** The statuses that had to change for [override] to be true. See [shark.dive.LeakStatusConflict]. */
    solved: List<LeakStatusOverride> = emptyList()
  ) {
    save(overrides.with(listOf(override) + solved)) {
      "Set ${override.status} on ${hexObjectId(override.objectId)} by hand, and " +
        "${solved.size} statuses with it to solve what it disagreed with"
    }
  }

  /** Takes the status off [objectId], so that the heap dump says what it says about it again. */
  suspend fun clear(objectId: Long) {
    save(overrides.without(objectId)) { "Took the status set by hand off ${hexObjectId(objectId)}" }
  }

  private suspend fun save(
    next: LeakStatusOverrides,
    what: () -> String
  ) {
    if (!isRead) {
      // Which the disabled button already prevents; here too, because this is where it would cost every
      // status of the heap dump.
      SharkLog.d { "Not saving to $file: it has not been read yet" }
      return
    }
    val written = withContext(Dispatchers.IO + NonCancellable) {
      try {
        statusFile.write(next)
        true
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not save the statuses set by hand to $file" }
        problem = "Could not save $file: $throwable"
        false
      }
    }
    if (!written) {
      return
    }
    SharkLog.d { "${what()} in $file" }
    overrides = next
    problem = null
  }
}
