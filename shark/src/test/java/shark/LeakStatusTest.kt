package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.LeakNodeStatus.LEAKING
import shark.LeakNodeStatus.NOT_LEAKING
import shark.LeakNodeStatus.UNKNOWN
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

    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
  }

  @Test fun leakingInstanceLeaking() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.status).isEqualTo(LEAKING)
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

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(UNKNOWN)
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements).hasSize(2)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[2].leakStatusAndReason.status).isEqualTo(UNKNOWN)
  }

  @Test fun gcRootClassNotLeakingConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingClass("GcRoot"), ObjectInspectors.CLASS)
      )

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.reason).isEqualTo(
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

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.reason).isEqualTo(
        "GcRoot is not leaking and a class is never leaking"
    )
  }

  @Test fun leakingInstanceLeakingConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(notLeakingInstance("Leaking"))
      )

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.reason).isEqualTo(
        "Leaking is not leaking. Conflicts with ObjectWatcher was watching this"
    )
  }

  @Test fun leakingInstanceLeakingAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          objectInspectors = listOf(leakingInstance("Leaking"))
      )

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.status).isEqualTo(LEAKING)
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.reason).isEqualTo(
        "Leaking is leaking and ObjectWatcher was watching this"
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
        "Class1 is not leaking and Class1 is not leaking"
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
        "Class1 is leaking and Class1 is leaking"
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

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elementMayBeLeakCause(0)).isFalse()
    assertThat(leak.leakTrace.elementMayBeLeakCause(1)).isTrue()
    assertThat(leak.leakTrace.elementMayBeLeakCause(2)).isTrue()
    assertThat(leak.leakTrace.elementMayBeLeakCause(3)).isFalse()
  }

  @Test fun sameLeakTraceSameGroup() {
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
    val hash1 = computeGroupHash(notLeaking = "Class1", leaking = "Class3")
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
    val hash2 = computeGroupHash(notLeaking = "Class1", leaking = "Class3")
    assertThat(hash1).isEqualTo(hash2)
  }

  @Test fun differentLeakTraceDifferentGroup() {
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
    val hash1 = computeGroupHash(notLeaking = "Class1", leaking = "Class3")
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
    val hash2 = computeGroupHash(notLeaking = "Class1", leaking = "Class3")
    assertThat(hash1).isNotEqualTo(hash2)
  }

  @Test fun sameCausesSameGroup() {
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
    val hash1 = computeGroupHash(notLeaking = "Class1", leaking = "Class3")

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
    val hash2 = computeGroupHash(notLeaking = "Class1", leaking = "Class3")
    assertThat(hash1).isEqualTo(hash2)
  }

  private fun notLeakingInstance(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        graph: HeapGraph,
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapInstance && record.instanceClassName == className) {
          reporter.reportNotLeaking("$className is not leaking")
        }
      }
    }
  }

  private fun leakingInstance(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        graph: HeapGraph,
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapInstance && record.instanceClassName == className) {
          reporter.reportLeaking("$className is leaking")
        }
      }
    }
  }

  private fun notLeakingClass(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        graph: HeapGraph,
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapClass && record.name == className) {
          reporter.reportNotLeaking("$className is not leaking")
        }
      }
    }
  }

  private fun leakingClass(className: String): ObjectInspector {
    return object : ObjectInspector {
      override fun inspect(
        graph: HeapGraph,
        reporter: ObjectReporter
      ) {
        val record = reporter.heapObject
        if (record is HeapClass && record.name == className) {
          reporter.reportLeaking("$className is leaking")
        }
      }
    }
  }

  private fun computeGroupHash(
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
    return leak.groupHash
  }
}