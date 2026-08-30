package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StarredFileTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a heap dump nobody has starred anything in has nothing starred`() {
    assertThat(starredFile("heap.hprof").read()).isEmpty()
  }

  @Test fun `what was starred is read back`() {
    val file = starredFile("heap.hprof")

    file.write(listOf(HOLDER_ID, PAYLOAD_ID))

    assertThat(file.read()).containsExactly(HOLDER_ID, PAYLOAD_ID)
  }

  @Test fun `the same heap dump is the same objects in another run`() {
    starredFile("heap.hprof").write(listOf(HOLDER_ID))

    assertThat(starredFile("heap.hprof").read()).containsExactly(HOLDER_ID)
  }

  /** Two runs of one app produce two dumps of one name, and they are two investigations. */
  @Test fun `two heap dumps of one name in two directories are two working sets`() {
    starredFile("dumps/monday/heap.hprof").write(listOf(HOLDER_ID))

    assertThat(starredFile("dumps/tuesday/heap.hprof").read()).isEmpty()
  }

  /**
   * Starring is how a handful of objects are held side by side while being compared, so the list somebody
   * built is the list they expect to come back to — not the same list sorted by something.
   */
  @Test fun `they come back in the order they were starred`() {
    val file = starredFile("heap.hprof")

    file.write(listOf(PAYLOAD_ID, HIGH_ID, HOLDER_ID))

    assertThat(file.read()).containsExactly(PAYLOAD_ID, HIGH_ID, HOLDER_ID)
  }

  /**
   * An object address is unsigned, and half the addresses of a 64 bit heap dump don't fit in a signed long
   * the right way round. One written as a negative number and read back as another object would be a star
   * quietly moved onto something else.
   */
  @Test fun `an object above the middle of the address space is the object it was starred on`() {
    val file = starredFile("heap.hprof")

    file.write(listOf(HIGH_ID))

    assertThat(file.read()).containsExactly(HIGH_ID)
  }

  @Test fun `the last star taken off is the file deleted`() {
    val file = starredFile("heap.hprof")
    file.write(listOf(HOLDER_ID))

    file.write(emptyList())

    assertThat(file.file.exists()).isFalse()
    assertThat(file.read()).isEmpty()
  }

  /** This file is hand editable on purpose, so one typo in it must not be a working set that went. */
  @Test fun `a line that cannot be read is skipped and the rest are kept`() {
    val file = starredFile("heap.hprof")
    file.write(listOf(HOLDER_ID, PAYLOAD_ID))
    file.file.writeText(
      file.file.readText() +
        "not an address\n" +
        "0xnope\n" +
        "\n" +
        "# A comment somebody left\n"
    )

    assertThat(file.read()).containsExactly(HOLDER_ID, PAYLOAD_ID)
  }

  /** Because two rows for one object is two rows that scroll, select and open as one. */
  @Test fun `an address listed twice is one starred object`() {
    val file = starredFile("heap.hprof")
    file.write(listOf(HOLDER_ID))
    file.file.appendText("${exactHexObjectId(HOLDER_ID)}\n")

    assertThat(file.read()).containsExactly(HOLDER_ID)
  }

  /** So that a working set can be read, mailed and pasted from without going through this app. */
  @Test fun `the starred objects are one file named after the heap dump`() {
    assertThat(starredFile("large-dump.hprof").file.name)
      .startsWith("large-dump.hprof")
      .endsWith(".starred.txt")
  }

  private fun starredFile(heapDumpPath: String) =
    StarredFile(starredRoot, File(testFolder.root, heapDumpPath))

  private val starredRoot by lazy { testFolder.newFolder("starred") }

  companion object {
    private const val HOLDER_ID = 0x82182c00L
    private const val PAYLOAD_ID = 0x1234L

    /** Which is what a `long` holds an address of `0xffff…` as. */
    private const val HIGH_ID = -0x1234L
  }
}
