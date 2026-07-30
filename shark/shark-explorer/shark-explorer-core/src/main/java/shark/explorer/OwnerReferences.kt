package shark.explorer

import androidx.collection.LongSet
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import java.util.BitSet
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.Reference

/**
 * A construct where one reference is the one that really holds an object, and every other reference to
 * it is a rival that happens to point at it.
 *
 * A view that's part of a hierarchy is the example to think with. Dozens of things end up pointing at
 * it — an input method manager, a fallback event handler, a view binding, a lifecycle owner, a view
 * holder — but what holds it is its parent, and the moment the parent lets go, none of the others keeps
 * it alive for long. Attributing its bytes to any of them says nothing true about the app, so a
 * dominator tree built without this rule scatters a window's views across whatever happened to be
 * closest to a GC root.
 *
 * A rule says nothing about the state the owned object is in, which is what makes it cheap and what
 * makes it right. "The parent owns an *attached* view" needs no attachment check: a detached hierarchy
 * isn't held by whatever holds the window, so if its parent isn't reachable, no owner reaches the child
 * and [OwnerReferences] falls back on the rivals by itself. The state the rule seems to need is already
 * expressed by which references exist.
 */
internal class OwnerRule(
  /** The class whose instances something owns, subclasses included. */
  val ownedClassName: String,
  /**
   * The fields that own what they point at, by the name of the class declaring them. Read against the
   * whole class hierarchy of the referring object, so a rule on a base class covers every subclass.
   */
  val ownerFieldsByClassName: Map<String, Set<String>> = emptyMap(),
  /**
   * The classes whose virtual references own what they point at, subclasses included.
   *
   * Those are the references the explorer adds itself, to present a structure the way you think about it
   * instead of the way it's built, and an owner named that way is named by what it is rather than by a
   * field it holds the object in: a `ViewGroup` owns the children [ViewChildReferenceReader] reads for it,
   * whichever slot of whichever array each one is really in.
   */
  val ownerVirtualClassNames: Set<String> = emptySet()
)

/**
 * Which objects of a heap dump something owns, and which references are the owning ones — the
 * [OwnerRule] list applied to one heap dump.
 *
 * The rule this exists to apply: **a reference into an object something else owns is not one of the ways
 * that object is held, unless nothing that owns it reached it.** A walk therefore parks a rival
 * reference instead of dropping it, and only takes a parked one once it has nothing else left to
 * follow — see [HeapReachability.walkFromGcRoots]. That's what makes the rule safe to apply without
 * knowing anything about the state of the objects: when the owner turns out not to be there, the walk
 * calls [markLastResortHeld] and the rivals count after all.
 *
 * Filled in by the walk, then read by everything that needs the same edges the walk followed:
 * [WeakeningAwareReferenceReader], and so the dominator tree, the referrer index and the path search.
 * So [isHeldThrough] is only meaningful once [HeapReachability.computeFor] has returned.
 */
