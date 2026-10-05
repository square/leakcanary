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
import shark.dive.VerdictFile
import shark.dive.VerdictOverride
import shark.dive.VerdictOverrides
import shark.dive.hexObjectId

/**
 * The verdicts set by hand on every heap dump this run has open.
 *
 * Per run rather than per window, for the reason [DiveNotes] is: the same heap dump is often open in two
 * windows, and two of these over one file would mean each window saving over the other's verdicts. It also
 * means a verdict set in one window is the verdict the other one draws, which is what makes the two windows two
 * views of one heap dump rather than two readings of it.
 *
 * Plain state rather than a composable's, so that it can be handed to a window and to a test.
 */
internal class DiveVerdicts(private val root: File = VERDICTS_DIRECTORY) {

  private val byFile = mutableMapOf<String, HeapDumpVerdicts>()

  /** The verdicts set on [heapDumpFile], the same ones every time they are asked for. */
  fun of(heapDumpFile: File): HeapDumpVerdicts = synchronized(byFile) {
    val file = VerdictFile(root, heapDumpFile)
    byFile.getOrPut(file.file.path) { HeapDumpVerdicts(file) }
  }

  companion object {
    /**
     * Beside the notes, the logs and the published runs, which is everything else this app keeps.
     *
     * This was `leak-statuses`, and the files in it ended `.leak-statuses.tsv`, so verdicts recorded before
     * the rename read as a heap dump nobody has set one on. Carrying one over is a rename of both halves.
     */
    private val VERDICTS_DIRECTORY = File(SHARK_DIVE_DIRECTORY, "verdicts")
  }
}

/**
 * What has been set by hand about one heap dump: a verdict per object, and how to change one.
 *
 * **Nothing is applied that wasn't written**, which is the opposite way round from the notes beside it: a
 * note is what someone is typing and a verdict is a conclusion the window then reads the heap dump through, so
 * one that only lives in this process is a path explained by a reason that will be gone next run. A save that
 * fails leaves the heap dump as it was and says why.
 */
@Stable
internal class HeapDumpVerdicts(private val verdictFile: VerdictFile) {

  /** Where these are kept, shown while setting one so they can be found without this app. */
  val file: File get() = verdictFile.file

  /** Every verdict set by hand, which is what the window reads the heap dump with. */
  var overrides: VerdictOverrides by mutableStateOf(VerdictOverrides.NONE)
    private set

  /**
   * Whether the file has been read, which is what makes writing safe.
   *
   * Until it is true, [overrides] is empty because nothing has been read rather than because nothing was
   * set — and saving over that would delete every verdict of the heap dump to say the disk was slow. Which is
   * why the button that changes one is disabled until then.
   */
  var isRead by mutableStateOf(false)
    private set

  /** What went wrong reading or writing the file, shown where the verdict is. Null while nothing has. */
  var problem: String? by mutableStateOf(null)
    private set

  /** Reads the file, once per run of the app. */
  suspend fun read() {
    if (isRead) {
      return
    }
    val read = try {
      withContext(Dispatchers.IO) { verdictFile.read() }
    } catch (throwable: Throwable) {
      // In the window as well as in the log, because these are somebody's conclusions about this heap dump:
      // a reader who is told nothing would take the inspectors' answer for the whole of it.
      SharkLog.d(throwable) { "Could not read the verdicts set by hand in $file" }
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
   * half way through would leave a heap dump whose verdicts contradict each other — which is the one state
   * the conflict step exists to prevent.
   */
  suspend fun set(
    override: VerdictOverride,
    /** The verdicts that had to change for [override] to be true. See [shark.dive.VerdictConflict]. */
    solved: List<VerdictOverride> = emptyList()
  ) {
    save(overrides.with(listOf(override) + solved)) {
      "Set ${override.verdict} on ${hexObjectId(override.objectId)} by hand, and " +
        "${solved.size} verdicts with it to solve what it disagreed with"
    }
  }

  /** Takes the verdict off [objectId], so that the heap dump says what it says about it again. */
  suspend fun clear(objectId: Long) {
    save(overrides.without(objectId)) { "Took the verdict set by hand off ${hexObjectId(objectId)}" }
  }

  private suspend fun save(
    next: VerdictOverrides,
    what: () -> String
  ) {
    if (!isRead) {
      // Which the disabled button already prevents; here too, because this is where it would cost every
      // verdict of the heap dump.
      SharkLog.d { "Not saving to $file: it has not been read yet" }
      return
    }
    val written = withContext(Dispatchers.IO + NonCancellable) {
      try {
        verdictFile.write(next)
        true
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not save the verdicts set by hand to $file" }
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
