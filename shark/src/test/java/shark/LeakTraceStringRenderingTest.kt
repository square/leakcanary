package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.HeapObject.HeapInstance
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.StaticFieldPattern
import shark.ValueHolder.ReferenceHolder
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
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
          , leakFilters = listOf(object : LeakingObjectFilter {
        override fun isLeakingObject(heapObject: HeapObject) =
          heapObject is HeapInstance && heapObject instanceOf "ClassB"
      })
      )

    analysis renders """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA instance
    │    Leaking: UNKNOWN
    │    ↓ ClassA.instanceB
    │             ~~~~~~~~~
    ╰→ ClassB instance
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    ¯\_(ツ)_/¯
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    ↓ static GcRoot.instanceA
    │                    ~~~~~~~~~
    ├─ ClassA instance
    │    Leaking: UNKNOWN
    │    Library leak match: instance field ClassA#leak
    │    ↓ ClassA.leak
    │             ~~~~
    ╰→ Leaking instance
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
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
              LibraryLeakReferenceMatcher(pattern = StaticFieldPattern("GcRoot", "leak"))
          )
      )

    analysis rendersLibraryLeak """
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    Library leak match: static field GcRoot#leak
    │    ↓ static GcRoot.leak
    │                    ~~~~
    ╰→ Leaking instance
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
    ┬───
    │ GC Root: System class
    │
    ├─ GcRoot class
    │    Leaking: UNKNOWN
    │    ↓ static GcRoot.array
    │                    ~~~~~
    ├─ java.lang.Object[] array
    │    Leaking: UNKNOWN
    │    ↓ Object[].[0]
    │               ~~~
    ╰→ Leaking instance
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
    ┬───
    │ GC Root: Java local variable
    │
    ├─ MyThread thread
    │    Leaking: UNKNOWN
    │    ↓ MyThread.<Java Local>
    │               ~~~~~~~~~~~~
    ╰→ Leaking instance
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    ​     key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    ​     watchDurationMillis = 25000
    ​     retainedDurationMillis = 10000
    """
  }

  @Test fun renderFieldFromSuperClass() {
    hprofFile.dump {
      val leakingInstance = "Leaking" watchedInstance {}
      val parentClassId = clazz("com.ParentClass", fields = listOf("leak" to ReferenceHolder::class))
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
    │    Leaking: UNKNOWN
    │    ↓ static GcRoot.child
    │                    ~~~~~
    ├─ com.ChildClass instance
    │    Leaking: UNKNOWN
    │    ↓ ParentClass.leak
    │                  ~~~~
    ╰→ Leaking instance
    ​     Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
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