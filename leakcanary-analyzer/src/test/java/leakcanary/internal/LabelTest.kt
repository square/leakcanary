package leakcanary.internal

import leakcanary.HeapAnalysisSuccess
import leakcanary.HprofParser
import leakcanary.LeakNode
import leakcanary.LeakingInstance
import leakcanary.ObjectIdMetadata.STRING
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LabelTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun stringContentAsLabel() {
    hprofFile.writeSinglePathToString("World")

    val labeler = { parser: HprofParser,
      node: LeakNode
      ->
      if (parser.objectIdMetadata(node.instance) == STRING) {
        listOf("Hello ${parser.retrieveStringById(node.instance)}")
      } else emptyList()

    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(labelers = listOf(labeler))

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.last().labels).isEqualTo(listOf("Hello World"))
  }

}