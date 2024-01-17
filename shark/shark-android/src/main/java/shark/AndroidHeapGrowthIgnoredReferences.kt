package shark

import java.util.EnumSet
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.StaticFieldPattern

// TODO Maybe change back to using ref matchers?
// Deprioritizing can still be useful.
enum class AndroidHeapGrowthIgnoredReferences {

  ANDROID_DEFAULTS {
    override fun add(patterns: MutableList<ReferencePattern>) {
      patterns += AndroidReferenceMatchers.appDefaults.filterIsInstance<IgnoredReferenceMatcher>()
        .map { it.pattern }
    }
  },

  HEAP_TRAVERSAL {
    override fun add(patterns: MutableList<ReferencePattern>) {
      patterns += HeapTraversal.ignoredReferences.map { it.pattern }
    }
  },

  STRICT_MODE_VIOLATION_TIME {
    override fun add(patterns: MutableList<ReferencePattern>) {
      // https://cs.android.com/android/_/android/platform/frameworks/base/+/6985fb39f07294fb979b14ba0ebabfd2fea06d34
      patterns += StaticFieldPattern("android.os.StrictMode", "sLastVmViolationTime")
    }
  },

  COMPOSE_TEST_CONTEXT_STATES {
    override fun add(patterns: MutableList<ReferencePattern>) {
      // TestContext.states has unbounded growth
      // https://issuetracker.google.com/issues/319693080
      patterns += InstanceFieldPattern("androidx.compose.ui.test.TestContext", "states")
    }
  },

  RESOURCES_THEME_REFS {
    override fun add(patterns: MutableList<ReferencePattern>) {
      // Every time a new theme is generated, a weak reference is added to that list. That list is cleared at growing thresholds, and
      // only the cleared weak refs are removed.
      // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/res/Resources.java;l=182;drc=c4f2605b0cbde5813bf4030d9209c62cd0839f81
      patterns += InstanceFieldPattern("android.content.res.Resources", "mThemeRefs")
    }
  },

  VIEW_ROOT_IMPL_W_VIEW_ANCESTOR {
    override fun add(patterns: MutableList<ReferencePattern>) {
      // Stub allowing the system server process to talk back to a view root impl. Cleared when the GC runs
      // in the system server process and then the receiving app.
      // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/ViewRootImpl.java;l=10538;drc=803ac6538fd26e39942e51930a0a3c7d8a0e8e06
      patterns += InstanceFieldPattern("android.view.ViewRootImpl\$W", "mViewAncestor")
    }
  },

  ;

  internal abstract fun add(patterns: MutableList<ReferencePattern>)

  companion object {

    val defaults: List<IgnoredReferenceMatcher>
      get() = buildKnownReferences(EnumSet.allOf(AndroidHeapGrowthIgnoredReferences::class.java))

    fun buildKnownReferences(referenceMatchers: Set<AndroidHeapGrowthIgnoredReferences>): List<IgnoredReferenceMatcher> {
      val resultSet = mutableListOf<ReferencePattern>()
      referenceMatchers.forEach {
        it.add(resultSet)
      }
      return resultSet.map { IgnoredReferenceMatcher(it) }
    }
  }
}
