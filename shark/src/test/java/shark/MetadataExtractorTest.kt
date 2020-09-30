package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MetadataExtractorTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun extractStaticStringField() {
    hprofFile.dump {
      val helloString = string("Hello")
      clazz(
          "World", staticFields = listOf(
          "message" to helloString
      )
      )
    }

    val extractor = object : MetadataExtractor {
      override fun extractMetadata(graph: HeapGraph): Map<String, String> {
        val message =
          graph.findClassByName("World")!!["message"]!!.valueAsInstance!!.readAsJavaString()!!
        return mapOf("World message" to message)
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(metadataExtractor = extractor)

    val metadata = analysis.metadata

    assertThat(metadata).containsAllEntriesOf(mapOf("World message" to "Hello"))
  }
}