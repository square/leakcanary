package shark.dive

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HeapDumpFilesTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `what was written is what is read`() {
    val file = File(testFolder.newFolder("notes"), "heap.hprof-1f3a9c0b.md")

    writeWholeFile(file, "the whole of it\n")

    assertThat(file.readText()).isEqualTo("the whole of it\n")
  }

  @Test fun `nothing written is the file deleted`() {
    val file = File(testFolder.newFolder("notes"), "heap.hprof-1f3a9c0b.md")
    writeWholeFile(file, "something\n")

    writeWholeFile(file, "")

    assertThat(file.exists()).isFalse()
  }

  /**
   * What these files are read by is not only the thread writing them — another window of the run, a `--cli`
   * command, somebody's editor — so a save in flight must never be a moment in which the file isn't there.
   * This fails without [java.nio.file.StandardCopyOption.ATOMIC_MOVE], in a few replacements rather than in a
   * rare one: around two of every five of them left a gap when it was measured. The reader spins rather than
   * sleeping because what it is looking for lasts two syscalls.
   */
  @Test fun `a file being replaced is never missing to a reader`() {
    val file = File(testFolder.newFolder("verdicts"), "heap.hprof-1f3a9c0b.verdicts.tsv")
    writeWholeFile(file, "the first write\n")
    val writing = AtomicBoolean(true)
    val readsThatSawIt = AtomicInteger()
    val gone = AtomicReference<IOException?>(null)
    val reader = thread(name = "reads-while-it-is-written") {
      while (writing.get()) {
        if (!file.isFile) {
          continue
        }
        try {
          file.readLines()
          readsThatSawIt.incrementAndGet()
        } catch (notFound: IOException) {
          gone.compareAndSet(null, notFound)
        }
      }
    }

    repeat(REPLACEMENTS) { writeWholeFile(file, "write $it\n") }
    writing.set(false)
    reader.join()

    assertThat(gone.get()).isNull()
    // Because a reader that never got as far as opening the file would pass whatever the writer did.
    assertThat(readsThatSawIt.get()).isGreaterThan(0)
  }

  companion object {
    /**
     * Enough that a gap of two syscalls is hit many times over, and few enough to be a fraction of a second.
     */
    private const val REPLACEMENTS = 2000
  }
}
