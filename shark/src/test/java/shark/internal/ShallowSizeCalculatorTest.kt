package shark.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.dump
import java.io.File

private const val EMPTY_CLASS_SIZE = 42

class ShallowSizeCalculatorTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun `empty class has instance size 0`() {
    hprofFile.dump {
      "SomeClass" instance {}
    }

    val instanceSize = hprofFile.openHeapGraph().use { graph ->
      val calculator = ShallowSizeCalculator(graph)
      calculator.computeShallowSize(
          graph.findClassByName("SomeClass")!!.instances.single().objectId
      )
    }

    assertThat(instanceSize).isEqualTo(0)
  }

  @Test fun `class with static field has instance size 0`() {
    hprofFile.dump {
      "SomeClass" instance {
        staticField["someStaticField"] = LongHolder(42)
      }
    }

    val instanceSize = hprofFile.openHeapGraph().use { graph ->
      val calculator = ShallowSizeCalculator(graph)
      calculator.computeShallowSize(
          graph.findClassByName("SomeClass")!!.instances.single().objectId
      )
    }

    assertThat(instanceSize).isEqualTo(0)
  }

  @Test fun `class with int field has instance size 4`() {
    hprofFile.dump {
      "SomeClass" instance {
        field["someIntField"] = IntHolder(42)
      }
    }

    val instanceSize = hprofFile.openHeapGraph().use { graph ->
      val calculator = ShallowSizeCalculator(graph)
      calculator.computeShallowSize(
          graph.findClassByName("SomeClass")!!.instances.single().objectId
      )
    }
    assertThat(instanceSize).isEqualTo(4)
  }

  @Test fun `empty class has size EMPTY_CLASS_SIZE`() {
    hprofFile.dump {
      "SomeClass" clazz {}
    }

    val classSize = hprofFile.openHeapGraph().use { graph ->
      val calculator = ShallowSizeCalculator(graph)
      calculator.computeShallowSize(graph.findClassByName("SomeClass")!!.objectId)
    }
    assertThat(classSize).isEqualTo(EMPTY_CLASS_SIZE)
  }

  @Test fun `class with static int field has class size that includes field`() {
    hprofFile.dump {
      "SomeClass" clazz {
        staticField["someStaticField"] = IntHolder(42)
      }
    }

    val classSize = hprofFile.openHeapGraph().use { graph ->
      val calculator = ShallowSizeCalculator(graph)
      calculator.computeShallowSize(graph.findClassByName("SomeClass")!!.objectId)
    }

    val bytesForFieldId = 4
    val bytesForFieldType = 1
    val bytesForFieldValue = 4

    assertThat(classSize).isEqualTo(
        EMPTY_CLASS_SIZE + bytesForFieldId + bytesForFieldType + bytesForFieldValue
    )
  }

}