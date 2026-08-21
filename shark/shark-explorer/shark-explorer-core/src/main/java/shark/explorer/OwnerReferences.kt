package shark.explorer

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import java.util.BitSet
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.Reference

/**
 * A construct where one reference is the one that really holds an object, and every other reference to it
 * is a rival that happens to point at it.
 *
 * A view that's part of a hierarchy is the example to think with. Dozens of things end up pointing at
 * it — an input method manager, a fallback event handler, a view binding, a lifecycle owner, a view
 * holder — but what holds it is its parent, and the moment the parent lets go, none of the others keeps
 * it alive for long. Attributing its bytes to any of them says nothing true about the app, so a
 * dominator tree built without this rule scatters a window's views across whatever happened to be
 * closest to a GC root.
 *
 * A rule says nothing about the state the owned object is in, which is what makes it cheap and what makes
 * it right. "The parent owns an *attached* view" needs no attachment check: a detached hierarchy isn't held
 * by whatever holds the window, so if its parent isn't reachable, no owner reaches the child and
 * [OwnerReferences] falls back on the rivals by itself. The state the rule seems to need is already
 * expressed by which references exist.
 *
 * A rule is code rather than a pattern because two of these — which references own, and which objects are
 * owned — are one question. See [OwnerReferences.computeFor], and [ownerRulesFor] for the rules themselves.
 */
internal interface OwnerRule {

  /**
   * The classes whose instances can own something, subclasses included, so that the pass below reads the
   * objects that can own and no others. A rule naming a class the heap dump doesn't have owns nothing.
   */
  val ownerClassNames: Set<String>

  /**
   * Reports to [onOwned] every object [owner] owns, [owner] being an instance of one of
   * [ownerClassNames] or of a subclass of one.
   */
  fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  )
}

/**
 * Which objects of a heap dump something owns, and which references are the owning ones — [ownerRulesFor]
 * applied to one heap dump.
 *
 * The rule this exists to apply: **a reference into an object something else owns is not one of the ways
 * that object is held, unless nothing that owns it reached it.** A walk therefore parks a rival reference
 * instead of dropping it, and only takes a parked one once it has nothing else left to follow — see
 * [HeapReachability.walkFromGcRoots]. That's what makes the rule safe to apply without knowing anything
 * about the state of the objects: when the owner turns out not to be there, the walk calls
 * [markLastResortHeld] and the rivals count after all.
 *
 * Filled in by the walk, then read by everything that needs the same edges the walk followed:
 * [WeakeningAwareReferenceReader], and so the dominator tree, the referrer index and the path search.
 * So [isHeldThrough] is only meaningful once [HeapReachability.computeFor] has returned.
 */
