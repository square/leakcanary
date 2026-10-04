package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.LeakTraceObject.Verdict.STUCK
import shark.LeakTraceObject.Verdict.EXPECTED
import shark.LeakTraceObject.Verdict.UNKNOWN
import java.io.File

class VerdictTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun gcRootClassExpected() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      objectInspectors = listOf(ObjectInspectors.CLASS)
    )

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTraces.first().referencePath.first().originObject.verdict).isEqualTo(
      EXPECTED
    )
  }

  @Test fun watchedInstanceIsStuck() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.verdict).isEqualTo(STUCK)
  }

  @Test fun unreachableInstanceIsStuck() {
    val heapDump = dump {
      "SomeClass" watchedInstance {
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(analysis.unreachableObjects[0].verdict).isEqualTo(STUCK)
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
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(UNKNOWN)
  }

  @Test fun inspectorSaysExpected() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      objectInspectors = listOf(expectedInstance("Class1"))
    )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(EXPECTED)
  }

  @Test fun inspectorSaysStuck() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(stuckInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.verdictReason).isEqualTo(
      "Class1 is stuck"
    )
  }

  @Test fun stuckWinsOverUnknown() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(stuckInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(STUCK)
  }

  @Test fun expectedWhenNextIsExpected() {
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
        objectInspectors = listOf(expectedInstance("Class3"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(EXPECTED)
  }

  @Test fun stuckWhenPreviousIsStuck() {
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
        objectInspectors = listOf(stuckInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(4)
    assertThat(leakTrace.referencePath[2].originObject.verdict).isEqualTo(STUCK)
    assertThat(leakTrace.referencePath[2].originObject.verdictReason).isEqualTo(
      "Class1↑ is stuck"
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
          expectedInstance("Class1"), stuckInstance("Class3")
        )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[2].originObject.verdict).isEqualTo(UNKNOWN)
  }

  @Test fun gcRootClassExpectedConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(stuckClass("GcRoot"), ObjectInspectors.CLASS)
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    assertThat(leakTrace.referencePath.first().originObject.verdict).isEqualTo(EXPECTED)
    assertThat(leakTrace.referencePath.first().originObject.verdictReason).isEqualTo(
      "a class is always expected. Conflicts with GcRoot is stuck"
    )
  }

  @Test fun gcRootClassExpectedAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(expectedClass("GcRoot"), ObjectInspectors.CLASS)
      )

    println(analysis)

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    assertThat(leakTrace.referencePath.first().originObject.verdict).isEqualTo(EXPECTED)
    assertThat(leakTrace.referencePath.first().originObject.verdictReason).isEqualTo(
      "GcRoot is expected and a class is always expected"
    )
  }

  @Test fun watchedInstanceStuckConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(expectedInstance("Leaking"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.verdict).isEqualTo(STUCK)
    assertThat(leakTrace.leakingObject.verdictReason).isEqualTo(
      "ObjectWatcher was watching this because its lifecycle has ended. " +
        "Conflicts with Leaking is expected"
    )
  }

  @Test fun watchedInstanceStuckAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(stuckInstance("Leaking"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.verdict).isEqualTo(STUCK)
    assertThat(leakTrace.leakingObject.verdictReason).isEqualTo(
      "Leaking is stuck and ObjectWatcher was watching this because its lifecycle has ended"
    )
  }

  @Test fun conflictExpectedWins() {
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
          expectedInstance("Class1"), stuckInstance("Class1")
        )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    println(leakTrace)

    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(EXPECTED)
    assertThat(leakTrace.referencePath[1].originObject.verdictReason).isEqualTo(
      "Class1 is expected. Conflicts with Class1 is stuck"
    )
  }

  @Test fun twoInspectorsAgreeExpected() {
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
          expectedInstance("Class1"), expectedInstance("Class1")
        )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(EXPECTED)
    assertThat(leakTrace.referencePath[1].originObject.verdictReason).isEqualTo(
      "Class1 is expected"
    )
  }

  @Test fun twoInspectorsAgreeStuck() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["staticField1"] = "Class1" instance {
          field["field1"] = "Leaking" watchedInstance {}
        }
      }
    }

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(stuckInstance("Class1"), stuckInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(STUCK)
    assertThat(leakTrace.referencePath[1].originObject.verdictReason).isEqualTo(
      "Class1 is stuck"
    )
  }

  @Test fun expectedWhenFurtherDownIsExpected() {
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
        objectInspectors = listOf(expectedInstance("Class3"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[1].originObject.className).isEqualTo("Class1")
    assertThat(leakTrace.referencePath[1].originObject.verdict).isEqualTo(EXPECTED)
    assertThat(leakTrace.referencePath[1].originObject.verdictReason).isEqualTo(
      "Class3↓ is expected"
    )
  }

  @Test fun stuckWhenFurtherUpIsStuck() {
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
        objectInspectors = listOf(stuckInstance("Class1"))
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath[3].originObject.className).isEqualTo("Class3")
    assertThat(leakTrace.referencePath[3].originObject.verdict).isEqualTo(STUCK)
    assertThat(leakTrace.referencePath[3].originObject.verdictReason).isEqualTo(
      "Class1↑ is stuck"
    )
  }

  @Test fun leakCausesAreLastExpectedAndUnknown() {
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
          expectedInstance("Class1"), stuckInstance("Class3")
        )
      )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePathElementIsSuspect(0)).isFalse()
    assertThat(leakTrace.referencePathElementIsSuspect(1)).isTrue()
    assertThat(leakTrace.referencePathElementIsSuspect(2)).isTrue()
  }

  @Test fun sameLeakTraceSameLeakFingerprint() {
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
    val hash1 = computeLeakFingerprint(expected = "Class1", stuck = "Class3")
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
    val hash2 = computeLeakFingerprint(expected = "Class1", stuck = "Class3")
    assertThat(hash1).isEqualTo(hash2)
  }

  @Test fun differentLeakTraceDifferentLeakFingerprint() {
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
    val hash1 = computeLeakFingerprint(expected = "Class1", stuck = "Class3")
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
    val hash2 = computeLeakFingerprint(expected = "Class1", stuck = "Class3")
    assertThat(hash1).isNotEqualTo(hash2)
  }

  @Test fun sameCausesSameLeakFingerprint() {
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
    val hash1 = computeLeakFingerprint(expected = "Class1", stuck = "Class3")

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
    val hash2 = computeLeakFingerprint(expected = "Class1", stuck = "Class3")
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
        objectInspectors = listOf(expectedInstance("Class1"), stuckInstance("Class3"))
      )

    assertThat(analysis.applicationLeaks).hasSize(1)
    assertThat(analysis.applicationLeaks.first().leakTraces).hasSize(2)
  }

  private fun expectedInstance(className: String): ObjectInspector {
    return ObjectInspector { reporter ->
      val record = reporter.heapObject
      if (record is HeapInstance && record.instanceClassName == className) {
        reporter.expectedReasons += "$className is expected"
      }
    }
  }

  private fun stuckInstance(className: String): ObjectInspector {
    return ObjectInspector { reporter ->
      val record = reporter.heapObject
      if (record is HeapInstance && record.instanceClassName == className) {
        reporter.stuckReasons += "$className is stuck"
      }
    }
  }

  private fun expectedClass(className: String): ObjectInspector {
    return ObjectInspector { reporter ->
      val record = reporter.heapObject
      if (record is HeapClass && record.name == className) {
        reporter.expectedReasons += "$className is expected"
      }
    }
  }

  private fun stuckClass(className: String): ObjectInspector {
    return ObjectInspector { reporter ->
      val record = reporter.heapObject
      if (record is HeapClass && record.name == className) {
        reporter.stuckReasons += "$className is stuck"
      }
    }
  }

  private fun computeLeakFingerprint(
    expected: String,
    stuck: String
  ): String {
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(expectedInstance(expected), stuckInstance(stuck))
      )
    require(analysis.applicationLeaks.size == 1) {
      "Expecting 1 retained instance in ${analysis.applicationLeaks}"
    }
    val leak = analysis.applicationLeaks[0]
    return leak.leakFingerprint
  }
}
