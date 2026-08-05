package shark.explorer

import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.ValueHolder

/**
 * The constructs the explorer knows about. Curated: each one is a claim that a reference is *the* way an
 * object is held, and getting that wrong moves bytes to the wrong place in the tree.
 *
 * Created per heap dump, so a rule resolves the class it is about once and answers off the index from
 * there, the way [ViewChildReferenceReader] and [RunningActivityReferenceReader] do. A rule whose classes
 * aren't in the dump names no owner and therefore owns nothing, which is what keeps a rule about Android
 * from costing anything on a dump of a JVM.
 */
internal fun ownerRulesFor(graph: HeapGraph): List<OwnerRule> = listOf(
  ViewParentRule(graph),
  WindowDecorRule(),
  ActivityWindowRule(),
  RunningActivityRule(graph),
  LayoutNodeParentRule(graph),
  ComposeUiRootNodeRule(),
  ModifierChainRule(),
  ScopedProviderRule(graph)
)

/**
 * A view of a hierarchy is held by its parent, through the virtual reference [ViewChildReferenceReader]
 * reads from a `ViewGroup` to each of its children.
 *
 * Not through the `View[]` the framework really keeps them in, which is what this rule used to say. An
 * array owns by its type or not at all — there is nothing on it to say whose children it holds — so a rule
 * about `View[]` also hands ownership to an app's own array of views it merely points at, and it leaves a
 * hierarchy hanging off an unnamed array at every level of the tree.
 */
internal class ViewParentRule(graph: HeapGraph) : OwnerRule {

  private val childReferenceReader = ViewChildReferenceReader(graph)

  override val ownerClassNames = setOf(ViewChildReferenceReader.VIEW_GROUP_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    childReferenceReader.childReferencesOf(owner).forEach { onOwned(it.valueObjectId) }
  }
}

/**
 * The decor view at the top of a hierarchy has no parent to own it, and is held by the window it is the
 * decor of.
 *
 * Not by the `Activity` or `Dialog`, which is what this rule used to say. `Activity.mDecor` is written in
 * one place, `ActivityThread.handleResumeActivity`, guarded by the activity's record having no window yet —
 * so it is set once per `ActivityClientRecord` rather than once per activity, and an activity recreated on a
 * configuration change is visible, resumed, and has a null `mDecor` for the rest of its life. Which is most
 * of the activities in the heap dumps here. Worse than useless: with the decor view declared owned and no
 * owner reaching it, every rival counted, and a window's whole hierarchy was drawn flat at the top of the
 * tree.
 *
 * The window holds it whether the activity is destroyed or not, so this rule leans on [ActivityWindowRule]
 * for the fallback the field used to give: it is the *window* that stops being owned when its activity goes,
 * and the hierarchy comes with it.
 *
 * One owner and not both, though — a decor view owned by its window *and* its activity is dominated by
 * whatever dominates the two, and on a real app dump that's the top of the tree: a jank monitor held the
 * window from a GC root of its own, which cost the activity all 18 MB of its hierarchy.
 */
internal class WindowDecorRule : OwnerRule {

  override val ownerClassNames = setOf(WINDOW_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    // By field name rather than by declaring class, because the class declaring it is internal and has
    // moved: com.android.internal.policy.impl.PhoneWindow before Lollipop, com.android.internal.policy
    // .PhoneWindow since. Reading the fields of a window costs the record read this rule makes anyway.
    owner.readFields()
      .firstOrNull { it.name == DECOR_FIELD_NAME }
      ?.value
      ?.asNonNullObjectId
      ?.let(onOwned)
  }

  companion object {
    private const val WINDOW_CLASS_NAME = "android.view.Window"
    private const val DECOR_FIELD_NAME = "mDecor"
  }
}

