package shark.explorer

import shark.HprofWriterHelper
import shark.ValueHolder
import shark.ValueHolder.ReferenceHolder

/**
 * The `java.lang.ref` class hierarchy, written the way a real heap dump has it: `referent` is
 * declared on `java.lang.ref.Reference` and inherited, which is what the reference matchers have to
 * cope with, and `FinalizerReference` extends `PhantomReference` the way Android's does.
 */
class ReferenceClasses(
  val referenceId: Long,
  val softId: Long,
  val weakId: Long,
  val phantomId: Long,
  val finalizerId: Long
)

fun HprofWriterHelper.referenceClasses(): ReferenceClasses {
  val referenceId = clazz(
    className = "java.lang.ref.Reference",
    fields = listOf("referent" to ReferenceHolder::class)
  )
  val phantomId = clazz("java.lang.ref.PhantomReference", superclassId = referenceId)
  return ReferenceClasses(
    referenceId = referenceId,
    softId = clazz("java.lang.ref.SoftReference", superclassId = referenceId),
    weakId = clazz("java.lang.ref.WeakReference", superclassId = referenceId),
    phantomId = phantomId,
    finalizerId = clazz(
      className = "java.lang.ref.FinalizerReference",
      superclassId = phantomId,
      fields = listOf("zombie" to ReferenceHolder::class)
    )
  )
}

/**
 * A `java.lang.ref.Reference` of class [referenceClassId] pointing at [referent].
 *
 * Only for the classes that declare no field of their own — see [finalizerReference] for the one that
 * does. Field values are written in class hierarchy order, most derived class first, and a record
 * that's short of what its class declares fails to read at all.
 */
fun HprofWriterHelper.reference(
  referenceClassId: Long,
  referent: ReferenceHolder
): ReferenceHolder = instance(referenceClassId, fields = listOf(referent))

/**
 * An Android `FinalizerReference`, which holds the object it's about in [referent] until `finalize()`
 * starts and in [zombie] while it runs.
 */
fun HprofWriterHelper.finalizerReference(
  classes: ReferenceClasses,
  referent: ReferenceHolder = ReferenceHolder(ValueHolder.NULL_REFERENCE),
  zombie: ReferenceHolder = ReferenceHolder(ValueHolder.NULL_REFERENCE)
): ReferenceHolder = instance(classes.finalizerId, fields = listOf(zombie, referent))
