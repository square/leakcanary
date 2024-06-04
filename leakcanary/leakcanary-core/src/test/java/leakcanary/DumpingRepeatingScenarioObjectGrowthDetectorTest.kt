package leakcanary

import java.io.File
import leakcanary.HeapDumpStorageStrategy.DeleteOnHeapDumpClose
import leakcanary.HeapDumpStorageStrategy.KeepHeapDumps
import leakcanary.HeapDumpStorageStrategy.KeepHeapDumpsOnObjectsGrowing
import leakcanary.HeapDumpStorageStrategy.KeepZippedHeapDumpsOnObjectsGrowing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot
import shark.GcRootReference
import shark.JvmObjectGrowthReferenceMatchers
import shark.ObjectGrowthDetector
import shark.OpenJdkReferenceReaderFactory
import shark.dump
import shark.forJvmHeap

class DumpingRepeatingScenarioObjectGrowthDetectorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `DeleteOnHeapDumpClose deletes heap dump on indexing failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    DeleteOnHeapDumpClose().triggerIndexingFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).isEmpty()
  }

  @Test
  fun `DeleteOnHeapDumpClose deletes heap dump on traversal failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    DeleteOnHeapDumpClose().triggerTraversalFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).isEmpty()
  }

  @Test
  fun `KeepHeapDumps keeps heap dump on indexing failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    KeepHeapDumps.triggerIndexingFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).containsExactly(
      File(heapDumpDirectory, "dump-1.hprof")
    )
  }

  @Test
  fun `KeepHeapDumps keeps heap dump on traversal failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    KeepHeapDumps.triggerTraversalFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).containsExactly(
      File(heapDumpDirectory, "dump-1.hprof")
    )
  }

  @Test
  fun `KeepHeapDumpsOnObjectsGrowing keeps heap dump on indexing failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    KeepHeapDumpsOnObjectsGrowing().triggerIndexingFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).containsExactly(
      File(heapDumpDirectory, "dump-1.hprof")
    )
  }

  @Test
  fun `KeepHeapDumpsOnObjectsGrowing keeps heap dump on traversal failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    KeepHeapDumpsOnObjectsGrowing().triggerTraversalFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).containsExactly(
      File(heapDumpDirectory, "dump-1.hprof")
    )
  }

  @Test
  fun `KeepZippedHeapDumpsOnObjectsGrowing keeps zipped heap dump on indexing failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    KeepZippedHeapDumpsOnObjectsGrowing().triggerIndexingFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).containsExactly(
      File(heapDumpDirectory, "dump-1.zip")
    )
  }

  @Test
  fun `KeepZippedHeapDumpsOnObjectsGrowing keeps zipped heap dump on traversal failure`() {
    val heapDumpDirectory = tempFolder.newFolder()

    KeepZippedHeapDumpsOnObjectsGrowing().triggerTraversalFailure(heapDumpDirectory)

    assertThat(heapDumpDirectory.listFiles()).containsExactly(
      File(heapDumpDirectory, "dump-1.zip")
    )
  }

  private fun HeapDumpStorageStrategy.triggerIndexingFailure(heapDumpDirectory: File) {
    val heapDumpFiles = generateSequence(1) {
      it + 1
    }.map { File(heapDumpDirectory, "dump-$it.hprof") }.iterator()

    val detector = DumpingRepeatingScenarioObjectGrowthDetector(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeap(),
      heapDumpFileProvider = {
        heapDumpFiles.next()
      },
      heapDumper = {
        // bad heap dump
        it.writeText("I like turtles")
      },
      heapDumpStorageStrategy = this,
    )


    try {
      detector.findRepeatedlyGrowingObjects {
      }
    } catch (ignored: Throwable) {
    }
  }

  private fun HeapDumpStorageStrategy.triggerTraversalFailure(heapDumpDirectory: File) {
    val unknownObjectId = 42L
    val heapDumpFiles = generateSequence(1) {
      it + 1
    }.map { File(heapDumpDirectory, "dump-$it.hprof") }.iterator()

    val referenceMatchers = JvmObjectGrowthReferenceMatchers.defaults
    val detector = DumpingRepeatingScenarioObjectGrowthDetector(
      objectGrowthDetector = ObjectGrowthDetector(
        gcRootProvider = {
          // Fake GC Root
          sequenceOf(GcRootReference(GcRoot.StickyClass(unknownObjectId), false, null))
        },
        referenceReaderFactory = OpenJdkReferenceReaderFactory(referenceMatchers)
      ),
      heapDumpFileProvider = {
        heapDumpFiles.next()
      },
      heapDumper = {
        // bad heap dump
        it.dump { }
      },
      heapDumpStorageStrategy = this,
    )

    assertThatThrownBy {
      detector.findRepeatedlyGrowingObjects {
      }
    }.hasMessageContaining(unknownObjectId.toString())
  }
}