/**
 * A window is held by the `Activity` or `Dialog` it is the window of. Everything else pointing at one is
 * something that needs to reach the window: the `WindowManagerImpl` that added it, an
 * `AppCompatDelegateImpl`, a `DecorContext`, a menu callback, the window's own inner classes.
 *
 * Attributing a window to what it is for is what makes the rest of these rules land somewhere worth reading.
 * Its decor view is nearly all of its bytes ([WindowDecorRule]), and a window with several referrers is
 * otherwise dominated by whatever dominates all of them — measured on the heap dumps here, 6 of 16 windows
 * were drawn at the top of the tree, taking their hierarchy with them.
 *
 * The one rule here that reads the state of its owner, because this is the one place the framework doesn't
 * express it in references. `Activity.mWindow` is set in `attach` and never cleared, and `Dialog.mWindow` is
 * final, so a destroyed activity and a dismissed dialog both still point at a window they have nothing to do
 * with. Owning it anyway would take the only honest answer away from the one case that needs it: a leaked
 * screen whose window something else holds as well is two things to fix, and it reads as one if the leak owns
 * the window. So a destroyed activity owns nothing, and neither does a dismissed dialog — which the framework
 * says by nulling `Dialog.mDecor`, the field that used to be this rule and is a reliable signal for a dialog
 * even though it isn't one for an activity ([WindowDecorRule]).
 */
internal class ActivityWindowRule : OwnerRule {

  override val ownerClassNames = setOf(ACTIVITY_CLASS_NAME, DIALOG_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    if (!owner.isScreenThatIsUp()) {
      return
    }
    ownerClassNames.forEach { className ->
      owner[className, WINDOW_FIELD_NAME]?.value?.asNonNullObjectId?.let(onOwned)
    }
  }

  /** Whether the framework hasn't finished with [this] screen yet. */
  private fun HeapInstance.isScreenThatIsUp(): Boolean {
    // A dialog says so with its own mDecor: show sets it, dismiss nulls it. Asked first because having the
    // field is also what tells a dialog from an activity — an activity has no android.app.Dialog.mDecor.
    val dialogDecor = this[DIALOG_CLASS_NAME, DECOR_FIELD_NAME]
    if (dialogDecor != null) {
      return dialogDecor.value.asNonNullObjectId != null
    }
    // An activity says so with mDestroyed, which API 17 introduced — a dump too old to have the field is not
    // a dump of destroyed screens.
    return this[ACTIVITY_CLASS_NAME, DESTROYED_FIELD_NAME]?.value?.asBoolean != true
  }

  companion object {
    private const val ACTIVITY_CLASS_NAME = "android.app.Activity"
    private const val DIALOG_CLASS_NAME = "android.app.Dialog"
    private const val WINDOW_FIELD_NAME = "mWindow"
    private const val DESTROYED_FIELD_NAME = "mDestroyed"
    private const val DECOR_FIELD_NAME = "mDecor"
  }
}

/**
 * An activity belongs to the thread running it, through the virtual reference
 * [RunningActivityReferenceReader] reads from an `ActivityThread` to each activity in `mActivities`.
 * Everything else that points at one — a context wrapper, a view, a fragment, a presenter, a callback — is
 * something the activity brought along.
 *
 * Self-clearing in the same way as the rest: `ActivityThread.handleDestroyActivity` takes the record out of
 * `mActivities`, so a destroyed activity has no owner left and falls back on whatever is leaking it, which
 * is exactly what you want its bytes drawn under.
 */
internal class RunningActivityRule(graph: HeapGraph) : OwnerRule {

  private val activityReferenceReader = RunningActivityReferenceReader(graph)

  override val ownerClassNames = setOf(RunningActivityReferenceReader.ACTIVITY_THREAD_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    activityReferenceReader.activityReferencesOf(owner).forEach { onOwned(it.valueObjectId) }
  }
}

/**
 * A Compose UI is a tree of `LayoutNode`s and [ViewParentRule] applies to it too, through the virtual
 * reference [LayoutNodeChildReferenceReader] reads from a parent to each child.
 *
 * It needs saying louder than for a view, because Compose keeps a flat registry of every node of a window —
 * `AndroidComposeView.layoutNodes`, by semantics id — so *every* node of a UI is one reference from the view
 * that hosts it, and the tree of a screen is a list until this rule turns it back into a tree. The rest of
 * the rivals are Compose's own graphs pointing sideways: the nodes waiting to be measured, the ones waiting
 * to be positioned, a focus listener the input method manager holds, a modifier node's coordinator.
 */
