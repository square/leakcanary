package shark.explorer

import shark.GcRoot
import shark.GcRoot.Debugger
import shark.GcRoot.Finalizing
import shark.GcRoot.InternedString
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.ReferenceCleanup
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.GcRoot.Unknown
import shark.GcRoot.Unreachable
import shark.GcRoot.VmInternal
import shark.HeapField
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HeapValue
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.ObjectDominators.DominatorNode
import shark.ObjectReporter
import shark.AndroidObjectInspectors
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.Companion.NULL_REFERENCE
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder

/**
 * A heap dump's dominator tree, seen as a [TreemapTree] weighted by retained size.
 *
 * Nodes are object ids, and the root is [NULL_REFERENCE]: the virtual root [shark.HeapDominatorTree]
 * puts above every GC root, so that the whole reachable heap is one rectangle.
 *
 * Which objects are in it depends on [followedStrengths] — see [HeapExplorer.treeFor]. With none of
 * them, an object only a weak reference points at is absent, because a weak reference retains nothing,
 * so the root doesn't add up to the size of the heap dump. [HeapSizes] is where the rest of the bytes
 * are accounted for.
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
class HeapDominatorTreemap internal constructor(
  private val graph: HeapGraph,
  private val reachability: HeapReachability,
  private val strengthReader: ReferenceStrengthReader,
  private val nodes: Map<Long, DominatorNode>,
  /** The reference strengths this tree was built by following. */
  val followedStrengths: Set<ReachabilityStrength>
) : TreemapTree<Long> {

  override val root: Long get() = NULL_REFERENCE

  /** Bytes retained by [node]: its own shallow size plus that of everything it dominates. */
  override fun weight(node: Long): Long = nodes.getValue(node).retainedSize.toLong()

  override fun children(node: Long): List<Long> = nodes.getValue(node).dominatedObjectIds

  /** Whether [objectId] is in this tree, i.e. reachable by following [followedStrengths]. */
  operator fun contains(objectId: Long): Boolean = objectId in nodes

  /** How strongly the garbage collector holds on to [objectId]. */
  fun strengthOf(objectId: Long): ReachabilityStrength = if (objectId == root) {
    ReachabilityStrength.STRONG
  } else {
    reachability.strengthOf(objectId)
  }

  /**
   * A short name for [objectId], to draw on its rectangle.
   *
   * Cheap enough to call for every visible rectangle, unlike [summarize].
   */
  fun label(objectId: Long): String = if (objectId == root) {
    ROOT_LABEL
  } else {
    when (val heapObject = graph.findObjectById(objectId)) {
      is HeapClass -> "class ${heapObject.simpleName}"
      is HeapInstance -> heapObject.instanceClassSimpleName
      is HeapObjectArray -> heapObject.arrayClassSimpleName
      is HeapPrimitiveArray -> heapObject.arrayClassName
    }
  }

  /**
   * Everything the details panel shows about [objectId].
   *
   * Reads the object and runs Shark's object inspectors over it, so call it for the selected object
   * rather than for every rectangle.
   */
  fun summarize(objectId: Long): HeapObjectSummary {
    val node = nodes.getValue(objectId)
    val heapObject = if (objectId == root) null else graph.findObjectById(objectId)
    val fields = heapObject?.fieldsOf() ?: FieldList(emptyList(), totalCount = 0)
    return HeapObjectSummary(
      objectId = objectId,
      label = label(objectId),
      className = when (heapObject) {
        null -> ROOT_LABEL
        is HeapClass -> heapObject.name
        is HeapInstance -> heapObject.instanceClassName
        is HeapObjectArray -> heapObject.arrayClassName
        is HeapPrimitiveArray -> heapObject.arrayClassName
      },
      headline = heapObject?.headline(),
      strength = strengthOf(objectId),
      shallowSize = node.shallowSize,
      retainedSize = node.retainedSize.toLong(),
      retainedCount = node.retainedCount,
      dominatedObjectCount = node.dominatedObjectIds.size,
      inspectorLabels = if (heapObject == null) {
        emptyList()
      } else {
        val reporter = ObjectReporter(heapObject)
        AndroidObjectInspectors.appDefaults.forEach { it.inspect(reporter) }
        reporter.labels.toList()
      },
      fields = fields.shown,
      hiddenFieldCount = fields.totalCount - fields.shown.size
    )
  }

  /**
   * What holds on to [objectId]: the fields pointing at it, and the GC roots if any point straight at
   * it.
   *
   * This is how an object ends up dominated by nothing but the virtual root: two referrers on paths
   * that only meet at the root mean neither of them alone would free it, so its bytes are attributed to
   * the whole heap rather than to either owner. The dominator tree can't say that on its own, and it's
   * the first thing you want to know about a big rectangle sitting flat under the root.
   *
   * Costs a pass over every object in the heap dump — around a second per 100 MB — because a heap dump
   * only records references in the direction they point. Hence a call of its own rather than part of
   * [summarize]: the panel fills the rest in straight away and this a moment later.
   *
   * Counts every referrer but keeps only the first [MAX_REFERRERS]: something like `Boolean.TRUE` is
   * held from tens of thousands of places, and the count is the useful part of that anyway.
   */
  fun referrersOf(objectId: Long): ObjectReferrers {
    var totalCount = 0
    val referrers = mutableListOf<Referrer>()
    fun add(referrer: () -> Referrer) {
      totalCount++
      if (referrers.size < MAX_REFERRERS) {
        referrers += referrer()
      }
    }
    graph.gcRoots
      .filter { it.id == objectId }
      .forEach { gcRoot ->
        add {
          Referrer(
            label = gcRootLabel(gcRoot),
            fieldName = null,
            inspectableObjectId = null
          )
        }
      }
    graph.objects.forEach { heapObject ->
      strengthReader.retainingReferencesOf(heapObject)
        .filter { it.valueObjectId == objectId }
        .forEach { reference ->
          add { referrer(heapObject, reference.lazyDetailsResolver.resolve().name) }
        }
      strengthReader.weakeningReferencesOf(heapObject)
        .filter { it.valueObjectId == objectId }
        .forEach { weakening -> add { referrer(heapObject, weakening.fieldName) } }
    }
    return ObjectReferrers(
      isDominatedByRoot = objectId != root && objectId in nodes.getValue(root).dominatedObjectIds,
      referrers = referrers,
      hiddenReferrerCount = totalCount - referrers.size
    )
  }

  private fun referrer(
    heapObject: HeapObject,
    fieldName: String
  ) = Referrer(
    label = label(heapObject.objectId),
    fieldName = fieldName,
    inspectableObjectId = heapObject.objectId.takeIf { it in nodes }
  )

  /**
   * What's worth saying about an object before its fields, for the kinds this recognizes. Both cases
   * here are objects whose fields say nothing about their size: a bitmap keeps its pixels in native
   * memory, and a string's characters are folded into it by the size calculator.
   */
  private fun HeapObject.headline(): String? = when (this) {
    is HeapInstance -> when {
      instanceOf("java.lang.String") -> readAsJavaString()?.let { "\"$it\"" }
      instanceOf("android.graphics.Bitmap") -> bitmapHeadline()
      instanceOf("java.lang.Thread") -> readStringField("java.lang.Thread", "name")
        ?.let { "thread \"$it\"" }
      else -> null
    }
    is HeapObjectArray -> "${readRecord().elementIds.size} elements"
    is HeapPrimitiveArray -> "$recordSize bytes"
    is HeapClass -> null
  }

  private fun HeapInstance.bitmapHeadline(): String {
    val width = this[BITMAP_CLASS_NAME, "mWidth"]?.value?.asInt
    val height = this[BITMAP_CLASS_NAME, "mHeight"]?.value?.asInt
    val recycled = this[BITMAP_CLASS_NAME, "mRecycled"]?.value?.asBoolean == true
    return "$width × $height pixels" + if (recycled) ", recycled" else ""
  }

  private fun HeapInstance.readStringField(
    declaringClassName: String,
    fieldName: String
  ): String? = this[declaringClassName, fieldName]?.value?.readAsJavaString()

  /**
   * Every field of an object, with object valued ones inspectable so that the panel can walk the graph
   * the way the heap dump records it, rather than only the way the dominator tree summarizes it.
   *
   * An array's elements are fields here too, and an array can hold millions of them, so this counts
   * them all and reads only the first [MAX_FIELDS].
   */
  private fun HeapObject.fieldsOf(): FieldList = when (this) {
    is HeapInstance -> readFields().filterNot { it.isRuntimeInternal }.toList().let { fields ->
      FieldList(fields.take(MAX_FIELDS).map { it.asFieldValue(it.declaringClass.simpleName) }, fields.size)
    }
    is HeapClass -> readStaticFields().filterNot { it.isRuntimeInternal }.toList().let { fields ->
      FieldList(fields.take(MAX_FIELDS).map { it.asFieldValue(simpleName) }, fields.size)
    }
    is HeapObjectArray -> readRecord().elementIds.let { elementIds ->
      FieldList(
        elementIds.take(MAX_FIELDS).mapIndexed { index, elementId ->
          ObjectFieldValue(
            name = "[$index]",
            declaringClassName = null,
            value = if (elementId == NULL_REFERENCE) NULL_VALUE else render(elementId),
            inspectableObjectId = elementId.takeIf { it in nodes }
          )
        },
        elementIds.size
      )
    }
    is HeapPrimitiveArray -> readRecord().let { record ->
      FieldList(
        (0 until minOf(record.size, MAX_FIELDS)).map { index ->
          ObjectFieldValue(
            name = "[$index]",
            declaringClassName = null,
            value = record.elementAt(index),
            inspectableObjectId = null
          )
        },
        record.size
      )
    }
  }

  private fun PrimitiveArrayDumpRecord.elementAt(index: Int): String = when (this) {
    is BooleanArrayDump -> array[index].toString()
    is CharArrayDump -> "'${array[index]}'"
    is FloatArrayDump -> array[index].toString()
    is DoubleArrayDump -> array[index].toString()
    is ByteArrayDump -> array[index].toString()
    is ShortArrayDump -> array[index].toString()
    is IntArrayDump -> array[index].toString()
    is LongArrayDump -> array[index].toString()
  }

  private fun HeapField.asFieldValue(declaringClassName: String) = ObjectFieldValue(
    name = name,
    declaringClassName = declaringClassName,
    value = render(value),
    inspectableObjectId = value.asNonNullObjectId?.takeIf { it in nodes }
  )

  /** A reference reads as what it points at, so that `Thread.name` says `"main"` and not an address. */
  private fun render(value: HeapValue): String = when (val holder = value.holder) {
    is ReferenceHolder -> if (holder.isNull) NULL_VALUE else render(holder.value)
    is BooleanHolder -> holder.value.toString()
    is CharHolder -> "'${holder.value}'"
    is FloatHolder -> holder.value.toString()
    is DoubleHolder -> holder.value.toString()
    is ByteHolder -> holder.value.toString()
    is ShortHolder -> holder.value.toString()
    is IntHolder -> holder.value.toString()
    is LongHolder -> holder.value.toString()
  }

  private fun render(objectId: Long): String {
    val target = graph.findObjectByIdOrNull(objectId) ?: return UNKNOWN_VALUE
    return (target as? HeapInstance)?.readAsJavaString()?.let { "\"$it\"" } ?: label(objectId)
  }

  /**
   * Lays this tree out into [viewport] rooted at [root], and reads a label and a strength for every
   * rectangle: everything the UI needs to draw a treemap without touching the heap dump itself.
   */
  fun present(
    layout: TreemapLayout<Long>,
    viewport: TreemapRect,
    root: Long = this.root
  ): TreemapPresentation {
    val result = layout.layout(this, viewport, root)
    return TreemapPresentation(layout = result, cells = result.cells.map { it.presented() })
  }

  /** The same, laid out as rings around a centre rather than as rectangles. */
  fun presentRadial(
    layout: RadialLayout<Long>,
    viewport: TreemapRect,
    root: Long = this.root
  ): RadialPresentation {
    val result = layout.layout(this, viewport, root)
    return RadialPresentation(layout = result, cells = result.cells.map { it.presented() })
  }

  private fun <C : LayoutCell<Long>> C.presented(): PresentedCell<C> = when (val subject = subject) {
    is CellSubject.Node -> PresentedCell(
      cell = this,
      label = label(subject.node),
      strength = strengthOf(subject.node)
    )
    is CellSubject.Group -> PresentedCell(
      cell = this,
      label = "${subject.nodeCount} smaller objects",
      strength = null
    )
  }

  /** The fields of one object: what's shown, and how many there are in total. */
  private class FieldList(
    val shown: List<ObjectFieldValue>,
    val totalCount: Int
  )

  companion object {
    /**
     * The object id of the virtual root, which every dominator tree of a heap dump has, so the UI can
     * root its navigation there before it has a tree to ask.
     */
    const val ROOT_OBJECT_ID = NULL_REFERENCE

    /** What the virtual root above every GC root is called in the UI. */
    const val ROOT_LABEL = "All GC roots"

    private const val BITMAP_CLASS_NAME = "android.graphics.Bitmap"
    private const val NULL_VALUE = "null"
    private const val UNKNOWN_VALUE = "object not in the heap dump"

    /** An array can hold millions of elements, and no panel is going to show them. */
    private const val MAX_FIELDS = 500

    /** Same, for the objects holding a widely shared one. */
    private const val MAX_REFERRERS = 100

    /**
     * ART gives every object a `shadow$_klass_` and a `shadow$_monitor_`: the class pointer and the
     * lock word. They're the runtime's business, and they're on every object in the list otherwise.
     */
    private val HeapField.isRuntimeInternal: Boolean get() = name.startsWith("shadow\$_")

    private fun gcRootLabel(gcRoot: GcRoot): String = "GC root: " + when (gcRoot) {
      is JniGlobal -> "JNI global reference"
      is JniLocal -> "JNI local reference"
      is JniMonitor -> "JNI monitor"
      is JavaFrame -> "local variable of a running method"
      is NativeStack -> "native stack"
      is StickyClass -> "loaded class"
      is ThreadBlock -> "thread block"
      is MonitorUsed -> "monitor in use"
      is ThreadObject -> "running thread"
      is ReferenceCleanup -> "reference cleanup"
      is VmInternal -> "runtime internal"
      is InternedString -> "interned string"
      is Finalizing -> "being finalized"
      is Debugger -> "held by the debugger"
      is Unreachable -> "unreachable"
      is Unknown -> "kind not recorded"
    }
  }
}

