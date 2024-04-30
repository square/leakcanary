package shark

import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.EnumSet
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField
import shark.ReferencePattern.Companion.javaLocal
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.JavaLocalPattern

enum class JdkReferenceMatchers : ReferenceMatcher.ListBuilder {

  REFERENCES {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(WeakReference::class.java.name, "referent")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("leakcanary.KeyedWeakReference", "referent")
        .ignored(patternApplies = ALWAYS)
      references += instanceField(SoftReference::class.java.name, "referent")
        .ignored(patternApplies = ALWAYS)
      references += instanceField(PhantomReference::class.java.name, "referent")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("java.lang.ref.Finalizer", "prev")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("java.lang.ref.Finalizer", "element")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("java.lang.ref.Finalizer", "next")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("java.lang.ref.FinalizerReference", "prev")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("java.lang.ref.FinalizerReference", "element")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("java.lang.ref.FinalizerReference", "next")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("sun.misc.Cleaner", "prev")
        .ignored(patternApplies = ALWAYS)
      references += instanceField("sun.misc.Cleaner", "next")
        .ignored(patternApplies = ALWAYS)
    }
  },

  FINALIZER_WATCHDOG_DAEMON {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      // If the FinalizerWatchdogDaemon thread is on the shortest path, then there was no other
      // reference to the object and it was about to be GCed.
      references += javaLocal("FinalizerWatchdogDaemon")
        .ignored(patternApplies = ALWAYS)
    }
  },

  MAIN {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      // The main thread stack is ever changing so local variables aren't likely to hold references
      // for long. If this is on the shortest path, it's probably that there's a longer path with
      // a real leak.
      references += javaLocal("main")
        .ignored(patternApplies = ALWAYS)
    }
  },
  ;

  companion object {

    /**
     * @see [AndroidReferenceMatchers]
     */
    @JvmStatic
    val defaults: List<ReferenceMatcher>
      get() = ReferenceMatcher.fromListBuilders(EnumSet.allOf(JdkReferenceMatchers::class.java))

    /**
     * Builds a list of [ReferenceMatcher] from the [referenceMatchers] set of
     * [AndroidReferenceMatchers].
     */
    @JvmStatic
    @Deprecated(
      "Use ReferenceMatcher.fromListBuilders instead.",
      ReplaceWith("ReferenceMatcher.fromListBuilders")
    )
    fun buildKnownReferences(referenceMatchers: Set<JdkReferenceMatchers>): List<ReferenceMatcher> {
      return ReferenceMatcher.fromListBuilders(referenceMatchers)
    }

    /**
     * Creates a [IgnoredReferenceMatcher] that matches a [InstanceFieldPattern].
     */
    @Deprecated(
      "Use ReferencePattern.instanceField instead",
      ReplaceWith("ReferencePattern.instanceField")
    )
    @JvmStatic
    fun ignoredInstanceField(
      className: String,
      fieldName: String
    ): IgnoredReferenceMatcher {
      return instanceField(className, fieldName)
        .ignored(patternApplies = ALWAYS)
    }

    /**
     * Creates a [IgnoredReferenceMatcher] that matches a [JavaLocalPattern].
     */
    @Deprecated(
      "Use ReferencePattern.javaLocal instead",
      ReplaceWith("ReferencePattern.javaLocal")
    )
    @JvmStatic
    fun ignoredJavaLocal(
      threadName: String
    ): IgnoredReferenceMatcher {
      return javaLocal(threadName)
        .ignored(patternApplies = ALWAYS)
    }
  }
}
