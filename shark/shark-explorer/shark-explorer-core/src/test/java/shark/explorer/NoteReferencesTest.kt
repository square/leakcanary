package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The heap dump's half of a note: what the names and addresses written in one turn out to be. [NoteTest] is
 * the other half, and it decides what gets asked here.
 */
class NoteReferencesTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a class the heap dump has is answered with its class object`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      val classObjectId = tree.classObjectIdOrNull("com.example.Holder")!!

      val summary = tree.summarize(classObjectId)
      assertThat(summary.className).isEqualTo("com.example.Holder")
      assertThat(summary.kind).isEqualTo(HeapObjectKind.CLASS)
    }
  }

  @Test fun `a class the heap dump has not got is nothing`() {
    testFolder.openTestHeapDump().use { explorer ->
      assertThat(explorer.tree.classObjectIdOrNull("com.example.Absent")).isNull()
    }
  }

  @Test fun `an object of the heap dump is named the way every list here names one`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val holder = tree.onlyInstanceOf("Holder")

      assertThat(tree.objectNameOrNull(holder.objectId)).isEqualTo("Holder instance")
    }
  }

  @Test fun `an address the heap dump has no object at is nothing`() {
    testFolder.openTestHeapDump().use { explorer ->
      assertThat(explorer.tree.objectNameOrNull(0x1L)).isNull()
    }
  }

  /** A note is read on every keystroke and the dump asked once, for everything in it at once. */
  @Test fun `a note's mentions are answered together`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val holder = tree.onlyInstanceOf("Holder")
      val note = Note.of(
        "com.example.Holder holds ${hexObjectId(holder.objectId)}, and com.example.Absent holds 0x1"
      )

      val references = tree.referencesOf(note.mentions)

      assertThat(references.classObjectIds.keys).containsExactly("com.example.Holder")
      assertThat(references.objectNames).containsExactly(entry(holder.objectId, "Holder instance"))
    }
  }
}
