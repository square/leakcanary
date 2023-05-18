package leakcanary

import android.os.Build.VERSION.SDK_INT
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.hexIdentityHashCode

class AndroidExtensionsTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun identityHashCode() {
    assumeTrue("SDK_INT is $SDK_INT, shadow\$_monitor_ was introduced in 21", SDK_INT >= 24)

    // leakcanary.AndroidExtensionsTest@c559955
    val thisToString = toString()

    val heapDumpFile = testFolder.newFile()
    AndroidDebugHeapDumper.dumpHeap(heapDumpFile)

    val testClassName = this::class.java.name

    val identityHashCodeFromDump = heapDumpFile.openHeapGraph().use { graph ->
      val testClass = graph.findClassByName(testClassName)!!
      val testInstance = testClass.instances.single()
      testInstance.hexIdentityHashCode
    }

    assertThat("$testClassName@$identityHashCodeFromDump").isEqualTo(thisToString)
  }
}
