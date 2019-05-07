package leakcanary.internal

import leakcanary.HeapAnalysisSuccess
import leakcanary.LeakInspector
import leakcanary.LeakNodeStatus
import leakcanary.LeakNodeStatus.LEAKING
import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.LeakingInstance
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LeakStatusTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun gcRootsNotLeaking() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
  }

  @Test fun leakingInstanceLeaking() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.status).isEqualTo(LEAKING)
  }

  @Test fun defaultsToUnknown() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(UNKNOWN)
  }

  @Test fun inspectorNotLeaking() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        leakInspectors = listOf(notLeaking("Class1"))
    )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
  }

  @Test fun inspectorLeaking() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(leaking("Class1"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
  }

  @Test fun leakingWinsUnknown() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(leaking("Class1"), unknownInstance())
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
  }

  @Test fun notLeakingWhenNextIsNotLeaking() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1",
            "Class2" to "field2",
            "Class3" to "field3"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(notLeaking("Class3"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
  }

  @Test fun leakingWhenPreviousIsLeaking() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1",
            "Class2" to "field2",
            "Class3" to "field3"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(leaking("Class1"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[3].leakStatusAndReason.status).isEqualTo(LEAKING)
  }

  @Test fun notLeakingWinsConflicts() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1",
            "Class2" to "field2",
            "Class3" to "field3"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(
              notLeaking("Class3"), leaking("Class1")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[0].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[0].leakStatusAndReason.reason).isEqualTo("it's a GC root")
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
        "Class2↓ is not leaking. Conflicts with Class1 is leaking"
    )
    assertThat(leak.leakTrace.elements[2].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[2].leakStatusAndReason.reason).isEqualTo(
        "Class3↓ is not leaking"
    )
    assertThat(leak.leakTrace.elements[3].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[3].leakStatusAndReason.reason).isEqualTo(
        "Class3 is not leaking"
    )
  }

  @Test fun middleUnknown() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1",
            "Class2" to "field2",
            "Class3" to "field3"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(
              notLeaking("Class1"), leaking("Class3")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[2].leakStatusAndReason.status).isEqualTo(UNKNOWN)
  }

  @Test fun gcRootsNotLeakingConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(leaking("GcRoot"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.reason).isEqualTo(
        "it's a GC root. Conflicts with GcRoot is leaking"
    )
  }

  @Test fun gcRootsNotLeakingAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(notLeaking("GcRoot"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements.first().leakStatusAndReason.reason).isEqualTo(
        "it's a GC root and GcRoot is not leaking"
    )
  }

  @Test fun leakingInstanceLeakingConflictingWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(notLeaking("Leaking"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.status).isEqualTo(LEAKING)
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.reason).isEqualTo(
        "RefWatcher was watching this. Conflicts with Leaking is not leaking"
    )
  }

  @Test fun leakingInstanceLeakingAgreesWithInspector() {
    hprofFile.writeSinglePathToInstance()
    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(leaking("Leaking"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.status).isEqualTo(LEAKING)
    assertThat(leak.leakTrace.elements.last().leakStatusAndReason.reason).isEqualTo(
        "RefWatcher was watching this and Leaking is leaking"
    )
  }

  @Test fun conflictNotLeakingWins() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(
              notLeaking("Class1"), leaking("Class1")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
        "Class1 is not leaking. Conflicts with Class1 is leaking"
    )
  }

  @Test fun twoInspectorsAgreeNotLeaking() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(
              notLeaking("Class1"), notLeaking("Class1")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(NOT_LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
        "Class1 is not leaking and Class1 is not leaking"
    )
  }

  @Test fun twoInspectorsAgreeLeaking() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(leaking("Class1"), leaking("Class1"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.status).isEqualTo(LEAKING)
    assertThat(leak.leakTrace.elements[1].leakStatusAndReason.reason).isEqualTo(
        "Class1 is leaking and Class1 is leaking"
    )
  }

  @Test fun leakCausesAreLastNotLeakingAndUnknown() {
    hprofFile.writeCustomPathToInstance(
        listOf(
            "GcRoot" to "staticField1",
            "Class1" to "field1",
            "Class2" to "field2",
            "Class3" to "field3"
        )
    )

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(
              notLeaking("Class1"), leaking("Class3")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elementMayBeLeakCause(0)).isFalse()
    assertThat(leak.leakTrace.elementMayBeLeakCause(1)).isTrue()
    assertThat(leak.leakTrace.elementMayBeLeakCause(2)).isTrue()
    assertThat(leak.leakTrace.elementMayBeLeakCause(3)).isFalse()
  }

  @Test fun sameLeakTraceSameGroup() {
    assertThat(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1",
                "Class2" to "field2",
                "Class3" to "field3"
            ), notLeaking = "Class1", leaking = "Class3"
        )
    ).isEqualTo(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1",
                "Class2" to "field2",
                "Class3" to "field3"
            ), notLeaking = "Class1", leaking = "Class3"
        )
    )
  }

  @Test fun differentLeakTraceDifferentGroup() {
    assertThat(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1a",
                "Class2" to "field2",
                "Class3" to "field3"
            ), notLeaking = "Class1", leaking = "Class3"
        )
    ).isNotEqualTo(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1b",
                "Class2" to "field2",
                "Class3" to "field3"
            ), notLeaking = "Class1", leaking = "Class3"
        )
    )
  }

  @Test fun sameCausesSameGroup() {
    assertThat(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1",
                "Class2" to "field2",
                "Class3" to "field3a"
            ), notLeaking = "Class1", leaking = "Class3"
        )
    ).isEqualTo(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1",
                "Class2" to "field2",
                "Class3" to "field3b"
            ), notLeaking = "Class1", leaking = "Class3"
        )
    )
  }

  private fun unknownInstance(): LeakInspector {
    return { _, _ -> LeakNodeStatus.unknown() }
  }

  private fun notLeaking(className: String): LeakInspector {
    return { parser, node ->
      with(parser) result@{
        val record = node.instance.objectRecord
        if (record is InstanceDumpRecord) {
          if (className(record.classId) == className) {
            return@result LeakNodeStatus.notLeaking("$className is not leaking")
          }
        } else if (record is ClassDumpRecord) {
          if (className(record.id) == className) {
            return@result LeakNodeStatus.notLeaking("$className is not leaking")
          }
        }
        return@result LeakNodeStatus.unknown()
      }
    }
  }

  private fun leaking(className: String): LeakInspector {
    return { parser, node ->
      with(parser) result@{
        val record = node.instance.objectRecord
        if (record is InstanceDumpRecord) {
          if (className(record.classId) == className) {
            return@result LeakNodeStatus.leaking("$className is leaking")
          }
        } else if (record is ClassDumpRecord) {
          if (className(record.id) == className) {
            return@result LeakNodeStatus.leaking("$className is leaking")
          }
        }
        return@result LeakNodeStatus.unknown()
      }
    }
  }

  private fun computeGroupHash(
    path: List<Pair<String, String>>,
    notLeaking: String,
    leaking: String
  ): String {
    hprofFile.writeCustomPathToInstance(path)

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          leakInspectors = listOf(notLeaking(notLeaking), leaking(leaking))
      )
    val leak = analysis.retainedInstances[0] as LeakingInstance
    return leak.groupHash
  }
}