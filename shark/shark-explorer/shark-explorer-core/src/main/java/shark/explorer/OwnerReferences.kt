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
import shark.explorer.OwnedObjects.HeldByScopedProviders
import shark.explorer.OwnedObjects.InstancesOf

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
  /** Which objects of a heap dump the rule is about. */
  val ownedObjects: OwnedObjects,
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
 * How an [OwnerRule] says which objects of a heap dump it is about.
 *
 * Two ways, because the cheap one doesn't always apply. Naming a class is a scan of two indexes and no
 * object record, so it's the one to reach for — but it needs the owned objects to have a class in
 * common, and the objects of some constructs have nothing in common but where they're held.
 */
internal sealed class OwnedObjects {

  /** Every instance of [className] and of its subclasses, which the heap dump index answers on its own. */
  class InstancesOf(val className: String) : OwnedObjects()

  /**
   * Whatever the [providers] are holding, whatever its class — for a dependency injection singleton,
   * which is any type at all. What makes an object one is a provider caching it, so there is nothing
   * about the object itself to recognise, and this reads the providers to find out.
   */
  class HeldByScopedProviders(val providers: List<ScopedProvider>) : OwnedObjects()
}

/**
 * A dependency injection framework's scoped provider: the object a generated component keeps a binding's
 * instance in, so that every injection point of that scope is handed the same one.
 *
 * Named per framework rather than found by shape, since a provider is an ordinary class with an ordinary
 * field and nothing in a heap dump marks it. Each entry is four names, and all four are checkable against
 * a dump of an app using the framework — a wrong one doesn't fail, it silently stops finding singletons.
 */
