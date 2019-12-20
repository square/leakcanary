package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.regex.Pattern

class HeapAnalysisStringRenderingTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun emptyFile() {
    val analysis = hprofFile.checkForLeaks<HeapAnalysis>()

    analysis renders """
      |====================================
      |HEAP ANALYSIS FAILED
      |
      |You can report this failure at https://github.com/square/leakcanary/issues
      |Please provide the stacktrace, metadata and the heap dump file.
      |====================================
      |STACKTRACE
      |
      |java.lang.IllegalArgumentException: Hprof file is 0 byte length
      |.*
      |====================================
      |METADATA
      |
      |Build.VERSION.SDK_INT: -1
      |Build.MANUFACTURER: Unknown
      |LeakCanary version: Unknown
      |Analysis duration: \d* ms
      |Heap dump file path: ${hprofFile.absolutePath}
      |Heap dump timestamp: \d*
      |===================================="""
  }

  @Test fun successNoLeak() {
    hprofFile.dump {
      "GcRoot" clazz {}
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysis>()


    analysis renders """
      |====================================
      |HEAP ANALYSIS RESULT
      |====================================
      |0 APPLICATION LEAKS
      |
      |References underlined with "~~~" are likely causes.
      |Learn more at https://squ.re/leaks.
      |====================================
      |0 LIBRARY LEAKS
      |
      |Library Leaks are leaks coming from the Android Framework or Google libraries.
      |====================================
      |METADATA
      |
      |Please include this in bug reports and Stack Overflow questions.
      |
      |Analysis duration: \d* ms
      |Heap dump file path: ${hprofFile.absolutePath}
      |Heap dump timestamp: \d*
      |===================================="""
  }

  @Test fun successWithLeaks() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysis>()


    analysis renders """
      |====================================
      |HEAP ANALYSIS RESULT
      |====================================
      |1 APPLICATION LEAKS
      |
      |References underlined with "~~~" are likely causes.
      |Learn more at https://squ.re/leaks.
      |
      |Signature: a49095c3373b957532aff14eb32987bb75ffe9d5
      |┬───
      |│ GC Root: System class
      |│
      |├─ GcRoot class
      |.*
      |====================================
      |0 LIBRARY LEAKS
      |
      |Library Leaks are leaks coming from the Android Framework or Google libraries.
      |====================================
      |METADATA
      |
      |Please include this in bug reports and Stack Overflow questions.
      |
      |Analysis duration: \d* ms
      |Heap dump file path: ${hprofFile.absolutePath}
      |Heap dump timestamp: \d*
      |===================================="""
  }

  private infix fun HeapAnalysis.renders(multilineRegexString: String) {
    val regex: Pattern =
      Pattern.compile("^" + multilineRegexString.trimMargin() + "$", Pattern.DOTALL)
    assertThat(toString()).matches(regex)
  }

}