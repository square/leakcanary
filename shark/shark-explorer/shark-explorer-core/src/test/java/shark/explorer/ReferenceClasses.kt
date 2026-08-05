package shark.explorer

import shark.HprofWriterHelper
import shark.ValueHolder
import shark.ValueHolder.ReferenceHolder

/** No reference: the value a field of a heap dump holds when it points at nothing. */
private val NULL = ReferenceHolder(ValueHolder.NULL_REFERENCE)

/**
 * The `java.lang.ref` class hierarchy, written the way a real heap dump has it: `referent` is
 * declared on `java.lang.ref.Reference` and inherited, which is what the reference matchers have to
 * cope with, and `FinalizerReference` extends `PhantomReference` the way Android's does.
 *
 * `FinalizerReference` also carries the `head` static and the `prev` and `next` fields of the doubly
 * linked list ART keeps every object with a `finalize()` method on, because that list is the only thing
 * holding an object waiting to be finalized — see [finalizerReference].
 */
class ReferenceClasses(
  val referenceId: Long,
  val softId: Long,
  val weakId: Long,
  val phantomId: Long,
  val finalizerId: Long
)

/**
 * [finalizerListHead] is what `FinalizerReference.head` points at, the first entry of the list. Its id
 * has to come from [HprofWriterHelper.reserveObjectId], since the class is written before its instances.
 * Null for a dump that isn't about that list, which is most of them.
 */
fun HprofWriterHelper.referenceClasses(
  finalizerListHead: ReferenceHolder = NULL
): ReferenceClasses {
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
      staticFields = listOf("head" to finalizerListHead),
      fields = listOf(
        "zombie" to ReferenceHolder::class,
        "prev" to ReferenceHolder::class,
        "next" to ReferenceHolder::class
      )
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
 *
 * [next] and [prev] are its place in the list `FinalizerReference.head` starts. [objectId] is for the
 * entry that static points at, whose id therefore had to be reserved before the class was written.
 */
fun HprofWriterHelper.finalizerReference(
  classes: ReferenceClasses,
  referent: ReferenceHolder = NULL,
  zombie: ReferenceHolder = NULL,
  next: ReferenceHolder = NULL,
  prev: ReferenceHolder = NULL,
  objectId: ReferenceHolder? = null
): ReferenceHolder = instance(
  classes.finalizerId,
  fields = listOf(zombie, prev, next, referent),
  objectId = objectId
)

/**
 * `sun.misc.Cleaner`, the `PhantomReference` subclass Android runs a `thunk` from once its referent is
 * gone, with the `first` static and the `next` and `prev` fields of the list it lives on. Held on a list
 * of its own for the same reason a `FinalizerReference` is, so it strands the same way when the links
 * aren't followed: `large-dump.hprof` has 3553 of these.
 *
 * [firstCleaner] is what the `first` static points at, from [HprofWriterHelper.reserveObjectId].
 */
fun HprofWriterHelper.cleanerClass(
  classes: ReferenceClasses,
  firstCleaner: ReferenceHolder = NULL
): Long = clazz(
  className = "sun.misc.Cleaner",
  superclassId = classes.phantomId,
  staticFields = listOf("first" to firstCleaner),
  fields = listOf(
    "thunk" to ReferenceHolder::class,
    "prev" to ReferenceHolder::class,
    "next" to ReferenceHolder::class
  )
)

/** One `sun.misc.Cleaner` of [cleanerClassId]. See [cleanerClass] for what each field is. */
fun HprofWriterHelper.cleaner(
  cleanerClassId: Long,
  referent: ReferenceHolder = NULL,
  thunk: ReferenceHolder = NULL,
  next: ReferenceHolder = NULL,
  prev: ReferenceHolder = NULL,
  objectId: ReferenceHolder? = null
): ReferenceHolder = instance(
  cleanerClassId,
  fields = listOf(thunk, prev, next, referent),
  objectId = objectId
)
