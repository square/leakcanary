package shark

import java.util.EnumSet
import shark.ReferencePattern.Companion.instanceField
import shark.ReferencePattern.Companion.staticField

enum class AndroidHeapGrowthReferenceMatchers : ReferenceMatcher.ListBuilder {

  ANDROID_LEAK_DETECTION_IGNORED_MATCHERS {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += AndroidReferenceMatchers.appDefaults.filterIsInstance<IgnoredReferenceMatcher>()
    }
  },

  HEAP_TRAVERSAL {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += HeapTraversalOutput.ignoredReferences
    }
  },

  STRICT_MODE_VIOLATION_TIME {
    override fun add(references: MutableList<ReferenceMatcher>) {
      // https://cs.android.com/android/_/android/platform/frameworks/base/+/6985fb39f07294fb979b14ba0ebabfd2fea06d34
      references += staticField("android.os.StrictMode", "sLastVmViolationTime").ignored()
    }
  },

  COMPOSE_TEST_CONTEXT_STATES {
    override fun add(references: MutableList<ReferenceMatcher>) {
      // TestContext.states has unbounded growth
      // https://issuetracker.google.com/issues/319693080
      references += instanceField("androidx.compose.ui.test.TestContext", "states").ignored()
    }
  },

  RESOURCES_THEME_REFS {
    override fun add(references: MutableList<ReferenceMatcher>) {
      // Every time a new theme is generated, a weak reference is added to that list. That list is cleared at growing thresholds, and
      // only the cleared weak refs are removed.
      // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/res/Resources.java;l=182;drc=c4f2605b0cbde5813bf4030d9209c62cd0839f81
      references += instanceField("android.content.res.Resources", "mThemeRefs").ignored()
    }
  },

  VIEW_ROOT_IMPL_W_VIEW_ANCESTOR {
    override fun add(references: MutableList<ReferenceMatcher>) {
      // Stub allowing the system server process to talk back to a view root impl. Cleared when the GC runs
      // in the system server process and then the receiving app.
      // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/ViewRootImpl.java;l=10538;drc=803ac6538fd26e39942e51930a0a3c7d8a0e8e06
      references += instanceField("android.view.ViewRootImpl\$W", "mViewAncestor").ignored()
    }
  },

  ;

  companion object {
    val defaults: List<ReferenceMatcher>
      get() = ReferenceMatcher.fromListBuilders(
        EnumSet.allOf(AndroidHeapGrowthReferenceMatchers::class.java)
      )
  }
}
