package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.ValueHolder.ReferenceHolder

/**
 * Tests for [LeakInvestigationSession]: verifies the split analysis pipeline (one-time BFS +
 * cheap re-inspection) and the override-wins-always invariant.
 */
class LeakInvestigationSessionTest {

  // Session with no custom inspectors beyond KEYED_WEAK_REFERENCE.
  private val session = LeakInvestigationSession(
    referenceMatchers = JdkReferenceMatchers.defaults,
    objectInspectors = listOf(ObjectInspectors.KEYED_WEAK_REFERENCE)
  )

  // ---------------------------------------------------------------------------
  // Helper: find leaking object IDs the same way AiInvestigateCommand does,
  // but using the JDK filter set (which works with the watchedInstance DSL).
  // ---------------------------------------------------------------------------

  private fun leakingObjectIds(graph: HeapGraph): Set<Long> =
    FilteringLeakingObjectFinder(ObjectInspectors.jdkLeakingObjectFilters)
      .findLeakingObjectIds(graph)

  // ---------------------------------------------------------------------------
  // Helper: session with an extra inspector appended before KEYED_WEAK_REFERENCE.
  // ---------------------------------------------------------------------------

  private fun sessionWithInspector(vararg extra: ObjectInspector) = LeakInvestigationSession(
    referenceMatchers = JdkReferenceMatchers.defaults,
    objectInspectors = extra.toList() + listOf(ObjectInspectors.KEYED_WEAK_REFERENCE)
  )

  private fun notLeakingInstance(className: String): ObjectInspector = ObjectInspector { reporter ->
    val obj = reporter.heapObject
    if (obj is HeapInstance && obj.instanceClassName == className) {
      reporter.notLeakingReasons += "$className is not leaking"
    }
  }

