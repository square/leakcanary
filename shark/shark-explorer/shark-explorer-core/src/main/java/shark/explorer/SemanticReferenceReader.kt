package shark.explorer

import shark.HeapObject
import shark.Reference
import shark.ReferenceReader
import shark.explorer.ReachabilityStrength.STRONG

/**
 * The edge set the explorer's **semantic dominators** are computed over: which of a heap dump's references
 * count as ways their target is held, rather than all of them.
 *
 * Two patterns curate it, and this is the one place both are gated, so the dominator tree, the referrer
 * index and the path search all see the same edges. Deny-listing a reference is one of them — see
 * [ReferenceStrengthReader] — and allow-listing an owner is the other, which is the same rule read off the
 * other end of a reference: see [OwnerReferences].
 *
 * Reads the references that retain their target, plus the ones that hold an object without retaining it
 * — a `java.lang.ref.Reference`'s referent, a cache's entries, a thread local — when such a reference is
 * the strongest thing reaching the object.
 *
 * That's what puts a weakly reachable object in the tree, dominated by the weak reference itself, and a
 * cached one under its cache entry: every object of the heap dump is a node, and every one of them is
 * held by whatever would have to let go of it first.
 *
 * **The target's strength decides, not the reference's.** Following a weak reference to an object that
 * something else holds strongly wouldn't reveal anything — the object is already in the tree — but it
 * would add an edge, which moves the object's retained size up to whatever dominates both paths and
 * attributes it to neither. Which is exactly what a weak reference isn't: it holds nothing.
 *
 * **And a path stays as weak as its weakest reference.** An object held only by a finalizer queue, a
 * thread local or a stack frame doesn't hold what something firmer holds either, so every reference out of
 * it is weighed the same way. Without that, a `FinalizerReference` two steps up a chain would be one more
 * way of holding an object that a field holds squarely, which is a way of holding nothing.
 *
 * Leaves out the references that lost to an owner for the same reason: they lost during the walk, which is
 * where the deferring happens, so by the time this reads them the verdict is already in.
 */
internal class SemanticReferenceReader(
  private val strengthReader: ReferenceStrengthReader,
  private val reachability: HeapReachability,
  private val ownerReferences: OwnerReferences
) : ReferenceReader<HeapObject> {

  override fun read(source: HeapObject): Sequence<Reference> {
    val pathStrength = reachability.strengthOf(source)
    val ownership = ownerReferences.ownershipOf(source)
    val retaining = strengthReader.retainingReferencesOf(source)
      .filter { ownerReferences.isHeldThrough(ownership, it) }
      .let { references ->
        // Nothing to weigh when the path here is strong, which it is for nearly every object of a dump.
        if (pathStrength == STRONG) {
          references
        } else {
          references.filter { reachability.isHeldThrough(it.valueObjectId, pathStrength) }
        }
      }
    val followed = strengthReader.weakeningReferencesOf(source)
      .filter { reachability.isHeldThrough(it.valueObjectId, maxOf(pathStrength, it.strength)) }
    return if (followed.isEmpty()) {
      retaining
    } else {
      retaining + followed.asSequence().map { it.toReference() }
    }
  }
}