internal class OwnerReferences private constructor(
  private val graph: HeapGraph,
  /** Every object of the heap dump an [OwnerRule] applies to, however it turns out to be held. */
  private val ownedObjectIds: LongSet,
  private val ownerFieldsByClassName: Map<String, Set<String>>,
  private val ownerVirtualClassNames: Set<String>
) {

  /**
   * What the instances of a class own, by class object id. Cached because it takes a class hierarchy walk
   * to work out and a heap dump has far more instances than classes, the same way
   * [ReferenceStrengthReader] caches its weakening fields.
   */
  private val ownershipByClassId = MutableLongObjectMap<Ownership>()

  /**
   * The owned objects nothing that owns them reached, so that what points at them is how they're held
   * after all: a view of a hierarchy whose parent is gone, a dialog's decor view once the dialog is
   * collected.
   */
  private val lastResortHeldObjectIndexes = BitSet(graph.objectCount)

  /** What [source] owns, worked out once per object rather than once per reference out of it. */
  fun ownershipOf(source: HeapObject): Ownership =
    if (source is HeapInstance) ownershipOfInstancesOf(source.instanceClass) else OWNS_NOTHING

  /**
   * Whether [reference] out of an object with [sourceOwnership] points at an object something else owns,
   * which is a reference a walk parks until it turns out to be the only one there is.
   */
  fun isRivalReference(
    sourceOwnership: Ownership,
    reference: Reference
  ): Boolean = ownedObjectIds.contains(reference.valueObjectId) && !sourceOwnership.owns(reference)

  /**
   * Records that nothing that owns [heapObject] reached it, so [isHeldThrough] answers true for every
   * reference into it from here on.
   */
  fun markLastResortHeld(heapObject: HeapObject) {
    lastResortHeldObjectIndexes.set(heapObject.objectIndex)
  }

  /**
   * Whether [reference] out of an object with [sourceOwnership] is one of the ways its target is held.
   *
   * True for everything but a rival reference into an object an owner reached. Nothing is lost by it:
   * every object was reached either through a reference this keeps, or from the parked references, and
   * then [markLastResortHeld] keeps all of them. So the graph the dominator tree is built from still has
   * every object of the heap dump in it, reachable and garbage alike.
   */
  fun isHeldThrough(
    sourceOwnership: Ownership,
    reference: Reference
  ): Boolean {
    if (!isRivalReference(sourceOwnership, reference)) {
      return true
    }
    val target = graph.findObjectByIdOrNull(reference.valueObjectId) ?: return true
    return lastResortHeldObjectIndexes.get(target.objectIndex)
  }

  /** Every instance of a class owns the same fields and the same virtual references. */
  private fun ownershipOfInstancesOf(instanceClass: HeapClass): Ownership =
    ownershipByClassId.getOrPut(instanceClass.objectId) {
      val hierarchy = instanceClass.classHierarchy.toList()
      // Superclass first, so that a subclass adding an owner field keeps the ones it inherits: an
      // Activity subclass owns its decor view through the field android.app.Activity declares.
      val ownerFieldNames = hierarchy
        .asReversed()
        .fold(emptySet<String>()) { inherited, heapClass ->
          ownerFieldsByClassName[heapClass.name]?.let { inherited + it } ?: inherited
        }
      // Against the hierarchy for the same reason: a LinearLayout owns its children through the rule on
      // android.view.ViewGroup.
      val ownsEveryVirtualReference = hierarchy.any { it.name in ownerVirtualClassNames }
      if (ownerFieldNames.isEmpty() && !ownsEveryVirtualReference) {
        OWNS_NOTHING
      } else {
        Ownership(ownerFieldNames, ownsEveryVirtualReference)
      }
    }

  /**
   * What one object owns: the values of a set of named fields, and whether the virtual references the
   * explorer reads for it own what they point at. For nearly every object of a heap dump, nothing at all.
   */
  internal class Ownership(
    private val ownerFieldNames: Set<String>,
    private val ownsEveryVirtualReference: Boolean
  ) {

    /**
     * Whether [reference] is the owning one. Only ever asked about a reference into an owned object, so
     * resolving the details of one to read its name stays rare — it's the names that cost, and an object
     * that owns nothing never gets that far.
     */
    fun owns(reference: Reference): Boolean {
      if (ownerFieldNames.isEmpty() && !ownsEveryVirtualReference) {
        return false
      }
      val details = reference.lazyDetailsResolver.resolve()
      return if (details.isVirtual) {
        ownsEveryVirtualReference
      } else {
        details.name in ownerFieldNames
      }
    }
  }

  companion object {

    private const val VIEW_CLASS_NAME = "android.view.View"

    private const val ACTIVITY_CLASS_NAME = "android.app.Activity"

    /**
     * The constructs the explorer knows about. Curated: each one is a claim that a reference is *the* way
     * an object is held, and getting that wrong moves bytes to the wrong place in the tree.
     */
    private val RULES = listOf(
      // A view of a hierarchy is held by its parent, through the virtual reference
      // [ViewChildReferenceReader] reads from a ViewGroup to each of its children.
      //
      // Not through the View[] the framework really keeps them in, which is what this rule used to say.
      // An array owns by its type or not at all — there is nothing on it to say whose children it holds —
      // so a rule about View[] also hands ownership to an app's own array of views it merely points at,
      // and it leaves a hierarchy hanging off an unnamed array at every level of the tree.
      OwnerRule(
        ownedClassName = VIEW_CLASS_NAME,
        ownerVirtualClassNames = setOf(ViewChildReferenceReader.VIEW_GROUP_CLASS_NAME)
      ),
      // The root view of a hierarchy has no parent to own it, and belongs to whatever the hierarchy is
      // for. Attributing a window's views to the Activity or Dialog they're for is the whole point:
      // otherwise they land under the WindowManagerGlobal that holds every window of the process, which
      // tells you nothing about which screen is expensive.
      //
      // The framework nulls Activity.mDecor when it destroys the activity, and that is the whole of
      // "unless the activity is destroyed": a rule needs no state check when the field is already gone.
      //
      // Not PhoneWindow.mDecor, which also holds the decor view and is set whether the activity is
      // destroyed or not. Two owner references are two ways of owning, so the decor view ends up
      // dominated by whatever dominates both, and on a real app dump that's the top of the tree: a jank
      // monitor held the window from a GC root of its own, which cost the activity all 18 MB of its
      // hierarchy. One owner per construct, and it should be the one you'd want to read the bytes under.
      OwnerRule(
        ownedClassName = VIEW_CLASS_NAME,
        ownerFieldsByClassName = mapOf(
          ACTIVITY_CLASS_NAME to setOf("mDecor"),
          "android.app.Dialog" to setOf("mDecor")
        )
      ),
      // An activity belongs to the list of activities the process is running, and everything else that
      // points at one — a context wrapper, a view, a fragment, a presenter, a callback — is something the
      // activity brought along. Self-clearing in the same way: ActivityThread.handleDestroyActivity takes
      // the record out of mActivities, so a destroyed activity has no owner left and falls back on
      // whatever is leaking it, which is exactly what you want its bytes drawn under.
      OwnerRule(
        ownedClassName = ACTIVITY_CLASS_NAME,
        ownerFieldsByClassName = mapOf(
          "android.app.ActivityThread\$ActivityClientRecord" to setOf("activity")
        )
      )
    )

    private val OWNS_NOTHING = Ownership(emptySet(), false)

    /**
     * Applies [RULES] to [graph], which takes one pass over its classes and one over its instances.
     *
     * The pass over the instances is what buys a hash lookup per reference later on, instead of a class
     * hierarchy walk: reading which objects are owned up front costs one index scan, and a heap dump has
     * millions of references but only thousands of views.
     */
    fun computeFor(graph: HeapGraph): OwnerReferences {
      val ownedClassIds = MutableLongSet()
      RULES.mapTo(mutableSetOf()) { it.ownedClassName }.forEach { className ->
        graph.findClassByName(className)?.let { ownedClassIds += it.objectId }
      }
      return OwnerReferences(
        graph = graph,
        ownedObjectIds = ownedObjectIdsOf(graph, ownedClassIds),
        ownerFieldsByClassName = ownerFieldsByClassName(),
        ownerVirtualClassNames = RULES.flatMapTo(mutableSetOf()) { it.ownerVirtualClassNames }
      )
    }

    /**
     * The instances of [ownedClassIds] and of their subclasses, by object id.
     *
     * Reads no object record: an instance's class comes from the heap dump index, and so does the
     * superclass of a class, so this is a scan of two indexes.
     */
    private fun ownedObjectIdsOf(
      graph: HeapGraph,
      ownedClassIds: LongSet
    ): LongSet {
      val ownedObjectIds = MutableLongSet()
      if (ownedClassIds.isEmpty()) {
        return ownedObjectIds
      }
      val subclassIds = MutableLongSet()
      graph.classes.forEach { heapClass ->
        if (heapClass.classHierarchy.any { ownedClassIds.contains(it.objectId) }) {
          subclassIds += heapClass.objectId
        }
      }
      graph.instances.forEach { instance ->
        if (subclassIds.contains(instance.instanceClassId)) {
          ownedObjectIds += instance.objectId
        }
      }
      return ownedObjectIds
    }

    /** [RULES]' owner fields merged, so that two rules on the same class both hold. */
    private fun ownerFieldsByClassName(): Map<String, Set<String>> {
      val merged = mutableMapOf<String, Set<String>>()
      RULES.forEach { rule ->
        rule.ownerFieldsByClassName.forEach { (className, fieldNames) ->
          merged[className] = merged[className].orEmpty() + fieldNames
        }
      }
      return merged
    }
  }
}
