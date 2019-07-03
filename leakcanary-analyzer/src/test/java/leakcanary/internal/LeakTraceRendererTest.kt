package leakcanary.internal

import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.HeapAnalysisSuccess
import leakcanary.HprofGraph
import leakcanary.LeakTraceElementReporter
import leakcanary.LeakTraceInspector
import leakcanary.LeakingInstance
import leakcanary.forEachInstanceOf
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
    │    Leaking: NO (it's a GC root)
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
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
          leakTraceInspectors = listOf(object : LeakTraceInspector {
            override fun inspect(
              graph: HprofGraph,
              leakTrace: List<LeakTraceElementReporter>
            ) {
              leakTrace.forEachInstanceOf("ClassB") {
                reportLeaking("because reasons")
              }
            }
          })
      )

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: NO (it's a GC root)
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA
    │    Leaking: UNKNOWN
    │    ↓ ClassA.instanceB
    │             ~~~~~~~~~
    ├─ ClassB
    │    Leaking: YES (because reasons)
    │    ↓ ClassB.leak
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
    """
  }

  @Test fun rendersLabelsOnAllNodes() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        leakTraceInspectors = listOf(object : LeakTraceInspector {
          override fun inspect(
            graph: HprofGraph,
            leakTrace: List<LeakTraceElementReporter>
          ) {
            leakTrace.forEach { reporter ->
              reporter.addLabel("¯\\_(ツ)_/¯")
            }
          }

        })
    )

    analysis renders """
    ┬
    ├─ GcRoot
    │    Leaking: NO (it's a GC root)
    │    ¯\_(ツ)_/¯
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
    ​     ¯\_(ツ)_/¯
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
    │    Leaking: NO (it's a GC root)
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA
    │    Leaking: UNKNOWN
    │    Matches exclusion field ClassA#leak
    │    ↓ ClassA.leak
    │             ~~~~
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
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
    │    Leaking: NO (it's a GC root)
    │    ↓ static GcRoot.array
    │                    ~~~~~
    ├─ java.lang.Object[]
    │    Leaking: UNKNOWN
    │    ↓ array Object[].[0]
    │                     ~~~
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
    """
  }

  @Test fun rendersThread() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    analysis renders """
    ┬
    ├─ MyThread
    │    Leaking: NO (it's a GC root)
    │    ↓ thread MyThread.<Java Local>
    │                      ~~~~~~~~~~~~
    ╰→ Leaking
    ​     Leaking: YES (RefWatcher was watching this)
    """
  }

  private infix fun HeapAnalysisSuccess.renders(expectedString: String) {
    val leak = retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.renderToString()).isEqualTo(
        expectedString.trimIndent()
    )
  }
}