internal class ScopedProvider(
  /**
   * The provider class, subclasses included, so that naming Metro's `BaseDoubleCheck` covers the
   * `DoubleCheck` its generated code really instantiates.
   */
  val className: String,
  /** The field the provider caches the instance in, once something has asked it for one. */
  val instanceFieldName: String,
  /** The class declaring the static field below, which is the provider's own only for Dagger. */
  val uninitializedHolderClassName: String,
  /**
   * The static field holding what [instanceFieldName] points at until something asks: one sentinel object
   * the framework shares between every provider in the process.
   *
   * Which has to be skipped rather than ignored, because it is a real object that real fields point at.
   * Counting it as owned would hand a bare `Object` to whichever provider hadn't been asked yet and take
   * the static field that does hold it out of the tree.
   */
  val uninitializedFieldName: String
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

    private const val MODIFIER_NODE_CLASS_NAME = "androidx.compose.ui.Modifier\$Node"

    /**
     * The scoped providers of the dependency injection frameworks the explorer knows about, read off a
     * heap dump of an app built with each — see `notes/dependency-injection.md`.
     *
     * Both frameworks null the provider's own `provider` field once it has produced the instance, so a
     * live provider points at nothing but its singleton.
     */
    private val SCOPED_PROVIDERS = listOf(
      // Dagger keeps the sentinel on the provider class itself.
      ScopedProvider(
        className = "dagger.internal.DoubleCheck",
        instanceFieldName = "instance",
        uninitializedHolderClassName = "dagger.internal.DoubleCheck",
        uninitializedFieldName = "UNINITIALIZED"
      ),
      // Metro's is a top level property of the file declaring the class, so it lives on the file's facade
      // class rather than on the provider — which is why this needs a class name of its own.
      ScopedProvider(
        className = "dev.zacsweers.metro.internal.BaseDoubleCheck",
        instanceFieldName = "_value",
        uninitializedHolderClassName = "dev.zacsweers.metro.internal.BaseDoubleCheckKt",
        uninitializedFieldName = "UNINITIALIZED"
      )
    )

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
        ownedObjects = InstancesOf(VIEW_CLASS_NAME),
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
        ownedObjects = InstancesOf(VIEW_CLASS_NAME),
        ownerFieldsByClassName = mapOf(
          ACTIVITY_CLASS_NAME to setOf("mDecor"),
          "android.app.Dialog" to setOf("mDecor")
        )
      ),
      // An activity belongs to the thread running it, through the virtual reference
      // [RunningActivityReferenceReader] reads from an ActivityThread to each activity in mActivities.
      // Everything else that points at one — a context wrapper, a view, a fragment, a presenter, a
      // callback — is something the activity brought along. Self-clearing in the same way:
      // ActivityThread.handleDestroyActivity takes the record out of mActivities, so a destroyed activity
      // has no owner left and falls back on whatever is leaking it, which is exactly what you want its
      // bytes drawn under.
      //
      // Not ActivityClientRecord.activity, which is what this rule used to say. A record is a slot of the
      // ArrayMap the thread keeps its activities in, and naming the slot leaves the map, its Object[] and
      // the record itself between the thread and every activity, so the chain from a GC root down to a
      // screen spends three of its steps on a map's bookkeeping and the thread's own rectangle is a pile of
      // records rather than a row of screens to compare.
      OwnerRule(
        ownedObjects = InstancesOf(ACTIVITY_CLASS_NAME),
        ownerVirtualClassNames = setOf(RunningActivityReferenceReader.ACTIVITY_THREAD_CLASS_NAME)
      ),
      // A Compose UI is a tree of LayoutNodes and the same rule applies to it, through the virtual
      // reference [LayoutNodeChildReferenceReader] reads from a parent to each child.
      //
      // It needs saying louder here than for a view, because Compose keeps a flat registry of every node
      // of a window — `AndroidComposeView.layoutNodes`, by semantics id — so *every* node of a UI is one
      // reference from the view that hosts it, and the tree of a screen is a list until this rule turns
      // it back into a tree. The rest of the rivals are Compose's own graphs pointing sideways: the nodes
      // waiting to be measured, the ones waiting to be positioned, a focus listener the input method
      // manager holds, a modifier node's coordinator.
      OwnerRule(
        ownedObjects = InstancesOf(LayoutNodeChildReferenceReader.LAYOUT_NODE_CLASS_NAME),
        ownerVirtualClassNames = setOf(LayoutNodeChildReferenceReader.LAYOUT_NODE_CLASS_NAME)
      ),
      // The node at the top of a window has no parent node, and belongs to the view hosting it — the same
      // rule as a decor view belonging to its Activity, and what puts a Compose UI's bytes inside the
      // hierarchy of the screen showing it.
      //
      // The composition holds that node too, in a slot of its table, and this is deliberately the owner
      // instead: a composition is one flat store per window, so owning from there would be the registry
      // problem again. See [SlotTableReferenceReader] for what a slot reference is.
      OwnerRule(
        ownedObjects = InstancesOf(LayoutNodeChildReferenceReader.LAYOUT_NODE_CLASS_NAME),
        ownerFieldsByClassName = mapOf(
          "androidx.compose.ui.platform.AndroidComposeView" to setOf("root")
        )
      ),
      // What a node of a Compose UI is made of belongs to that node: its modifiers are a chain hanging
      // off its `NodeChain`, from the outermost through each one's `child` to the tail.
      //
      // Without this the chain is a way *into* the UI rather than a part of it, and that is measurable:
      // Compose's modifier nodes point back at their coordinators, which point back at their layers and
      // at each other, so a single reference into any one of them reaches the lot. A heap dump taken on
      // API 36 has three such references from outside — a focus listener the input method manager reaches
      // through the window's `ViewTreeObserver`, the snapshot observer's static list of what it observes,
      // and the `Recomposer` — so every modifier of every screen was held from a GC root of its own, and
      // the images the UI draws were dominated by the top of the heap rather than by the UI showing them.
      //
      // `child` and not `parent`: a chain has to be owned in one direction, and it reads outermost first,
      // the way the modifiers were written.
      OwnerRule(
        ownedObjects = InstancesOf(MODIFIER_NODE_CLASS_NAME),
        ownerFieldsByClassName = mapOf(
          "androidx.compose.ui.node.NodeChain" to setOf("head"),
          MODIFIER_NODE_CLASS_NAME to setOf("child")
        )
      ),
      // A dependency injection singleton is held by the provider its component caches it in, and every
      // other reference to one is an injection site the component handed it to. Which is nearly always a
      // lot of them, all over the app, so without this rule a singleton is dominated by whatever dominates
      // the whole graph of things that were injected with it — the top of the tree. Measured on a JVM dump
      // of a Dagger and a Metro component: every scoped instance came out under the root, and under this
      // rule every one of them came out under its own component.
      //
      // The provider and not the component, though the component is what you'd want to read the bytes
      // under, because **nothing in a heap dump says which object is a component**. Dagger's generated one
      // is a `DaggerAppComponent$AppComponentImpl`, which a name could just about catch; Metro's is an
      // `AppGraph$Impl`, an `Impl` nested in the interface the app declared, with no marker of any kind.
      // A rule about the provider needs no such guess, and the component still collects the bytes, one
      // step further down: it is what holds every provider.
      //
      // Self-clearing in the way the rest of these are. A provider never lets go of its instance, so there
      // is no state to check: while the component is reachable so is the provider, and once the component
      // is gone the provider is gone with it, no owner reaches the singleton, and whatever is still
      // pointing at it is both how it's held and what's leaking it.
      OwnerRule(
        ownedObjects = HeldByScopedProviders(SCOPED_PROVIDERS),
        ownerFieldsByClassName = SCOPED_PROVIDERS.associate {
          it.className to setOf(it.instanceFieldName)
        }
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
      RULES.mapNotNullTo(mutableSetOf()) { (it.ownedObjects as? InstancesOf)?.className }
        .forEach { className ->
          graph.findClassByName(className)?.let { ownedClassIds += it.objectId }
        }
      val scopedProviders = RULES.flatMap {
        (it.ownedObjects as? HeldByScopedProviders)?.providers.orEmpty()
      }
      return OwnerReferences(
        graph = graph,
        ownedObjectIds = ownedObjectIdsOf(graph, ownedClassIds, scopedProviders),
        ownerFieldsByClassName = ownerFieldsByClassName(),
        ownerVirtualClassNames = RULES.flatMapTo(mutableSetOf()) { it.ownerVirtualClassNames }
      )
    }

    /**
     * The instances of [ownedClassIds] and of their subclasses, plus what the [scopedProviders] found in
     * the same pass are holding, by object id.
     *
     * The class half reads no object record: an instance's class comes from the heap dump index, and so
     * does the superclass of a class, so it is a scan of two indexes. The provider half then reads one
     * record per provider, which is one per binding a component has been asked for rather than one per
     * object — a few thousand at the very most, against the millions the index pass walks past.
     */
    private fun ownedObjectIdsOf(
      graph: HeapGraph,
      ownedClassIds: LongSet,
      scopedProviders: List<ScopedProvider>
    ): LongSet {
      val ownedObjectIds = MutableLongSet()
      // Only the frameworks the heap dump has classes for, so an app using neither pays one class lookup
      // per framework and nothing else.
      val providerByDeclaringClassId = MutableLongObjectMap<ScopedProvider>()
      scopedProviders.forEach { provider ->
        graph.findClassByName(provider.className)?.let {
          providerByDeclaringClassId[it.objectId] = provider
        }
      }
      if (ownedClassIds.isEmpty() && providerByDeclaringClassId.isEmpty()) {
        return ownedObjectIds
      }
      // Both halves need the subclasses of a named class, so both read them out of the one pass:
      // HeapClass.subclasses is a scan of every class of the dump, and there are several names here.
      val subclassIds = MutableLongSet()
      val providerByClassId = MutableLongObjectMap<ScopedProvider>()
      graph.classes.forEach { heapClass ->
        heapClass.classHierarchy.forEach { superclass ->
          if (ownedClassIds.contains(superclass.objectId)) {
            subclassIds += heapClass.objectId
          }
          providerByDeclaringClassId[superclass.objectId]?.let {
            providerByClassId[heapClass.objectId] = it
          }
        }
      }
      val providerInstances = mutableListOf<HeapInstance>()
      graph.instances.forEach { instance ->
        if (subclassIds.contains(instance.instanceClassId)) {
          ownedObjectIds += instance.objectId
        }
        if (providerByClassId.containsKey(instance.instanceClassId)) {
          providerInstances += instance
        }
      }
      val uninitializedValueIds = uninitializedValueIdsOf(graph, providerByDeclaringClassId)
      providerInstances.forEach { providerInstance ->
        val provider = providerByClassId[providerInstance.instanceClassId]!!
        val heldObjectId = providerInstance[provider.className, provider.instanceFieldName]
          ?.value
          ?.asNonNullObjectId
        if (heldObjectId != null && !uninitializedValueIds.contains(heldObjectId)) {
          ownedObjectIds += heldObjectId
        }
      }
      return ownedObjectIds
    }

    /** The sentinel each of the frameworks present in [graph] leaves in a provider nothing has asked yet. */
    private fun uninitializedValueIdsOf(
      graph: HeapGraph,
      providerByDeclaringClassId: MutableLongObjectMap<ScopedProvider>
    ): LongSet {
      val uninitializedValueIds = MutableLongSet()
      providerByDeclaringClassId.forEachValue { provider ->
        graph.findClassByName(provider.uninitializedHolderClassName)
          ?.get(provider.uninitializedFieldName)
          ?.value
          ?.asNonNullObjectId
          ?.let { uninitializedValueIds += it }
      }
      return uninitializedValueIds
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