  private fun leakingInstance(className: String): ObjectInspector = ObjectInspector { reporter ->
    val obj = reporter.heapObject
    if (obj is HeapInstance && obj.instanceClassName == className) {
      reporter.leakingReasons += "$className is leaking"
    }
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test fun analyzeFindsLeakGroup() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))

      assertThat(analysis.allLeaks).hasSize(1)
      assertThat(analysis.groupedPaths).hasSize(1)
      assertThat(analysis.groupedPaths[0]).hasSize(1)
    }
  }

  @Test fun analyzeGroupsMultipleInstancesOfSameLeak() {
    // Build: GcRoot.holders → Holder[] → [x] → Holder.child → Watched (leaking)
    // Array entries all produce referenceGenericName "[x]" → both paths share same signature.
    val heapDump = dump {
      val watchedClassId = clazz(className = "Watched")
      val holderClassId = clazz(
        className = "Holder",
        fields = listOf("child" to ReferenceHolder::class)
      )
      val holderArrayClassId = arrayClass("Holder")

      val watched1 = instance(watchedClassId)
      keyedWeakReference(watched1)
      val watched2 = instance(watchedClassId)
      keyedWeakReference(watched2)

      val holder1 = instance(holderClassId, listOf(watched1))
      val holder2 = instance(holderClassId, listOf(watched2))

      clazz(
        className = "GcRoot",
        staticFields = listOf("holders" to objectArrayOf(holderArrayClassId, holder1, holder2))
      )
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))

      // Both paths: GcRoot.holders[x].child → Watched share the same signature → one group
      assertThat(analysis.allLeaks).hasSize(1)
      assertThat(analysis.groupedPaths[0]).hasSize(2)
    }
  }

  @Test fun reinspectDefaultsToUnknownForMiddleNode() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f"] = "Class1" instance {
          field["g"] = "Leaking" watchedInstance {}
        }
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]

      val trace = session.reinspectPath(graph, cachedPath, emptyMap())

      // Node at referencePath[1] is Class1 — no inspector opinion → UNKNOWN
      assertThat(trace.referencePath[1].originObject.leakingStatus).isEqualTo(UNKNOWN)
    }
  }

  @Test fun markLeakingOverrideChangesNodeStatus() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f"] = "Class1" instance {
          field["g"] = "Leaking" watchedInstance {}
        }
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]

      // objectIds[1] = Class1 instance (index 0 = GcRoot class)
      val class1Id = cachedPath.objectIds[1]
      val trace = session.reinspectPath(
        graph, cachedPath,
        mapOf(class1Id to (LEAKING to "agent override"))
      )

      assertThat(trace.referencePath[1].originObject.leakingStatus).isEqualTo(LEAKING)
      assertThat(trace.referencePath[1].originObject.leakingStatusReason)
        .isEqualTo("agent override")
    }
  }

  @Test fun markNotLeakingOverrideChangesNodeStatus() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f"] = "Class1" instance {
          field["g"] = "Leaking" watchedInstance {}
        }
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]

      val class1Id = cachedPath.objectIds[1]
      val trace = session.reinspectPath(
        graph, cachedPath,
        mapOf(class1Id to (NOT_LEAKING to "agent override"))
      )

      assertThat(trace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
      assertThat(trace.referencePath[1].originObject.leakingStatusReason)
        .isEqualTo("agent override")
    }
  }

  @Test fun overrideWinsOverConflictingInspector() {
    // Standard inspector votes NOT_LEAKING for Class1; override votes LEAKING → LEAKING wins.
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f"] = "Class1" instance {
          field["g"] = "Leaking" watchedInstance {}
        }
      }
    }

    val s = sessionWithInspector(notLeakingInstance("Class1"))

    heapDump.openHeapGraph().use { graph ->
      val analysis = s.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]

      // Verify inspector verdict before override
      val traceBefore = s.reinspectPath(graph, cachedPath, emptyMap())
      assertThat(traceBefore.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)

      // Apply LEAKING override — must beat the inspector's NOT_LEAKING
      val class1Id = cachedPath.objectIds[1]
      val traceAfter = s.reinspectPath(
        graph, cachedPath,
        mapOf(class1Id to (LEAKING to "agent override"))
      )
      assertThat(traceAfter.referencePath[1].originObject.leakingStatus).isEqualTo(LEAKING)
      assertThat(traceAfter.referencePath[1].originObject.leakingStatusReason)
        .isEqualTo("agent override")
    }
  }

  @Test fun notLeakingOverridePropagatesUpwardToEarlierNodes() {
    // Path: GcRoot → Class1 → Class2 → Class3 → Leaking
    // Marking Class3 NOT_LEAKING must force Class1 and Class2 to NOT_LEAKING too.
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f1"] = "Class1" instance {
          field["f2"] = "Class2" instance {
            field["f3"] = "Class3" instance {
              field["f4"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]

      // objectIds: [0]=GcRoot, [1]=Class1, [2]=Class2, [3]=Class3, [4]=Leaking
      val class3Id = cachedPath.objectIds[3]
      val trace = session.reinspectPath(
        graph, cachedPath,
        mapOf(class3Id to (NOT_LEAKING to "agent override"))
      )

      // Class3 itself is NOT_LEAKING
      assertThat(trace.referencePath[3].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
      // Class1 and Class2 are forced NOT_LEAKING because a node below them is NOT_LEAKING
      assertThat(trace.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
      assertThat(trace.referencePath[2].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    }
  }

  @Test fun leakingOverridePropagatesDownwardToLaterNodes() {
    // Path: GcRoot → Class1 → Class2 → Class3 → Leaking
    // Marking Class1 LEAKING must force Class2 and Class3 to LEAKING.
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f1"] = "Class1" instance {
          field["f2"] = "Class2" instance {
            field["f3"] = "Class3" instance {
              field["f4"] = "Leaking" watchedInstance {}
            }
          }
        }
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]

      val class1Id = cachedPath.objectIds[1]
      val trace = session.reinspectPath(
        graph, cachedPath,
        mapOf(class1Id to (LEAKING to "agent override"))
      )

      // Class1 is LEAKING
      assertThat(trace.referencePath[1].originObject.leakingStatus).isEqualTo(LEAKING)
      // Class2 and Class3 are forced LEAKING because a node above them is LEAKING
      assertThat(trace.referencePath[2].originObject.leakingStatus).isEqualTo(LEAKING)
      assertThat(trace.referencePath[3].originObject.leakingStatus).isEqualTo(LEAKING)
    }
  }

  @Test fun clearOverrideResetsNodeToInspectorDefault() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f"] = "Class1" instance {
          field["g"] = "Leaking" watchedInstance {}
        }
      }
    }

    val s = sessionWithInspector(notLeakingInstance("Class1"))

    heapDump.openHeapGraph().use { graph ->
      val analysis = s.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]
      val class1Id = cachedPath.objectIds[1]

      // Apply LEAKING override
      val traceWithOverride = s.reinspectPath(
        graph, cachedPath,
        mapOf(class1Id to (LEAKING to "agent override"))
      )
      assertThat(traceWithOverride.referencePath[1].originObject.leakingStatus).isEqualTo(LEAKING)

      // Remove override → inspector default (NOT_LEAKING) should be restored
      val traceCleared = s.reinspectPath(graph, cachedPath, emptyMap())
      assertThat(traceCleared.referencePath[1].originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    }
  }

  @Test fun overrideAppliedConsistentlyAcrossTraceInstances() {
    // Same array-based heap structure as analyzeGroupsMultipleInstancesOfSameLeak.
    // Path: GcRoot.holders[x] → Holder.child → Watched (leaking)
    // Overriding one Holder by object ID should not affect the other trace's Holder.
    val heapDump = dump {
      val watchedClassId = clazz(className = "Watched")
      val holderClassId = clazz(
        className = "Holder",
        fields = listOf("child" to ReferenceHolder::class)
      )
      val holderArrayClassId = arrayClass("Holder")

      val watched1 = instance(watchedClassId)
      keyedWeakReference(watched1)
      val watched2 = instance(watchedClassId)
      keyedWeakReference(watched2)

      val holder1 = instance(holderClassId, listOf(watched1))
      val holder2 = instance(holderClassId, listOf(watched2))

      clazz(
        className = "GcRoot",
        staticFields = listOf("holders" to objectArrayOf(holderArrayClassId, holder1, holder2))
      )
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))

      // Two trace instances in the single group
      assertThat(analysis.groupedPaths[0]).hasSize(2)

      val cachedPath0 = analysis.groupedPaths[0][0]
      val cachedPath1 = analysis.groupedPaths[0][1]

      // objectIds: [0]=GcRoot, [1]=Holder[], [2]=Holder instance, [3]=Watched
      val holder0Id = cachedPath0.objectIds[2]
      val override = mapOf(holder0Id to (NOT_LEAKING to "agent override"))

      // Trace 0: Holder is overridden → NOT_LEAKING
      val trace0 = session.reinspectPath(graph, cachedPath0, override)
      assertThat(trace0.referencePath[2].originObject.leakingStatus).isEqualTo(NOT_LEAKING)

      // Trace 1 has a different Holder instance (different object ID) → override does not apply
      val trace1 = session.reinspectPath(graph, cachedPath1, override)
      assertThat(trace1.referencePath[2].originObject.leakingStatus).isEqualTo(UNKNOWN)
    }
  }

  @Test fun objectIdsListCoversAllTraceNodes() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["f1"] = "Class1" instance {
          field["f2"] = "Class2" instance {
            field["f3"] = "Leaking" watchedInstance {}
          }
        }
      }
    }

    heapDump.openHeapGraph().use { graph ->
      val analysis = session.analyze(graph, leakingObjectIds(graph))
      val cachedPath = analysis.groupedPaths[0][0]
      val trace = session.reinspectPath(graph, cachedPath, emptyMap())

      // objectIds: [GcRoot, Class1, Class2, Leaking] = referencePath.size + 1
      val expectedSize = trace.referencePath.size + 1
      assertThat(cachedPath.objectIds).hasSize(expectedSize)

      // Last objectId matches the leaking object (accessible via graph)
      val leakingObjectId = cachedPath.objectIds.last()
      val leakingObj = graph.findObjectByIdOrNull(leakingObjectId)
      assertThat(leakingObj).isNotNull()
    }
  }
}
