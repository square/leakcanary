package shark.dive.app

import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.exactHexObjectId

/**
 * What a star does when the file it goes in hasn't been read yet, which is the one way a click here can be
 * lost rather than late.
 *
 * Covered without a window because the race it is about can't be driven through one: the window starts the
 * read as a heap dump opens and a person reaches a star seconds later, so the only thing fast enough to get
 * in between is something driving the app — which is how this was found, as `DiveAppTest`'s star test timing
 * out on a loaded CI runner and nowhere else.
 */
class DiveStarsTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @get:Rule
  var log = RecordedLog()

  @Test fun `a star set before the file is read is still written`() {
    val stars = starsOf(testFolder.newFolder())

    // No read() first, which is what the window's own LaunchedEffect does and what a click can beat.
    runBlocking { stars.toggle(OBJECT_ID) }

    assertThat(stars.objectIds).containsExactly(OBJECT_ID)
    assertThat(stars.file.readText()).contains(exactHexObjectId(OBJECT_ID))
  }

  @Test fun `a star set before the file is read keeps what the file already had`() {
    val root = testFolder.newFolder()
    val heapDumpFile = testFolder.newFile()
    // A working set from an earlier run, which is what makes dropping the read dangerous rather than merely
    // slow: writing a list worked out from an unread one takes the star off everything that was in it.
    runBlocking { starsOf(root, heapDumpFile).toggle(ALREADY_STARRED) }

    val nextRun = starsOf(root, heapDumpFile)
    runBlocking { nextRun.toggle(OBJECT_ID) }

    assertThat(nextRun.objectIds).containsExactly(ALREADY_STARRED, OBJECT_ID)
  }

  @Test fun `a star is not written when the file cannot be read`() {
    val stars = starsOf(testFolder.newFolder())
    // A read that fails rather than one that hasn't happened, which is what the guard in toggle is left for:
    // the list is unknown, so writing one worked out from it would still take the star off whatever is there.
    stars.file.writeText(exactHexObjectId(ALREADY_STARRED))
    check(stars.file.setReadable(false)) { "Could not make ${stars.file} unreadable" }
    assumeTrue("Running as a user that reads an unreadable file", !stars.file.canRead())

    runBlocking { stars.toggle(OBJECT_ID) }

    assertThat(stars.objectIds).isEmpty()
    assertThat(log).anyMatch { it.startsWith("Not starring") }
  }

  private fun starsOf(
    root: File,
    heapDumpFile: File = testFolder.newFile()
  ) = DiveStars(root).of(heapDumpFile)

  private companion object {
    const val OBJECT_ID = 0x12d368b8L
    const val ALREADY_STARRED = 0x12d00bf0L
  }
}
