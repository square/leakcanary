package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import java.io.File

class LeakStatusTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun gcRootClassNotLeaking() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(ObjectInspectors.CLASS)
    )

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTraces.first().referencePath.first().originObject.leakingStatus).isEqualTo(
        NOT_LEAKING
    )
  }

  @Test fun leakingInstanceLeaking() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.leakingStatus).isEqualTo(LEAKING)
  }

  @Test fun defaultsToUnknown() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(UNKNOWN)
  }

  @Test fun inspectorNotLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(notLeakingInstance("Class1"))
    )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
  }

  @Test fun inspectorLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatusReason).isEqualTo(
        "Class1 is leaking"
    )
  }

  @Test fun leakingWinsUnknown() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(LEAKING)
  }

  @Test fun notLeakingWhenNextIsNotLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingInstance("Class3"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
  }

  @Test fun leakingWhenPreviousIsLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(4)
    assertThat(leakTrace.referencePath[2].originObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(leakTrace.referencePath[2].originObject.leakingStatusReason).isEqualTo(
        "Class1↑ is leaking"
    )
  }

  @Test fun middleUnknown() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(
              notLeakingInstance("Class1"), leakingInstance("Class3")
          )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[2].originObject.leakingStatus).isEqualTo(UNKNOWN)
  }

  @Test fun gcRootClassNotLeakingConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingClass("GcRoot"), ObjectInspectors.CLASS)
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    assertThat(leakTrace.referencePath.first().originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(leakTrace.referencePath.first().originObject.leakingStatusReason).isEqualTo(
        "a class is never leaking. Conflicts with GcRoot is leaking"
    )
  }

  @Test fun gcRootClassNotLeakingAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingClass("GcRoot"), ObjectInspectors.CLASS)
      )

    println(analysis)

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    assertThat(leakTrace.referencePath.first().originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(leakTrace.referencePath.first().originObject.leakingStatusReason).isEqualTo(
        "GcRoot is not leaking and a class is never leaking"
    )
  }

  @Test fun leakingInstanceLeakingConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingInstance("Leaking"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(leakTrace.leakingObject.leakingStatusReason).isEqualTo(
        "ObjectWatcher was watching this because its lifecycle has ended. " +
            "Conflicts with Leaking is not leaking"
    )
  }

  @Test fun leakingInstanceLeakingAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Leaking"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(leakTrace.leakingObject.leakingStatusReason).isEqualTo(
        "Leaking is leaking and ObjectWatcher was watching this because its lifecycle has ended"
    )
  }

  @Test fun conflictNotLeakingWins() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(
              notLeakingInstance("Class1"), leakingInstance("Class1")
          )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    println(leakTrace)

    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(leakTrace.referencePath[1].originObject.leakingStatusReason).isEqualTo(
        "Class1 is not leaking. Conflicts with Class1 is leaking"
    )
  }

  @Test fun twoInspectorsAgreeNotLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(
              notLeakingInstance("Class1"), notLeakingInstance("Class1")
          )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(leakTrace.referencePath[1].originObject.leakingStatusReason).isEqualTo(
        "Class1 is not leaking"
    )
  }

  @Test fun twoInspectorsAgreeLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Class1"), leakingInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(leakTrace.referencePath[1].originObject.leakingStatusReason).isEqualTo(
        "Class1 is leaking"
    )
  }

  @Test fun notLeakingWhenFurtherDownIsNotLeaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingInstance("Class3"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.className).isEqualTo("Class1")
    assertThat(leakTrace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(leakTrace.referencePath[1].originObject.leakingStatusReason).isEqualTo(
        "Class3↓ is not leaking"
    )
  }

  @Test fun leakingWhenFurtherUpIsleaking() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[3].originObject.className).isEqualTo("Class3")
    assertThat(leakTrace.referencePath[3].originObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(leakTrace.referencePath[3].originObject.leakingStatusReason).isEqualTo(
        "Class1↑ is leaking"
    )
  }

  @Test fun leakCausesAreLastNotLeakingAndUnknown() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(
              notLeakingInstance("Class1"), leakingInstance("Class3")
          )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePathElementIsSuspect(0)).isFalse()
    assertThat(leakTrace.referencePathElementIsSuspect(1)).isTrue()
    assertThat(leakTrace.referencePathElementIsSuspect(2)).isTrue()
  }

  @Test fun sameLeakTraceSameSignature() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }
    val hash1 = computeSignature(notLeaking = "Class1", leaking = "Class3")
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }
    val hash2 = computeSignature(notLeaking = "Class1", leaking = "Class3")
    assertThat(hash1).isEqualTo(hash2)
  }

  @Test fun differentLeakTraceDifferentSignature() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1a"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }
    val hash1 = computeSignature(notLeaking = "Class1", leaking = "Class3")
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1b"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }
    val hash2 = computeSignature(notLeaking = "Class1", leaking = "Class3")
    assertThat(hash1).isNotEqualTo(hash2)
  }

  @Test fun sameCausesSameSignature() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3a"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }
    val hash1 = computeSignature(notLeaking = "Class1", leaking = "Class3")

    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3b"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }
    val hash2 = computeSignature(notLeaking = "Class1", leaking = "Class3")
    assertThat(hash1).isEqualTo(hash2)
  }

  @Test fun sameCausesSameApplicationLeak() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Class2" instance {
            field["field2"] = "Class3" instance {
              field["field3a"] = "Leaking" watchedInstance {}
              field["field3b"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingInstance("Class1"), leakingInstance("Class3"))
      )

    assertThat(analysis.applicationLeaks).hasSize(1)
    assertThat(analysis.applicationLeaks.first().leakTraces).hasSize(2)
  }

  private fun notLeakingInstance(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapInstance && record.instanceClassName == className) {
          reporter.notLeakingReasons += "$className is not leaking"
        }
      }
    }
  }

  private fun leakingInstance(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapInstance && record.instanceClassName == className) {
          reporter.leakingReasons += "$className is leaking"
        }
      }
    }
  }

  private fun notLeakingClass(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapClass && record.name == className) {
          reporter.notLeakingReasons += "$className is not leaking"
        }
      }
    }
  }

  private fun leakingClass(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapClass && record.name == className) {
          reporter.leakingReasons += "$className is leaking"
        }
      }
    }
  }

  private fun computeSignature(
    notLeaking: String,
    leaking: String
  ): String {
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingInstance(notLeaking), leakingInstance(leaking))
      )
    require(analysis.applicationLeaks.size == 1) {
      "Expecting 1 retained instance in ${analysis.applicationLeaks}"
    }
    val leak = analysis.applicationLeaks[0]
    return leak.signature
  }
}