package shark

import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.EnumSet
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.JavaLocalPattern

enum class JdkReferenceMatchers {

  REFERENCES {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += ignoredInstanceField(WeakReference::class.java.name, "referent")
      references += ignoredInstanceField("leakcanary.KeyedWeakReference", "referent")
      references += ignoredInstanceField(SoftReference::class.java.name, "referent")
      references += ignoredInstanceField(PhantomReference::class.java.name, "referent")
      references += ignoredInstanceField("java.lang.ref.Finalizer", "prev")
      references += ignoredInstanceField("java.lang.ref.Finalizer", "element")
      references += ignoredInstanceField("java.lang.ref.Finalizer", "next")
      references += ignoredInstanceField("java.lang.ref.FinalizerReference", "prev")
      references += ignoredInstanceField("java.lang.ref.FinalizerReference", "element")
      references += ignoredInstanceField("java.lang.ref.FinalizerReference", "next")
      references += ignoredInstanceField("sun.misc.Cleaner", "prev")
      references += ignoredInstanceField("sun.misc.Cleaner", "next")
    }
  },

  FINALIZER_WATCHDOG_DAEMON {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      // If the FinalizerWatchdogDaemon thread is on the shortest path, then there was no other
      // reference to the object and it was about to be GCed.
      references += ignoredJavaLocal("FinalizerWatchdogDaemon")
    }
  },

  MAIN {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      // The main thread stack is ever changing so local variables aren't likely to hold references
      // for long. If this is on the shortest path, it's probably that there's a longer path with
      // a real leak.
      references += ignoredJavaLocal("main")
    }
  },
  ;

  internal abstract fun add(references: MutableList<ReferenceMatcher>)

  companion object {

    /**
     * @see [AndroidReferenceMatchers]
     */
    @JvmStatic
    val defaults: List<ReferenceMatcher>
      get() = buildKnownReferences(EnumSet.allOf(JdkReferenceMatchers::class.java))

    /**
     * Builds a list of [ReferenceMatcher] from the [referenceMatchers] set of
     * [AndroidReferenceMatchers].
     */
    @JvmStatic
    fun buildKnownReferences(referenceMatchers: Set<JdkReferenceMatchers>): List<ReferenceMatcher> {
      val resultSet = mutableListOf<ReferenceMatcher>()
      referenceMatchers.forEach {
        it.add(resultSet)
      }
      return resultSet
    }

    /**
     * Creates a [IgnoredReferenceMatcher] that matches a [InstanceFieldPattern].
     */
    @JvmStatic
    fun ignoredInstanceField(
      className: String,
      fieldName: String
    ): IgnoredReferenceMatcher {
      return IgnoredReferenceMatcher(pattern = InstanceFieldPattern(className, fieldName))
    }

    /**
     * Creates a [IgnoredReferenceMatcher] that matches a [JavaLocalPattern].
     */
    @JvmStatic
    fun ignoredJavaLocal(
      threadName: String
    ): IgnoredReferenceMatcher {
      return IgnoredReferenceMatcher(pattern = JavaLocalPattern(threadName))
    }
  }
}
