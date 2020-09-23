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

    val retainedSize = firstRetainedSize()

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
    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

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

    val retainedSize = firstRetainedSize()

    // 4 byte reference + 2 * Int + native size
    assertThat(retainedSize).isEqualTo(12 + nativeBitmapSize)
  }

  private fun retainedInstances(): List<Leak> {
    val analysis = hprofFile.checkForLeaks<HeapAnalysis>(computeRetainedHeapSize = true)
    println(analysis.toString())
    analysis as HeapAnalysisSuccess
    return analysis.applicationLeaks.map { it }
  }

  private fun firstRetainedSize(): Int {
    return retainedInstances()
        .map { it.totalRetainedHeapByteSize!! }
        .first()
  }

}