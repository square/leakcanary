package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder
import java.io.File
import okio.Okio
import okio.buffer
import okio.sink
import shark.GcRoot.JavaFrame
import shark.GcRoot.ThreadObject
import shark.HeapObject.HeapInstance
import shark.HprofRecord.HeapDumpEndRecord
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader

class RetainedSizeTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun emptyLeakingInstance() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {}
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    assertThat(retainedSize).isEqualTo(0)
  }

  @Test fun leakingInstanceWithPrimitiveType() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = LongHolder(42)
        }
      }
    }
    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 8 bytes for long
    assertThat(retainedSize).isEqualTo(8)
  }

  @Test fun leakingInstanceWithPrimitiveArray() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = "42".charArrayDump
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference, 2 bytes per char
    assertThat(retainedSize).isEqualTo(8)
  }

  @Test fun leakingInstanceWithString() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = string("42")
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference, string (4 array ref + 4 int + 2 byte per char)
    assertThat(retainedSize).isEqualTo(16)
  }

  @Test fun leakingInstanceWithInstance() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = "FortyTwo" instance {
            field["number"] = IntHolder(42)
          }
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference + 4 byte int
    assertThat(retainedSize).isEqualTo(8)
  }

  @Test fun leakingInstanceWithPrimitiveWrapper() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = "java.lang.Integer" instance {
            field["value"] = IntHolder(42)
          }
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference, int field
    assertThat(retainedSize).isEqualTo(8)
  }

  @Test fun leakingInstanceWithPrimitiveWrapperArray() {
    hprofFile.dump {
      val intWrapperClass = clazz("java.lang.Integer", fields = listOf("value" to IntHolder::class))

      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = objectArrayOf(
            intWrapperClass,
            instance(
              intWrapperClass,
              fields = listOf<ValueHolder>(IntHolder(4))
            ),
            instance(
              intWrapperClass,
              fields = listOf<ValueHolder>(IntHolder(2))
            )
          )
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference * 3, 2 ints
    assertThat(retainedSize).isEqualTo(20)
  }

  @Test fun leakingInstanceWithObjectArray() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = objectArray("Forty" instance {}, "Two" instance {})
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference, 4 bytes per object entry
    assertThat(retainedSize).isEqualTo(12)
  }

  @Test fun leakingInstanceWithDeepRetainedObjects() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = "Forty" instance {
            field["forty"] = "Two" instance {
              field["two"] = string("42")
            }
          }
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference * 3, string (4 array ref + 4 int + 2 byte per char)
    assertThat(retainedSize).isEqualTo(24)
  }

  @Test fun leakingInstanceNotDominating() {
    hprofFile.dump {
      val fortyTwo = string("42")
      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = fortyTwo
        }
        staticField["rootDominator"] = fortyTwo
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference
    assertThat(retainedSize).isEqualTo(4)
  }

  @Test fun leakingInstanceWithSuperClass() {
    hprofFile.dump {
      val parentClass = clazz("Parent", fields = listOf("value" to LongHolder::class))
      val childClass =
        clazz("Child", superclassId = parentClass, fields = listOf("value" to IntHolder::class))

      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["answer"] = instance(childClass, listOf(LongHolder(42), IntHolder(42)))
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference + Long + Int
    assertThat(retainedSize).isEqualTo(16)
  }

  @Test fun leakingInstanceDominatedByOther() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "GrandParentLeaking" watchedInstance {
          field["answer"] = ShortHolder(42)
          field["child"] = "ParentLeaking" watchedInstance {
            field["answer"] = IntHolder(42)
            field["child"] = "ChildLeaking" watchedInstance {
              field["answer"] = LongHolder(42)
            }
          }
        }
      }
    }

    val retainedInstances = retainedInstances()
    require(retainedInstances.size == 1)

    val instance = retainedInstances[0]

    assertThat(instance.leakTraces.first().leakingObject.className).isEqualTo("GrandParentLeaking")
    // 4 bytes per ref * 2 + short + int + long
    assertThat(instance.totalRetainedHeapByteSize).isEqualTo(22)
  }

  @Test fun crossDominatedIsNotDominated() {
    hprofFile.dump {
      val fortyTwo = string("42")
      "GcRoot1" clazz {
        staticField["shortestPath"] = "Leaking1" watchedInstance {
          field["answer"] = fortyTwo
        }
      }
      "GcRoot2" clazz {
        staticField["shortestPath"] = "Leaking2" watchedInstance {
          field["answer"] = fortyTwo
        }
      }
    }

    val retainedInstances = retainedInstances()
    require(retainedInstances.size == 2)

    retainedInstances.forEach { instance ->
      // 4 byte reference
      assertThat(instance.totalRetainedHeapByteSize).isEqualTo(4)
    }
  }

  @Test fun `Updating first dominator through longer path after already updated to common ancestor denominator removes retained size`() {
    hprofFile.dump {
      val answer = string("42")
      val life = "com.example.Life" instance { field["answer"] = answer }
      val universe = "com.example.Universe" instance { field["answer"] = answer }
      val everything = "com.example.Everything" watchedInstance {
        field["life"] = life
        field["universe"] = universe
      }
      val fiber = "com.example.Fiber" instance { field["life"] = life }
      val towel = "com.example.Towel" instance { field["fiber"] = fiber }
      "Hitchhiker" clazz {
        staticField["guide"] = everything
        staticField["practicalTool"] = towel
      }
    }

    val everythingInstanceLeak = retainedInstances().single().leakTraces.first()
    // Only "Universe" is dominated by "Everything", the watched instance.
    // shallow size for "everything" is 2 refs (life + universe) => 8 bytes
    // Universe has one ref: 4 bytes => 8 + 4 = 12.
    // Instead we get 24 because the incorrect algorithm is including the answer string
    // which is 4 bytes for int size, 2 bytes per char => 12 bytes
    assertThat(everythingInstanceLeak.retainedObjectCount).isEqualTo(2)
  }

  @Test fun nativeSizeAccountedFor() {
    val width = 24
    val height = 16
    // pixel count * 4 bytes per pixel (ARGB_8888)
    val nativeBitmapSize = width * height * 4

    hprofFile.dump {
      val bitmap = "android.graphics.Bitmap" instance {
        field["mWidth"] = IntHolder(width)
        field["mHeight"] = IntHolder(height)
      }

      val referenceClass =
        clazz("java.lang.ref.Reference", fields = listOf("referent" to ReferenceHolder::class))
      val cleanerClass = clazz(
        "sun.misc.Cleaner", clazz("java.lang.ref.PhantomReference", referenceClass),
        fields = listOf("thunk" to ReferenceHolder::class)
      )

      instance(
        cleanerClass,
        fields = listOf("libcore.util.NativeAllocationRegistry\$CleanerThunk" instance {
          field["this\$0"] = "libcore.util.NativeAllocationRegistry" instance {
            field["size"] = LongHolder(nativeBitmapSize.toLong())
          }
        }, bitmap)
      )

      "GcRoot" clazz {
        staticField["shortestPath"] = "Leaking" watchedInstance {
          field["bitmap"] = bitmap
        }
      }
    }

    val retainedSize = retainedInstances()
      .firstRetainedSize()

    // 4 byte reference + 2 * Int + native size
    assertThat(retainedSize).isEqualTo(12 + nativeBitmapSize)
  }

  @Test fun `thread retained size includes java local references`() {
    hprofFile.dump {
      val threadInstance = Thread::class.java.name instance { }
      gcRoot(
        ThreadObject(
          id = threadInstance.value,
          threadSerialNumber = 42,
          stackTraceSerialNumber = 0
        )
      )
      val longArrayId = primitiveLongArray(LongArray(3))
      gcRoot(JavaFrame(id = longArrayId, threadSerialNumber = 42, frameNumber = 0))
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysis>(
      computeRetainedHeapSize = true,
      leakingObjectFinder = FilteringLeakingObjectFinder(listOf(FilteringLeakingObjectFinder.LeakingObjectFilter { heapObject ->
        heapObject is HeapInstance &&
          heapObject.instanceClassName == Thread::class.java.name
      }))
    )
    println(analysis.toString())
    analysis as HeapAnalysisSuccess
    val retainedInstances = analysis.applicationLeaks
    val retainedSize = retainedInstances.firstRetainedSize()

    // LongArray(3), 8 bytes per long
    assertThat(retainedSize).isEqualTo(3 * 8)
  }

  private fun retainedInstances(): List<Leak> {
    val analysis = hprofFile.checkForLeaks<HeapAnalysis>(computeRetainedHeapSize = true)
    println(analysis.toString())
    analysis as HeapAnalysisSuccess
    return analysis.applicationLeaks
  }

  private fun List<Leak>.firstRetainedSize(): Int {
    return map { it.totalRetainedHeapByteSize!! }
      .first()
  }
}
