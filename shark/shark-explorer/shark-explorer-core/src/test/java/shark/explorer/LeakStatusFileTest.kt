package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LeakStatusFileTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a heap dump nobody has set a status on has none`() {
    assertThat(statusFile("heap.hprof").read().isEmpty).isTrue()
  }

  @Test fun `what was set is read back`() {
    val file = statusFile("heap.hprof")

    file.write(LeakStatusOverrides.of(listOf(override(HOLDER_ID, LeakStatus.LEAKING, "the screen is gone"))))

    val read = file.read()[HOLDER_ID]!!
    assertThat(read.status).isEqualTo(LeakStatus.LEAKING)
    assertThat(read.reason).isEqualTo("the screen is gone")
  }

  @Test fun `the same heap dump is the same statuses in another run`() {
    statusFile("heap.hprof").write(LeakStatusOverrides.of(listOf(override(HOLDER_ID))))

    assertThat(statusFile("heap.hprof").read()[HOLDER_ID]).isNotNull()
  }

  /** Two runs of one app produce two dumps of one name, and they are two investigations. */
  @Test fun `two heap dumps of one name in two directories are two sets of statuses`() {
    statusFile("dumps/monday/heap.hprof").write(LeakStatusOverrides.of(listOf(override(HOLDER_ID, reason = "Monday"))))

    assertThat(statusFile("dumps/tuesday/heap.hprof").read().isEmpty).isTrue()
  }

  /**
   * An object address is unsigned, and half the addresses of a 64 bit heap dump don't fit in a signed long
   * the right way round. One written as a negative number and read back as another object would be a status
   * quietly moved onto something else.
   */
  @Test fun `an object above the middle of the address space is the object it was set on`() {
    val file = statusFile("heap.hprof")

    file.write(LeakStatusOverrides.of(listOf(override(HIGH_ID))))

    assertThat(file.read().all.single().objectId).isEqualTo(HIGH_ID)
  }

  @Test fun `a reason with tabs and newlines in it comes back as it was typed`() {
    val typed = "Two things:\n\t- a back slash \\n, which is not a newline\r\n\t- and a tab\there"
    val file = statusFile("heap.hprof")

    file.write(LeakStatusOverrides.of(listOf(override(HOLDER_ID, reason = typed))))

    assertThat(file.read()[HOLDER_ID]!!.reason).isEqualTo(typed)
    // Because a line that wrapped would be a line the next one is read from.
    assertThat(file.file.readLines().last { it.isNotBlank() }).contains("\\n", "\\t", "\\\\n")
  }

  @Test fun `the last status taken off is the file deleted`() {
    val file = statusFile("heap.hprof")
    file.write(LeakStatusOverrides.of(listOf(override(HOLDER_ID))))

    file.write(LeakStatusOverrides.NONE)

    assertThat(file.file.exists()).isFalse()
    assertThat(file.read().isEmpty).isTrue()
  }

  /** So that the file two runs write for the same statuses is the same file, in a diff and in an issue. */
  @Test fun `the statuses are written in one order however they were set`() {
    val one = statusFile("dumps/one/heap.hprof")
    val other = statusFile("dumps/other/heap.hprof")

    one.write(LeakStatusOverrides.of(listOf(override(HOLDER_ID), override(PAYLOAD_ID))))
    other.write(LeakStatusOverrides.of(listOf(override(PAYLOAD_ID), override(HOLDER_ID))))

    assertThat(one.file.readText()).isEqualTo(other.file.readText())
  }

  /** This file is hand editable on purpose, so one typo in it must not be a heap dump whose statuses went. */
  @Test fun `a line that cannot be read is skipped and the rest are kept`() {
    val file = statusFile("heap.hprof")
    file.write(LeakStatusOverrides.of(listOf(override(HOLDER_ID), override(PAYLOAD_ID))))
    file.file.writeText(
      file.file.readText() +
        "not an address\tLEAKING\ttyped over the address\n" +
        "0x1\tSORT_OF_LEAKING\tno such status\n" +
        "0x2\tLEAKING\n" +
        "0x3\tLEAKING\t   \n" +
        "\n" +
        "# A comment somebody left\n"
    )

    assertThat(file.read().all.map { it.objectId }).containsExactlyInAnyOrder(HOLDER_ID, PAYLOAD_ID)
  }

  @Test fun `a status with no reason is not a status`() {
    assertThatIllegalArgumentException().isThrownBy {
      LeakStatusOverride(objectId = HOLDER_ID, status = LeakStatus.LEAKING, reason = "  ")
    }.withMessageContaining("no reason")
  }

  /** So that they can be read, edited and pasted from without going through this app. */
  @Test fun `the statuses are one file named after the heap dump`() {
    assertThat(statusFile("large-dump.hprof").file.name)
      .startsWith("large-dump.hprof")
      .endsWith(".leak-statuses.tsv")
  }

  private fun statusFile(heapDumpPath: String) =
    LeakStatusFile(statusesRoot, File(testFolder.root, heapDumpPath))

  private fun override(
    objectId: Long,
    status: LeakStatus = LeakStatus.LEAKING,
    reason: String = "because I read the code"
  ) = LeakStatusOverride(objectId = objectId, status = status, reason = reason)

  private val statusesRoot by lazy { testFolder.newFolder("leak-statuses") }

  companion object {
    private const val HOLDER_ID = 0x82182c00L
    private const val PAYLOAD_ID = 0x1234L

    /** Which is what a `long` holds an address of `0xffff…` as. */
    private const val HIGH_ID = -0x1234L
  }
}