internal class OwnerReferences private constructor(
  private val graph: HeapGraph,
  /**
   * Who owns each owned object of the heap dump, which is both halves of a rule at once: an object is owned
   * because something owns it, and the reference that owns it is the one from that something.
   *
   * Usually one owner. More than one is a construct with two ways of owning, and the tree then draws the
   * object under whatever dominates all of them, which is the honest answer and not a good place for bytes
   * to land — see [WindowDecorRule] for the one that taught us that.
   */
  private val ownersByOwnedObjectId: LongObjectMap<MutableLongSet>
) {

  /**
   * The owned objects nothing that owns them reached, so that what points at them is how they're held
   * after all: a view of a hierarchy whose parent is gone, a dialog's decor view once the dialog is
   * collected.
   */
  private val lastResortHeldObjectIndexes = BitSet(graph.objectCount)

  /**
   * Whether [reference] out of the object with [sourceObjectId] points at an object something else owns,
   * which is a reference a walk parks until it turns out to be the only one there is.
   *
   * One hash lookup for nearly every reference of a heap dump, which is what this being asked per reference
   * needs it to be: the objects a rule is about number in the thousands against the millions of references
   * a walk reads, and a reference into none of them is answered by the map not having the key.
   */
  fun isRivalReference(
    sourceObjectId: Long,
    reference: Reference
  ): Boolean {
    val owners = ownersByOwnedObjectId[reference.valueObjectId] ?: return false
    return !owners.contains(sourceObjectId)
  }

  /**
   * Records that nothing that owns [heapObject] reached it, so [isHeldThrough] answers true for every
   * reference into it from here on.
   */
  fun markLastResortHeld(heapObject: HeapObject) {
    lastResortHeldObjectIndexes.set(heapObject.objectIndex)
  }

  /**
   * Whether [reference] out of the object with [sourceObjectId] is one of the ways its target is held.
   *
   * True for everything but a rival reference into an object an owner reached. Nothing is lost by it:
   * every object was reached either through a reference this keeps, or from the parked references, and
   * then [markLastResortHeld] keeps all of them. So the graph the dominator tree is built from still has
   * every object of the heap dump in it, reachable and garbage alike.
   */
  fun isHeldThrough(
    sourceObjectId: Long,
    reference: Reference
  ): Boolean {
    if (!isRivalReference(sourceObjectId, reference)) {
      return true
    }
    val target = graph.findObjectByIdOrNull(reference.valueObjectId) ?: return true
    return lastResortHeldObjectIndexes.get(target.objectIndex)
  }

  companion object {

    /**
     * Applies [ownerRulesFor] to [graph], which takes one pass over its classes and one over its instances.
     *
     * Reading which objects are owned up front is what buys a hash lookup per reference later on, and it
     * has to be up front: a rival reference is one a walk has to recognise before anything has reached its
     * target.
     *
     * The class pass reads no object record — an instance's class comes from the heap dump index, and so
     * does the superclass of a class — and it is what holds the instance pass to the objects that can own
     * something. Which is a few thousand of the millions in a dump, so asking each of those for what it
     * owns costs a record read per possible owner rather than per object: measured against naming the owned
     * classes instead, 4 ms to 26 ms of the 1.5 s it takes to open the heap dumps in this repo.
     */
    fun computeFor(graph: HeapGraph): OwnerReferences {
      val rules = ownerRulesFor(graph)
      val rulesByOwnerClassId = rulesByOwnerClassIdOf(graph, rules)
      val ownersByOwnedObjectId = MutableLongObjectMap<MutableLongSet>()
      if (rulesByOwnerClassId.isNotEmpty()) {
        graph.instances.forEach { instance ->
          rulesByOwnerClassId[instance.instanceClassId]?.forEach { rule ->
            rule.forEachOwnedObject(instance) { ownedObjectId ->
              ownersByOwnedObjectId
                .getOrPut(ownedObjectId) { MutableLongSet(initialCapacity = 1) }
                .plusAssign(instance.objectId)
            }
          }
        }
      }
      return OwnerReferences(
        graph = graph,
        ownersByOwnedObjectId = ownersByOwnedObjectId
      )
    }

    /**
     * Which rules the instances of a class can own something through, for the classes whose instances can,
     * so that reading an instance's own class id is enough to tell.
     *
     * Empty for a heap dump none of the rules names a class of, which is what keeps a rule about Android
     * from costing anything on a dump of a JVM.
     */
    private fun rulesByOwnerClassIdOf(
      graph: HeapGraph,
      rules: List<OwnerRule>
    ): LongObjectMap<List<OwnerRule>> {
      val rulesByDeclaredClassId = MutableLongObjectMap<MutableList<OwnerRule>>()
      rules.forEach { rule ->
        rule.ownerClassNames.forEach { className ->
          graph.findClassByName(className)?.let { ownerClass ->
            rulesByDeclaredClassId.getOrPut(ownerClass.objectId) { mutableListOf() } += rule
          }
        }
      }
      val rulesByOwnerClassId = MutableLongObjectMap<List<OwnerRule>>()
      if (rulesByDeclaredClassId.isEmpty()) {
        return rulesByOwnerClassId
      }
      graph.classes.forEach { heapClass ->
        var rulesForClass: MutableList<OwnerRule>? = null
        heapClass.classHierarchy.forEach { superclass ->
          rulesByDeclaredClassId[superclass.objectId]?.let { declaredRules ->
            // Superclass first would be the other order, and either does: a class inheriting an owner rule
            // owns through it as well, and a rule appears once however many of its classes are in the
            // hierarchy.
            val rulesSoFar = rulesForClass ?: mutableListOf<OwnerRule>().also { rulesForClass = it }
            declaredRules.forEach { if (it !in rulesSoFar) rulesSoFar += it }
          }
        }
        rulesForClass?.let { rulesByOwnerClassId[heapClass.objectId] = it }
      }
      return rulesByOwnerClassId
    }
  }
}