internal class LayoutNodeParentRule(graph: HeapGraph) : OwnerRule {

  private val childReferenceReader = LayoutNodeChildReferenceReader(graph)

  override val ownerClassNames = setOf(LayoutNodeChildReferenceReader.LAYOUT_NODE_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    childReferenceReader.childReferencesOf(owner).forEach { onOwned(it.valueObjectId) }
  }
}

/**
 * The node at the top of a window has no parent node, and belongs to the view hosting it — the same rule as
 * a decor view belonging to its `Activity` ([WindowDecorRule]), and what puts a Compose UI's bytes inside
 * the hierarchy of the screen showing it.
 *
 * The composition holds that node too, in a slot of its table, and this is deliberately the owner instead: a
 * composition is one flat store per window, so owning from there would be the registry problem
 * [LayoutNodeParentRule] describes. See [SlotTableReferenceReader] for what a slot reference is.
 */
internal class ComposeUiRootNodeRule : OwnerRule {

  override val ownerClassNames = setOf(ANDROID_COMPOSE_VIEW_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    owner[ANDROID_COMPOSE_VIEW_CLASS_NAME, ROOT_FIELD_NAME]?.value?.asNonNullObjectId?.let(onOwned)
  }

  companion object {
    private const val ANDROID_COMPOSE_VIEW_CLASS_NAME = "androidx.compose.ui.platform.AndroidComposeView"
    private const val ROOT_FIELD_NAME = "root"
  }
}

/**
 * What a node of a Compose UI is made of belongs to that node: its modifiers are a chain hanging off its
 * `NodeChain`, from the outermost through each one's `child` to the tail.
 *
 * Without this the chain is a way *into* the UI rather than a part of it, and that is measurable: Compose's
 * modifier nodes point back at their coordinators, which point back at their layers and at each other, so a
 * single reference into any one of them reaches the lot. A heap dump taken on API 36 has three such
 * references from outside — a focus listener the input method manager reaches through the window's
 * `ViewTreeObserver`, the snapshot observer's static list of what it observes, and the `Recomposer` — so
 * every modifier of every screen was held from a GC root of its own, and the images the UI draws were
 * dominated by the top of the heap rather than by the UI showing them.
 *
 * `child` and not `parent`: a chain has to be owned in one direction, and it reads outermost first, the way
 * the modifiers were written.
 */
internal class ModifierChainRule : OwnerRule {

  override val ownerClassNames = setOf(NODE_CHAIN_CLASS_NAME, MODIFIER_NODE_CLASS_NAME)

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    owner[NODE_CHAIN_CLASS_NAME, HEAD_FIELD_NAME]?.value?.asNonNullObjectId?.let(onOwned)
    owner[MODIFIER_NODE_CLASS_NAME, CHILD_FIELD_NAME]?.value?.asNonNullObjectId?.let(onOwned)
  }

  companion object {
    private const val NODE_CHAIN_CLASS_NAME = "androidx.compose.ui.node.NodeChain"
    private const val MODIFIER_NODE_CLASS_NAME = "androidx.compose.ui.Modifier\$Node"
    private const val HEAD_FIELD_NAME = "head"
    private const val CHILD_FIELD_NAME = "child"
  }
}

