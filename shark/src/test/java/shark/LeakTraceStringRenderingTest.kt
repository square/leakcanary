package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ReferencePattern.InstanceFieldPattern
import java.io.File

class LeakTraceStringRenderingTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun rendersSimplePath() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: UNKNOWN
    │    GC Root: System class
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersDeobfuscatedSimplePath() {
    hprofFile.dump {
      "a" clazz {
        staticField["b.c"] = "Leaking" watchedInstance {}
      }
    }

    val proguardMappingText = """
            GcRoot -> a:
                type leak -> b.c
        """.trimIndent()

    val reader = ProguardMappingReader(proguardMappingText.byteInputStream(Charsets.UTF_8))

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        proguardMapping = reader.readProguardMapping()
    )

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: UNKNOWN
    │    GC Root: System class
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersLeakingWithReason() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["instanceA"] = "ClassA" instance {
          field["instanceB"] = "ClassB" instance {
            field["leak"] = "Leaking" watchedInstance {}
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(object : ObjectInspector {
            override fun inspect(
              reporter: ObjectReporter
            ) {
              reporter.whenInstanceOf("ClassB") {
                leakingReasons += "because reasons"
              }
            }
          })
      )

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: UNKNOWN
    │    GC Root: System class
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA
    │    Leaking: UNKNOWN
    │    ↓ ClassA.instanceB
    │             ~~~~~~~~~
    ╰→ ClassB
    ​     Leaking: YES (because reasons)
    """
  }

  @Test fun rendersLabelsOnAllNodes() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(object : ObjectInspector {
          override fun inspect(
            reporter: ObjectReporter
          ) {
            reporter.labels += "¯\\_(ツ)_/¯"
          }

        })
    )

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: UNKNOWN
    │    ¯\_(ツ)_/¯
    │    GC Root: System class
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     ¯\_(ツ)_/¯
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersExclusion() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["instanceA"] = "ClassA" instance {
          field["leak"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          referenceMatchers = listOf(
              LibraryLeakReferenceMatcher(pattern = InstanceFieldPattern("ClassA", "leak"))
          )
      )

    analysis rendersLibraryLeak """
    ┬
    ├─ GcRoot
    │    Leaking: UNKNOWN
    │    GC Root: System class
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA
    │    Leaking: UNKNOWN
    │    ↓ ClassA.leak
    │             ~~~~
    ╰→ Leaking
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersArray() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["array"] = objectArray("Leaking" watchedInstance {})
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: UNKNOWN
    │    GC Root: System class
    │    ↓ static GcRoot.array
    │                    ~~~~~
    ├─ java.lang.Object[]
    │    Leaking: UNKNOWN
    │    ↓ array Object[].[0]
    │                     ~~~
    ╰→ Leaking
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersThread() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    analysis renders """
    ┬
    ├─ MyThread
    │    Leaking: UNKNOWN
    │    GC Root: Java local variable
    │    ↓ thread MyThread.<Java Local>
    │                      ~~~~~~~~~~~~
    ╰→ Leaking
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  private infix fun HeapAnalysisSuccess.renders(expectedString: String) {
    assertThat(applicationLeaks[0].leakTrace.toString()).isEqualTo(
        expectedString.trimIndent()
    )
  }

  private infix fun HeapAnalysisSuccess.rendersLibraryLeak(expectedString: String) {
    assertThat(libraryLeaks[0].leakTrace.toString()).isEqualTo(
        expectedString.trimIndent()
    )
  }
}