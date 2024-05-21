package shark

import java.util.EnumSet
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField

enum class JvmObjectGrowthReferenceMatchers : ReferenceMatcher.ListBuilder {

  JVM_LEAK_DETECTION_IGNORED_MATCHERS {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += JdkReferenceMatchers.defaults.filterIsInstance<IgnoredReferenceMatcher>()
    }
  },

  HEAP_TRAVERSAL {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += HeapTraversalOutput.ignoredReferences
    }
  },

  PARALLEL_LOCK_MAP {
    override fun add(references: MutableList<ReferenceMatcher>) {
      // Seems to be sometimes growing at a fast pace. JVM only ("Android-removed: Remove unused ParallelLoaders")
      references += instanceField("java.lang.ClassLoader", "parallelLockMap")
        .ignored(patternApplies = ALWAYS)
    }
  },

  ;

  companion object {
    val defaults: List<ReferenceMatcher>
      get() = ReferenceMatcher.fromListBuilders(
        EnumSet.allOf(JvmObjectGrowthReferenceMatchers::class.java)
      )
  }
}
