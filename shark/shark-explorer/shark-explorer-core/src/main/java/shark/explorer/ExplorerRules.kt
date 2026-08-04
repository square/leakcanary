package shark.explorer

import shark.ReferenceMatcher
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField
import shark.ignored
import shark.explorer.ReachabilityStrength.CACHE
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.THREAD_LOCAL
import shark.explorer.ReachabilityStrength.WEAK

/**
 * Fields that hold their value without retaining it, and how firmly the value is held instead.
 *
 * Read against the whole class hierarchy of the referring object, so a rule on a base class covers every
 * subclass and a subclass's rule for the same field name wins — which is how a `FinalizerReference` reads
 * as a finalizer reference rather than as the `PhantomReference` it extends.
 */
internal class WeakeningFieldRule(
  val className: String,
  val fieldNames: Set<String>,
  /** Why this field doesn't retain, for the reader of this list. */
  val description: String,
  val strength: ReachabilityStrength
)

/**
 * Everything the explorer knows about a heap dump that the heap dump doesn't say itself: which references
 * hold their target without retaining it, and which reference is the one way an object is held.
 *
 * All of it curated, all of it a claim about a class the explorer doesn't own, and all of it here rather
 * than spread over the classes applying it — because a rule is only as good as the reader who can see the
 * whole list and say whether it's still true.
 *
 * Two properties every entry has to have, and the reason a wrong one is worse than a missing one:
 *
 * - **Names are matched literally**, so a wrong class or field name doesn't fail, it silently does nothing.
 *   Add one only against a heap dump that has it. An obfuscated dump needs its mapping applied first, which
 *   is why none of the app level entries fire on a production dump as it comes off a device.
 * - **A wrong entry moves bytes rather than breaking**, which reads as an answer instead of as an error. The
 *   tree still holds every object either way — see [OwnerReferences] and [HeapReachability.isHeldThrough] —
 *   so what's at stake is where the reader is told to look, and that's the whole product.
 */
