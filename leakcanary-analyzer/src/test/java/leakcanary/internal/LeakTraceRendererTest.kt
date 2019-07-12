package leakcanary.internal

import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.HeapAnalysisSuccess
import leakcanary.HprofGraph
import leakcanary.ObjectInspector
import leakcanary.ObjectReporter
import leakcanary.whenInstanceOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LeakTraceRendererTest {

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
    ​     Leaking: YES (RefWatcher was watching this)
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
              graph: HprofGraph,
              reporter: ObjectReporter
            ) {
              reporter.whenInstanceOf("ClassB") {
                reportLeaking("because reasons")
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
            graph: HprofGraph,
            reporter: ObjectReporter
          ) {
            reporter.addLabel("¯\\_(ツ)_/¯")
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
    ​     Leaking: YES (RefWatcher was watching this)
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
          exclusions = listOf(Exclusion(type = InstanceFieldExclusion("ClassA", "leak")))
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
    │    Matches exclusion field ClassA#leak
    │    ↓ ClassA.leak
    │             ~~~~
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
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
    ​     Leaking: YES (RefWatcher was watching this)
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
    ​     Leaking: YES (RefWatcher was watching this)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  private infix fun HeapAnalysisSuccess.renders(expectedString: String) {
    val leak = leakingInstances[0]
    assertThat(leak.leakTrace.renderToString()).isEqualTo(
        expectedString.trimIndent()
    )
  }
}