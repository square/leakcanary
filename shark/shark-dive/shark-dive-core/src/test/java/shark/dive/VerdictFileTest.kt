package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerdictFileTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a heap dump nobody has set a verdict on has none`() {
    assertThat(verdictFile("heap.hprof").read().isEmpty).isTrue()
  }

  @Test fun `what was set is read back`() {
    val file = verdictFile("heap.hprof")

    file.write(VerdictOverrides.of(listOf(override(HOLDER_ID, Verdict.STUCK, "the screen is gone"))))

    val read = file.read()[HOLDER_ID]!!
    assertThat(read.verdict).isEqualTo(Verdict.STUCK)
    assertThat(read.reason).isEqualTo("the screen is gone")
  }

  @Test fun `the same heap dump is the same verdicts in another run`() {
    verdictFile("heap.hprof").write(VerdictOverrides.of(listOf(override(HOLDER_ID))))

    assertThat(verdictFile("heap.hprof").read()[HOLDER_ID]).isNotNull()
  }

  /** Two runs of one app produce two dumps of one name, and they are two investigations. */
  @Test fun `two heap dumps of one name in two directories are two sets of verdicts`() {
    verdictFile("dumps/monday/heap.hprof").write(VerdictOverrides.of(listOf(override(HOLDER_ID, reason = "Monday"))))

    assertThat(verdictFile("dumps/tuesday/heap.hprof").read().isEmpty).isTrue()
  }

  /**
   * An object address is unsigned, and half the addresses of a 64 bit heap dump don't fit in a signed long
   * the right way round. One written as a negative number and read back as another object would be a verdict
   * quietly moved onto something else.
   */
  @Test fun `an object above the middle of the address space is the object it was set on`() {
    val file = verdictFile("heap.hprof")

    file.write(VerdictOverrides.of(listOf(override(HIGH_ID))))

    assertThat(file.read().all.single().objectId).isEqualTo(HIGH_ID)
  }

  @Test fun `a reason with tabs and newlines in it comes back as it was typed`() {
    val typed = "Two things:\n\t- a back slash \\n, which is not a newline\r\n\t- and a tab\there"
    val file = verdictFile("heap.hprof")

    file.write(VerdictOverrides.of(listOf(override(HOLDER_ID, reason = typed))))

    assertThat(file.read()[HOLDER_ID]!!.reason).isEqualTo(typed)
    // Because a line that wrapped would be a line the next one is read from.
    assertThat(file.file.readLines().last { it.isNotBlank() }).contains("\\n", "\\t", "\\\\n")
  }

  @Test fun `the last verdict taken off is the file deleted`() {
    val file = verdictFile("heap.hprof")
    file.write(VerdictOverrides.of(listOf(override(HOLDER_ID))))

    file.write(VerdictOverrides.NONE)

    assertThat(file.file.exists()).isFalse()
    assertThat(file.read().isEmpty).isTrue()
  }

  /** So that the file two runs write for the same verdicts is the same file, in a diff and in an issue. */
  @Test fun `the verdicts are written in one order however they were set`() {
    val one = verdictFile("dumps/one/heap.hprof")
    val other = verdictFile("dumps/other/heap.hprof")

    one.write(VerdictOverrides.of(listOf(override(HOLDER_ID), override(PAYLOAD_ID))))
    other.write(VerdictOverrides.of(listOf(override(PAYLOAD_ID), override(HOLDER_ID))))

    assertThat(one.file.readText()).isEqualTo(other.file.readText())
  }

  /** This file is hand editable on purpose, so one typo in it must not be a heap dump whose verdicts went. */
  @Test fun `a line that cannot be read is skipped and the rest are kept`() {
    val file = verdictFile("heap.hprof")
    file.write(VerdictOverrides.of(listOf(override(HOLDER_ID), override(PAYLOAD_ID))))
    file.file.writeText(
      file.file.readText() +
        "not an address\tLEAKING\ttyped over the address\n" +
        "0x1\tSORT_OF_LEAKING\tno such verdict\n" +
        "0x2\tLEAKING\n" +
        "0x3\tLEAKING\t   \n" +
        "\n" +
        "# A comment somebody left\n"
    )

    assertThat(file.read().all.map { it.objectId }).containsExactlyInAnyOrder(HOLDER_ID, PAYLOAD_ID)
  }

  @Test fun `a verdict with no reason is not a verdict`() {
    assertThatIllegalArgumentException().isThrownBy {
      VerdictOverride(objectId = HOLDER_ID, verdict = Verdict.STUCK, reason = "  ")
    }.withMessageContaining("no reason")
  }

  /** So that they can be read, edited and pasted from without going through this app. */
  @Test fun `the verdicts are one file named after the heap dump`() {
    assertThat(verdictFile("large-dump.hprof").file.name)
      .startsWith("large-dump.hprof")
      .endsWith(".verdicts.tsv")
  }

  private fun verdictFile(heapDumpPath: String) =
    VerdictFile(verdictsRoot, File(testFolder.root, heapDumpPath))

  private fun override(
    objectId: Long,
    verdict: Verdict = Verdict.STUCK,
    reason: String = "because I read the code"
  ) = VerdictOverride(objectId = objectId, verdict = verdict, reason = reason)

  private val verdictsRoot by lazy { testFolder.newFolder("verdicts") }

  companion object {
    private const val HOLDER_ID = 0x82182c00L
    private const val PAYLOAD_ID = 0x1234L

    /** Which is what a `long` holds an address of `0xffff…` as. */
    private const val HIGH_ID = -0x1234L
  }
}
