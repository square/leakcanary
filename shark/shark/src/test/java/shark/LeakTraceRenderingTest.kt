package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.HeapObject.HeapInstance
import shark.ReferencePattern.Companion.instanceField
import shark.ReferencePattern.Companion.staticField
import shark.ValueHolder.ReferenceHolder

class LeakTraceRenderingTest {

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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
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
              stuckReasons += "because reasons"
            }
          }
        }), leakingObjectFinder = FilteringLeakingObjectFinder(
        listOf(LeakingObjectFilter { heapObject ->
          heapObject is HeapInstance && heapObject instanceOf "ClassB"
        })
      )
      )

    analysis renders """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA instance
    │    Verdict: Unknown
    │    ↓ ClassA.instanceB
    │             ~~~~~~~~~
    ╰→ ClassB instance
    ​     Verdict: Stuck (because reasons)
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ¯\_(ツ)_/¯
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
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
          instanceField(
            className = "ClassA",
            fieldName = "leak"
          ).leak()
        )
      )

    analysis rendersLibraryLeak """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA instance
    │    Verdict: Unknown
    │    Library leak match: instance field ClassA#leak
    │    ↓ ClassA.leak
    │             ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersRootExclusion() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        referenceMatchers = listOf(
          staticField(
            className = "GcRoot",
            fieldName = "leak"
          ).leak()
        )
      )

    analysis rendersLibraryLeak """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    Library leak match: static field GcRoot#leak
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.array
    │                    ~~~~~
    ├─ java.lang.Object[] array
    │    Verdict: Unknown
    │    ↓ Object[0]
    │            ~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun rendersThread() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = ObjectInspectors.jdkDefaults
      )

    analysis renders """
    ┬───
    │ GC Root: Thread object
    │
    ├─ MyThread instance
    │    Verdict: Unknown
    │    Thread name: 'kroutine'
    │    ↓ MyThread<Java Local>
    │              ~~~~~~~~~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun renderFieldFromSuperClass() {
    hprofFile.dump {
      val leakingInstance = "Leaking" watchedInstance {}
      val parentClassId =
        clazz("com.ParentClass", fields = listOf("leak" to ReferenceHolder::class))
      val childClassId = clazz("com.ChildClass", superclassId = parentClassId)
      "GcRoot" clazz {
        staticField["child"] = instance(childClassId, listOf(leakingInstance))
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    analysis renders """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.child
    │                    ~~~~~
    ├─ com.ChildClass instance
    │    Verdict: Unknown
    │    ↓ ParentClass.leak
    │                  ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
        """
  }

  @Test fun rendersRetainedSize() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(computeRetainedHeapSize = true)

    analysis renders """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Verdict: Unknown
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
    ​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended)
    ​     Retaining 0 B in 1 objects
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  private infix fun HeapAnalysisSuccess.renders(expectedString: String) {
    assertThat(applicationLeaks[0].leakTraces.first().toString()).isEqualTo(
      expectedString.trimIndent()
    )
  }

  private infix fun HeapAnalysisSuccess.rendersLibraryLeak(expectedString: String) {
    assertThat(libraryLeaks[0].leakTraces.first().toString()).isEqualTo(
      expectedString.trimIndent()
    )
  }
}