/** What the UI knows about one heap object. See [HeapDominatorTreemap.summarize]. */
data class HeapObjectSummary(
  val objectId: Long,
  /** Short name, as drawn on a rectangle. */
  val label: String,
  /** Fully qualified class name, or array type. */
  val className: String,
  /**
   * What this kind of object is worth saying before anything else — a string's content, a bitmap's
   * dimensions — for the kinds the explorer recognizes, null for the rest.
   */
  val headline: String?,
  val strength: ReachabilityStrength,
  val shallowSize: Int,
  val retainedSize: Long,
  /** Number of objects retained, including this one. */
  val retainedCount: Int,
  /** Number of objects immediately dominated by this one, ie its children in the treemap. */
  val dominatedObjectCount: Int,
  /** What Shark's object inspectors have to say, e.g. that an activity is destroyed. */
  val inspectorLabels: List<String>,
  /** Its fields, or an array's elements, in the order the heap dump records them. */
  val fields: List<ObjectFieldValue>,
  /** How many more fields there are than [fields] holds, which only an array reaches. */
  val hiddenFieldCount: Int
)

/** One field of an object, or one element of an array. See [HeapObjectSummary.fields]. */
data class ObjectFieldValue(
  /** The field's name, or `[3]` for the fourth element of an array. */
  val name: String,
  /** Which class along the hierarchy declares the field, null for an array element. */
  val declaringClassName: String?,
  /** The value, rendered: a number, `null`, a string's content, or what class the object is. */
  val value: String,
  /** The object the field points at, when it's in the tree and can therefore be inspected. */
  val inspectableObjectId: Long?
)

/** What holds on to an object. See [HeapDominatorTreemap.referrersOf]. */
data class ObjectReferrers(
  /**
   * Whether nothing but the virtual root dominates the object, which is what makes its referrers worth
   * showing: with more than one of them on paths that meet only at the root, no single owner would free
   * it, so the dominator tree attributes its bytes to the whole heap.
   */
  val isDominatedByRoot: Boolean,
  val referrers: List<Referrer>,
  /** How many referrers there are beyond the ones in [referrers]. */
  val hiddenReferrerCount: Int
) {
  /** How many objects hold this one, including the ones [referrers] left out. */
  val referrerCount: Int get() = referrers.size + hiddenReferrerCount
}

/** One reference pointing at an object. See [ObjectReferrers]. */
data class Referrer(
  /** The referring object, or which kind of GC root this is. */
  val label: String,
  /** The field holding the reference, null for a GC root. */
  val fieldName: String?,
  /** The referring object, when it's in the tree and can therefore be inspected. */
  val inspectableObjectId: Long?
)
