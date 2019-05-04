package leakcanary.internal

import leakcanary.HeapAnalysisSuccess
import leakcanary.HprofParser
import leakcanary.LeakNode
import leakcanary.LeakingInstance
import leakcanary.Reachability
import leakcanary.Reachability.Inspector
import leakcanary.Reachability.Status.REACHABLE
import leakcanary.Reachability.Status.UNKNOWN
import leakcanary.Reachability.Status.UNREACHABLE
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReachabilityTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun gcRootsReachable() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.expectedReachability.first().status).isEqualTo(REACHABLE)
  }

  @Test fun leakingInstanceUnreachable() {
    hprofFile.writeSinglePathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.expectedReachability.last().status).isEqualTo(UNREACHABLE)
  }

  @Test fun defaultsToUnknown() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.expectedReachability[1].status).isEqualTo(UNKNOWN)
  }

  @Test fun inspectorReachable() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        reachabilityInspectors = listOf(reachableInstance("Class1"))
    )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[1].status).isEqualTo(REACHABLE)
  }

  @Test fun inspectorUnreachable() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          reachabilityInspectors = listOf(unreachableInstance("Class1"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[1].status).isEqualTo(UNREACHABLE)
  }

  @Test fun unreachableWinsUnknown() {
    hprofFile.writeCustomPathToInstance(listOf("GcRoot" to "staticField1", "Class1" to "field1"))

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          reachabilityInspectors = listOf(unreachableInstance("Class1"), unknownInstance())
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[1].status).isEqualTo(UNREACHABLE)
  }

  @Test fun reachableWhenNextIsReachable() {
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
          reachabilityInspectors = listOf(reachableInstance("Class3"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[1].status).isEqualTo(REACHABLE)
  }

  @Test fun unreachableWhenPreviousIsUnreachable() {
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
          reachabilityInspectors = listOf(unreachableInstance("Class1"))
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[3].status).isEqualTo(UNREACHABLE)
  }

  @Test fun reachableWinsConflicts() {
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
          reachabilityInspectors = listOf(
              reachableInstance("Class3"), unreachableInstance("Class1")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[0].status).isEqualTo(REACHABLE)
    assertThat(leak.leakTrace.expectedReachability[1].status).isEqualTo(REACHABLE)
    assertThat(leak.leakTrace.expectedReachability[2].status).isEqualTo(REACHABLE)
    assertThat(leak.leakTrace.expectedReachability[3].status).isEqualTo(REACHABLE)
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
          reachabilityInspectors = listOf(
              reachableInstance("Class1"), unreachableInstance("Class3")
          )
      )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.expectedReachability[2].status).isEqualTo(UNKNOWN)
  }

  @Test fun leakCausesAreLastReachableAndUnknown() {
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
          reachabilityInspectors = listOf(
              reachableInstance("Class1"), unreachableInstance("Class3")
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
            ), reachable = "Class1", unreachable = "Class3"
        )
    ).isEqualTo(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1",
                "Class2" to "field2",
                "Class3" to "field3"
            ), reachable = "Class1", unreachable = "Class3"
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
            ), reachable = "Class1", unreachable = "Class3"
        )
    ).isNotEqualTo(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1b",
                "Class2" to "field2",
                "Class3" to "field3"
            ), reachable = "Class1", unreachable = "Class3"
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
            ), reachable = "Class1", unreachable = "Class3"
        )
    ).isEqualTo(
        computeGroupHash(
            path = listOf(
                "GcRoot" to "staticField1",
                "Class1" to "field1",
                "Class2" to "field2",
                "Class3" to "field3b"
            ), reachable = "Class1", unreachable = "Class3"
        )
    )
  }

  private fun unknownInstance(): Inspector {
    return object : Inspector {
      override fun expectedReachability(
        parser: HprofParser,
        node: LeakNode
      ): Reachability {
        return Reachability.unknown()
      }
    }
  }

  private fun reachableInstance(className: String): Inspector {
    return object : Inspector {
      override fun expectedReachability(
        parser: HprofParser,
        node: LeakNode
      ): Reachability = with(parser) {
        val record = node.instance.objectRecord
        if (record is InstanceDumpRecord) {
          if (className(record.classId) == className) {
            return Reachability.reachable("because reasons")
          }
        }
        return Reachability.unknown()
      }
    }
  }

  private fun unreachableInstance(className: String): Inspector {
    return object : Inspector {
      override fun expectedReachability(
        parser: HprofParser,
        node: LeakNode
      ): Reachability = with(parser) {
        val record = node.instance.objectRecord
        if (record is InstanceDumpRecord) {
          if (className(record.classId) == className) {
            return Reachability.unreachable("because reasons")
          }
        }
        return Reachability.unknown()
      }
    }
  }

  private fun computeGroupHash(
    path: List<Pair<String, String>>,
    reachable: String,
    unreachable: String
  ): String {
    hprofFile.writeCustomPathToInstance(path)

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
          reachabilityInspectors = listOf(
              reachableInstance(reachable), unreachableInstance(unreachable)
          )
      )
    val leak = analysis.retainedInstances[0] as LeakingInstance
    return leak.groupHash
  }

}