/**
 * A dependency injection singleton is held by the provider its component caches it in, and every other
 * reference to one is an injection site the component handed it to. Which is nearly always a lot of them,
 * all over the app, so without this rule a singleton is dominated by whatever dominates the whole graph of
 * things that were injected with it — the top of the tree.
 *
 * The provider and not the component, though the component is what you'd want to read the bytes under,
 * because **nothing in a heap dump says which object is a component**. Dagger's generated one is a
 * `DaggerAppComponent$AppComponentImpl`, which a name could just about catch; Metro's is an `AppGraph$Impl`,
 * an `Impl` nested in the interface the app declared, with no marker of any kind. A rule about the provider
 * needs no such guess, and the component still collects the bytes, one step further down: it is what holds
 * every provider.
 *
 * Self-clearing in the way the rest of these are. A provider never lets go of its instance, so there is no
 * state to check: while the component is reachable so is the provider, and once the component is gone the
 * provider is gone with it, no owner reaches the singleton, and whatever is still pointing at it is both how
 * it's held and what's leaking it.
 *
 * `notes/dependency-injection.md` records where every name below was read off a heap dump of an app built
 * with each framework, and how to take another dump.
 */
internal class ScopedProviderRule(private val graph: HeapGraph) : OwnerRule {

  /**
   * One dependency injection framework's scoped provider: the object a generated component keeps a
   * binding's instance in, so that every injection point of that scope is handed the same one.
   *
   * Both frameworks null the provider's own `provider` field once it has produced the instance, so a live
   * provider points at nothing but its singleton.
   */
  private class ScopedProvider(
    /**
     * The class declaring the field below, so that naming Metro's `BaseDoubleCheck` covers the
     * `DoubleCheck` its generated code really instantiates.
     */
    val className: String,
    /** The field the provider caches the instance in, once something has asked it for one. */
    val instanceFieldName: String,
    /**
     * What [instanceFieldName] points at until something asks: one sentinel object the framework shares
     * between every provider in the process, read out of the static field holding it.
     *
     * Skipped rather than ignored, because it is a real object that real fields point at. Counting it as
     * owned would hand a bare `Object` to whichever provider hadn't been asked yet and take the static
     * field that does hold it out of the tree.
     */
    val uninitializedObjectId: Long
  )

  /**
   * Only the frameworks the heap dump has, resolved once: [HeapGraph.findClassByName] scans every string of
   * the dump, and reading a field off an instance of a class that isn't in its hierarchy answers null, so a
   * dump built with neither framework pays these lookups and nothing else.
   */
  private val scopedProviders: List<ScopedProvider> by lazy {
    listOfNotNull(
      // Dagger keeps the sentinel on the provider class itself.
      scopedProviderOrNull(
        className = "dagger.internal.DoubleCheck",
        instanceFieldName = "instance",
        uninitializedHolderClassName = "dagger.internal.DoubleCheck"
      ),
      // Metro's is a top level property of the file declaring the class, so it lives on that file's facade
      // class rather than on the provider.
      scopedProviderOrNull(
        className = "dev.zacsweers.metro.internal.BaseDoubleCheck",
        instanceFieldName = "_value",
        uninitializedHolderClassName = "dev.zacsweers.metro.internal.BaseDoubleCheckKt"
      )
    )
  }

  override val ownerClassNames = setOf(
    "dagger.internal.DoubleCheck",
    "dev.zacsweers.metro.internal.BaseDoubleCheck"
  )

  override fun forEachOwnedObject(
    owner: HeapInstance,
    onOwned: (Long) -> Unit
  ) {
    scopedProviders.forEach { provider ->
      val heldObjectId = owner[provider.className, provider.instanceFieldName]
        ?.value
        ?.asNonNullObjectId
      if (heldObjectId != null && heldObjectId != provider.uninitializedObjectId) {
        onOwned(heldObjectId)
      }
    }
  }

  private fun scopedProviderOrNull(
    className: String,
    instanceFieldName: String,
    uninitializedHolderClassName: String
  ): ScopedProvider? {
    if (graph.findClassByName(className) == null) {
      return null
    }
    return ScopedProvider(
      className = className,
      instanceFieldName = instanceFieldName,
      uninitializedObjectId = graph.findClassByName(uninitializedHolderClassName)
        ?.get(UNINITIALIZED_FIELD_NAME)
        ?.value
        ?.asNonNullObjectId
        ?: ValueHolder.NULL_REFERENCE
    )
  }

  companion object {
    private const val UNINITIALIZED_FIELD_NAME = "UNINITIALIZED"
  }
}