internal class ExplorerRules(
  val weakeningFields: List<WeakeningFieldRule>,
  val ownerRules: List<OwnerRule>
) {

  /** [weakeningFields] as [ReferenceStrengthReader] reads it: by class name, then by field name. */
  val strengthByFieldNameByClassName: Map<String, Map<String, ReachabilityStrength>> =
    weakeningFields.groupBy { it.className }
      .mapValues { (_, rules) ->
        rules.flatMap { rule -> rule.fieldNames.map { it to rule.strength } }.toMap()
      }

  /**
   * The matchers that stop the retaining reader from following a reference that doesn't retain: one per
   * field of [weakeningFields] and nothing else, derived from the same list that gives those fields their
   * strength — so the two halves of [ReferenceStrengthReader] can't disagree about a reference, and one
   * edit covers both.
   *
   * Deliberately **not** [shark.JdkReferenceMatchers.REFERENCES], which is where LeakCanary keeps the same
   * `java.lang.ref` fields. That list says what a leak trace shouldn't route through and never says why, so
   * inheriting it left the strengths declared here and the ignoring declared there with nothing tying the
   * two together — and it carries fields that are no weakening reference at all, the `prev`, `next` and
   * `element` links of the lists a runtime keeps its finalizer and cleaner references on. Ignoring those
   * cost the explorer every object waiting to be finalized; see `notes/dominator-tree.md`.
   */
  val weakeningReferenceMatchers: List<ReferenceMatcher> =
    weakeningFields.flatMap { rule ->
      rule.fieldNames.map { instanceField(rule.className, it).ignored(patternApplies = ALWAYS) }
    }

  companion object {

    private const val VIEW_CLASS_NAME = "android.view.View"

    private const val ACTIVITY_CLASS_NAME = "android.app.Activity"

    /** The fields a `java.lang.ref.Reference` holds its referent in. */
    private const val REFERENT_FIELD_NAME = "referent"

    /**
     * What the `java.lang.ref` classes hold their referent with, most derived first for the reader — the
     * hierarchy walk in [ReferenceStrengthReader] is what actually resolves an override.
     *
     * `FinalizerReference` is Android's, `FinalReference` is the JVM's — `java.lang.ref.Finalizer` extends
     * it, and it's package private so nothing else can. `zombie` is Android's alone: it's where a
     * `FinalizerReference` moves its referent while `finalize()` runs.
     *
     * `leakcanary.KeyedWeakReference` needs no entry of its own even though LeakCanary's list has one: it
     * extends `WeakReference`, and a rule on a base class covers every subclass.
     */
    private val REFERENCE_WEAKENING_FIELDS = listOf(
      WeakeningFieldRule(
        className = "java.lang.ref.FinalizerReference",
        fieldNames = setOf(REFERENT_FIELD_NAME, "zombie"),
        description = "Android's finalizer queue, holding an object until finalize() has run",
        strength = FINALIZER
      ),
      WeakeningFieldRule(
        className = "java.lang.ref.FinalReference",
        fieldNames = setOf(REFERENT_FIELD_NAME),
        description = "The JVM's finalizer queue, which java.lang.ref.Finalizer extends",
        strength = FINALIZER
      ),
      WeakeningFieldRule(
        className = "java.lang.ref.PhantomReference",
        fieldNames = setOf(REFERENT_FIELD_NAME),
        description = "Enqueued once the referent is gone, and unreachable to the program before that",
        strength = PHANTOM
      ),
      WeakeningFieldRule(
        className = "java.lang.ref.WeakReference",
        fieldNames = setOf(REFERENT_FIELD_NAME),
        description = "Cleared at the next collection, whether or not memory is short",
        strength = WEAK
      ),
      WeakeningFieldRule(
        className = "java.lang.ref.SoftReference",
        fieldNames = setOf(REFERENT_FIELD_NAME),
        description = "Cleared when the VM wants the memory back",
        strength = SOFT
      )
    )

    /**
     * The caches the explorer recognizes, whose entries read as [CACHE] rather than as retained, so that an
     * object a cache and something else both hold is attributed to the something else. See [CACHE] for why
     * this is a list rather than something read off the heap dump.
     *
     * An entry belongs here once two things are true of the class: it is a cache that evicts on its own, and
     * its entries are worth blaming their owner for rather than it.
     *
     * Cutting as low as the value a cache entry wraps, rather than at the cache itself, is what keeps the
     * cache's own bookkeeping — the map, the entries, the sizes — where it belongs: strongly held by the
     * cache, and its bytes attributed to it.
     */
    private val CACHE_WEAKENING_FIELDS = listOf(
      WeakeningFieldRule(
        className = "coil3.memory.RealStrongMemoryCache\$InternalValue",
        fieldNames = setOf("image"),
        description = "Coil 3's memory cache: a bounded LRU of decoded images, halved on " +
          "TRIM_MEMORY_RUNNING_LOW and cleared on TRIM_MEMORY_BACKGROUND by AndroidSystemCallbacks",
        strength = CACHE
      )
    )

    /**
     * Where a thread keeps what it put in a `ThreadLocal`, held for as long as the thread lives.
     *
     * `ThreadLocalMap.Entry` is a `WeakReference` to the `ThreadLocal` itself, so its `referent` is already
     * weakened by [REFERENCE_WEAKENING_FIELDS]; what's added here is the value, which the map holds
     * strongly. Attributing it to the thread is what the tree does otherwise, and a thread of an app's pool
     * retaining everything anything ever left in a thread local of it is a picture of the pool rather than
     * of the app.
     */
    private val THREAD_LOCAL_WEAKENING_FIELDS = listOf(
      WeakeningFieldRule(
        className = "java.lang.ThreadLocal\$ThreadLocalMap\$Entry",
        fieldNames = setOf("value"),
        description = "A thread's own storage, given up when the thread dies",
        strength = THREAD_LOCAL
      )
    )

    /**
     * The constructs where one reference is the one that really holds an object. Each one is a claim that a
     * reference is *the* way an object is held, and getting that wrong moves bytes to the wrong place in the
     * tree — so one owner per construct, and it should be the one you'd want to read the bytes under.
     */
    private val OWNER_RULES = listOf(
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
      // hierarchy.
      OwnerRule(
        ownedClassName = VIEW_CLASS_NAME,
        ownerFieldsByClassName = mapOf(
          ACTIVITY_CLASS_NAME to setOf("mDecor"),
          "android.app.Dialog" to setOf("mDecor")
        )
      ),
      // An activity is held by the list of activities the process is running, through the virtual
      // reference [ActivityThreadReferenceReader] reads from the ActivityThread to each of them.
      // Everything else that points at one — a context wrapper, a view, a fragment, a presenter, a
      // callback — is something the activity brought along.
      //
      // Not ActivityThread$ActivityClientRecord.activity, which is the same construct named one level
      // lower and was this rule's first form: the record is an implementation detail of how the framework
      // runs an activity, so a tree built on it draws every screen of an app under a different unnamed
      // record instead of side by side under the one thread that runs them.
      //
      // Self-clearing either way: ActivityThread.handleDestroyActivity takes the record out of
      // mActivities, so a destroyed activity has no owner left and falls back on whatever is leaking it,
      // which is exactly what you want its bytes drawn under.
      OwnerRule(
        ownedClassName = ACTIVITY_CLASS_NAME,
        ownerVirtualClassNames = setOf(ActivityThreadReferenceReader.ACTIVITY_THREAD_CLASS_NAME)
      )
    )

    /** What the explorer applies unless something says otherwise, which today nothing does. */
    val DEFAULT = ExplorerRules(
      weakeningFields = REFERENCE_WEAKENING_FIELDS + CACHE_WEAKENING_FIELDS +
        THREAD_LOCAL_WEAKENING_FIELDS,
      ownerRules = OWNER_RULES
    )
  }
}
