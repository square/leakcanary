package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK

class HeapExplorerTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the root is the whole reachable heap`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val rootChildren = tree.children(tree.root)

      assertThat(rootChildren).isNotEmpty()
      // The virtual root has no shallow size of its own, so it weighs exactly what it dominates.
      assertThat(tree.weight(tree.root)).isEqualTo(rootChildren.sumOf { tree.weight(it) })
    }
  }

  @Test fun `children are ordered largest retained first`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val weights = tree.children(tree.root).map { tree.weight(it) }

      assertThat(weights).isEqualTo(weights.sortedDescending())
    }
  }

  @Test fun `the root is labelled rather than read from the heap`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())

      assertThat(tree.label(tree.root)).isEqualTo(HeapDominatorTreemap.ROOT_LABEL)
      assertThat(tree.summarize(tree.root).inspectorLabels).isEmpty()
    }
  }

  @Test fun `an instance is labelled with its simple class name`() {
    openTestHeapDump().use { explorer ->
      val holder = explorer.treeFor(emptySet()).findByLabel("Holder")

      assertThat(holder.className).isEqualTo("com.example.Holder")
    }
  }

  @Test fun `an object retains what it dominates`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val holder = tree.findByLabel("Holder")
      val dominated = tree.children(holder.objectId).map { tree.summarize(it) }

      // The holder is the only path to its array and its name, so it retains both. A string's
      // char array is not a node of its own: Shark folds its size into the string instead.
      assertThat(dominated.map { it.label }).containsExactly("Object[]", "String")
      assertThat(holder.retainedCount).isEqualTo(3)
      assertThat(holder.retainedSize)
        .isEqualTo(holder.shallowSize + dominated.sumOf { it.retainedSize })
    }
  }

  @Test fun `a string leads with its content`() {
    openTestHeapDump().use { explorer ->
      val string = explorer.treeFor(emptySet()).findByLabel("String")

      assertThat(string.headline).isEqualTo("\"Kept alive by the holder\"")
    }
  }

  @Test fun `a bitmap leads with its dimensions`() {
    HeapExplorer.open(bitmapHeapDump()).use { explorer ->
      val bitmap = explorer.treeFor(emptySet()).findByLabel("Bitmap")

      assertThat(bitmap.headline).isEqualTo("420 × 467 pixels")
    }
  }

  @Test fun `an object lists its fields, references reading as what they point at`() {
    openTestHeapDump().use { explorer ->
      val holder = explorer.treeFor(emptySet()).findByLabel("Holder")

      assertThat(holder.fields.map { "${it.name} = ${it.value}" })
        .containsExactly("payload = Object[]", "name = \"Kept alive by the holder\"")
      assertThat(holder.fields.map { it.declaringClassName }).containsOnly("Holder")
      // Both point at objects in the tree, so the panel can walk to them.
      assertThat(holder.fields.map { it.inspectableObjectId }).doesNotContainNull()
    }
  }

  @Test fun `an array lists its elements, and says how many it left out`() {
    HeapExplorer.open(weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(setOf(WEAK))
      val array = tree.findByLabel("Object[]")

      assertThat(array.headline).isEqualTo("$PAYLOAD_ELEMENT_COUNT elements")
      assertThat(array.fields).hasSize(MAX_FIELDS_SHOWN)
      assertThat(array.hiddenFieldCount).isEqualTo(PAYLOAD_ELEMENT_COUNT - MAX_FIELDS_SHOWN)
      assertThat(array.fields.first().name).isEqualTo("[0]")
    }
  }

  @Test fun `an object two others hold is dominated by the root, which its referrers explain`() {
    HeapExplorer.open(sharedPayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val payload = tree.findByLabel("Object[]")

      // Neither holder alone would free the payload, so the dominator tree can only attribute it to
      // the whole heap. That is what makes a big rectangle sit flat under the root, and the only way
      // to find out why is to ask what points at it.
      assertThat(tree.children(tree.root)).contains(payload.objectId)
      val referrers = tree.referrersOf(payload.objectId)
      assertThat(referrers.isDominatedByRoot).isTrue()
      assertThat(referrers.referrers.map { "${it.label}.${it.fieldName}" })
        .containsExactlyInAnyOrder("Holder.payload", "OtherHolder.payload")
    }
  }

  @Test fun `an object held two ways has a path from a gc root for each of them`() {
    HeapExplorer.open(cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val payload = tree.findByLabel("Object[]")

      val holdingPaths = tree.holdingPathsTo(payload.objectId)

      // Held by the tile that shows it, twice over, and by the cache: three ways, and the walk up has
      // to fork twice to find the third, since the cache holds the wrapper rather than the payload.
      assertThat(holdingPaths.paths.map { path -> path.steps.map { it.label } }).containsExactlyInAnyOrder(
        listOf("Cache", "Wrapper", "Object[]"),
        listOf("Tile", "Wrapper", "Object[]"),
        listOf("Tile", "View", "Object[]")
      )
      assertThat(holdingPaths.paths.map { path -> path.steps.map { it.referenceName } })
        .containsExactlyInAnyOrder(
          listOf(null, "entry", "payload"),
          listOf(null, "result", "payload"),
          listOf(null, "view", "drawable")
        )
      assertThat(holdingPaths.paths.map { it.gcRootLabel }.distinct())
        .containsExactly("GC root: JNI global reference")
    }
  }

  @Test fun `the paths say where they join, and that they don't all join`() {
    HeapExplorer.open(cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val holdingPaths = tree.holdingPathsTo(tree.findByLabel("Object[]").objectId)

      // The tile is on two of the three paths, so nothing is common to all of them — which is exactly
      // why the root ends up dominating the payload.
      assertThat(holdingPaths.commonHolderObjectId).isNull()
      assertThat(holdingPaths.commonHolderLabel).isNull()
      val pathCountByLabel = holdingPaths.paths
        .flatMap { path -> path.steps }
        .associate { it.label to it.pathCount }
      assertThat(pathCountByLabel).containsEntry("Tile", 2)
      assertThat(pathCountByLabel).containsEntry("Wrapper", 2)
      assertThat(pathCountByLabel).containsEntry("Cache", 1)
      assertThat(pathCountByLabel).containsEntry("Object[]", 3)
    }
  }

  @Test fun `an object with an owner has the one path that goes through it`() {
    HeapExplorer.open(cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val view = tree.findByLabel("View")

      val holdingPaths = tree.holdingPathsTo(view.objectId)

      // Everything that holds the view goes through the tile, which the dominator tree already said, so
      // there is nothing to fork on and one path is the whole story.
      assertThat(holdingPaths.paths.map { path -> path.steps.map { it.label } })
        .containsExactly(listOf("Tile", "View"))
      assertThat(holdingPaths.commonHolderLabel).isEqualTo("Tile")
      assertThat(holdingPaths.hiddenPathCount).isEqualTo(0)
    }
  }

  @Test fun `the root has no path leading to it`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())

      val holdingPaths = tree.holdingPathsTo(tree.root)

      assertThat(holdingPaths.paths).isEmpty()
      assertThat(holdingPaths.commonHolderObjectId).isNull()
    }
  }

  @Test fun `a path that leads back to the object is not a way of holding it`() {
    HeapExplorer.open(cyclicHolderHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val view = tree.findByLabel("View")

      val holdingPaths = tree.holdingPathsTo(view.objectId)

      // The view's own helper points back at it, so one of its two referrers is only reachable through
      // the view itself. Following it would report the view as holding itself.
      assertThat(tree.referrersOf(view.objectId).referrers.map { it.label })
        .containsExactlyInAnyOrder("Tile", "Helper")
      assertThat(holdingPaths.paths.map { path -> path.steps.map { it.label } })
        .containsExactly(listOf("Tile", "View"))
    }
  }

  @Test fun `a gc root is one of the referrers`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val holder = tree.findByLabel("Holder")

      val referrers = tree.referrersOf(holder.objectId)

      assertThat(referrers.referrers.map { it.label }).containsExactly("GC root: JNI global reference")
      assertThat(referrers.referrers.single().fieldName).isNull()
    }
  }

  @Test fun `progress is reported for each step`() {
    val steps = mutableListOf<String>()

    openTestHeapDump(onProgress = { steps += it }).use { explorer ->
      explorer.treeFor(emptySet(), onProgress = { steps += it })
    }

    assertThat(steps).hasSize(3)
    assertThat(steps).allSatisfy { assertThat(it).isNotEmpty() }
  }

  @Test fun `opening a file that is not a heap dump fails`() {
    val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }

    assertThatThrownBy { HeapExplorer.open(notAHeapDump) }
      .isInstanceOf(Exception::class.java)
  }

  @Test fun `an object only reachable through a weak reference is not retained`() {
    HeapExplorer.open(weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val labels = tree.allSummaries().map { it.label }

      assertThat(labels).contains("WeakReference")
      // 1024 ids at 4 bytes each: if a weak reference counted as retaining its referent, this array
      // would be the biggest thing in the treemap. It isn't in it at all.
      assertThat(labels).doesNotContain("Object[]")
      assertThat(tree.weight(tree.root)).isLessThan(PAYLOAD_BYTE_SIZE)
    }
  }

  @Test fun `following weak references nests the referent inside the weak reference`() {
    HeapExplorer.open(weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(setOf(WEAK))
      val weakReference = tree.findByLabel("WeakReference")
      val dominated = tree.children(weakReference.objectId).map { tree.summarize(it) }

      // What makes a weakly reachable rectangle show up inside a strongly reachable one: the weak
      // reference itself is strongly reachable, and it dominates a referent that isn't.
      assertThat(weakReference.strength).isEqualTo(STRONG)
      assertThat(dominated.map { it.label }).containsExactly("Object[]")
      assertThat(dominated.single().strength).isEqualTo(WEAK)
      assertThat(tree.weight(tree.root)).isGreaterThan(PAYLOAD_BYTE_SIZE)
    }
  }

  @Test fun `following weak references leaves a referent that is strongly reachable too where it was`() {
    HeapExplorer.open(stronglyAndWeaklyReachablePayloadHeapDump()).use { explorer ->
      val strongly = explorer.treeFor(emptySet())
      val stronglyDominated = strongly.dominatedLabels()
      val stronglyRetained = strongly.weight(strongly.root)

      val weakly = explorer.treeFor(setOf(WEAK))

      // The payload is strongly reachable through the holder, so the weak reference has nothing to
      // reveal. Following it anyway would make both a path to the payload, moving its bytes up to
      // their common ancestor and reshuffling the treemap for no reason.
      assertThat(weakly.dominatedLabels()).isEqualTo(stronglyDominated)
      assertThat(weakly.weight(weakly.root)).isEqualTo(stronglyRetained)
      assertThat(weakly.findByLabel("Object[]").strength).isEqualTo(STRONG)
    }
  }

  @Test fun `a root with few children keeps them as they are`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())

      assertThat(tree.children(tree.root)).allSatisfy { child ->
        assertThat(tree.classGroupOrNull(child)).isNull()
      }
    }
  }

  @Test fun `a root with too many children to draw gathers them by class`() {
    HeapExplorer.open(crowdedRootHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val children = tree.children(tree.root)
      val groups = children.mapNotNull { tree.classGroupOrNull(it) }

      // One cell for the tiles, and the class with a single instance under the root left as that
      // instance: a group of one would say nothing and add a level to click through.
      assertThat(groups.map { it.className }).containsExactly(TILE_CLASS_NAME)
      assertThat(groups.single().instanceCount).isEqualTo(TILE_COUNT)
      assertThat(children.map { tree.label(it) }).contains("Solo")
      assertThat(children.map { tree.label(it) }).doesNotContain("Tile")
      // The loaded classes are root children too, and this dump has no `java.lang.Class` for them to
      // gather under, so they're left alone rather than forced into a group.
      assertThat(children.map { tree.label(it) }).contains("class Tile")
    }
  }

  @Test fun `the loaded classes gather under java lang Class`() {
    HeapExplorer.open(crowdedRootHeapDump(withJavaLangClass = true)).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val groups = tree.children(tree.root).mapNotNull { tree.classGroupOrNull(it) }

      // What this is for: a real dump has thousands of loaded classes directly under the root — 9,502 in
      // the production dump this was measured on — and as one cell they stop drowning everything else.
      val classGroup = groups.single { it.className == "java.lang.Class" }
      assertThat(classGroup.instanceCount).isGreaterThan(TILE_COUNT)
      assertThat(tree.children(tree.root).map { tree.label(it) }).doesNotContain("class Tile")
    }
  }

  @Test fun `a class group weighs and holds what its instances do`() {
    HeapExplorer.open(crowdedRootHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val group = tree.children(tree.root).single { tree.classGroupOrNull(it) != null }
      val instances = tree.children(group)

      assertThat(instances).hasSize(TILE_COUNT)
      assertThat(instances.map { tree.label(it) }.distinct()).containsExactly("Tile")
      assertThat(tree.weight(group)).isEqualTo(instances.sumOf { tree.weight(it) })
      // Zooming into a group is laying it out as a root, and its instances have to survive a rebuild
      // for the breadcrumb to still mean something after following another strength.
      assertThat(group in tree).isTrue()
      assertThat(group in explorer.treeFor(setOf(WEAK))).isTrue()
    }
  }

  @Test fun `a class group reads as a pile of objects rather than as one`() {
    HeapExplorer.open(crowdedRootHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val group = tree.children(tree.root).single { tree.classGroupOrNull(it) != null }
      val presented = tree.present(TreemapLayout(), VIEWPORT)
        .cells
        .single { (it.cell.subject as? CellSubject.Node)?.node == group }

      assertThat(tree.label(group))
        .isEqualTo("$TILE_COUNT ${HeapDominatorTreemap.CLASS_GROUP_LABEL_SEPARATOR} Tile")
      assertThat(presented.content).isEqualTo(CellContent.ClassGroup(TILE_CLASS_NAME, TILE_COUNT))
      // Nothing to be strongly or weakly reachable: a class group isn't an object of the heap dump.
      assertThat(presented.strength).isNull()
      assertThatThrownBy { tree.summarize(group) }
        .isInstanceOf(IllegalArgumentException::class.java)
    }
  }

  @Test fun `the tree is reused when the strengths do not change`() {
    openTestHeapDump().use { explorer ->
      assertThat(explorer.treeFor(setOf(WEAK))).isSameAs(explorer.treeFor(setOf(WEAK)))
      assertThat(explorer.treeFor(emptySet())).isNotSameAs(explorer.treeFor(setOf(WEAK)))
    }
  }

  /**
   * A heap dump where one instance is the only path to a large object array, so that the dominator
   * tree has an object retaining well more than its shallow size.
   */
  private fun openTestHeapDump(onProgress: (String) -> Unit = {}): HeapExplorer {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val payload = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(256)))
      val holder = "com.example.Holder" instance {
        field["payload"] = payload
        field["name"] = string("Kept alive by the holder")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return HeapExplorer.open(file, onProgress)
  }

  /** A heap dump where a large object array is only reachable through a `WeakReference`. */
  private fun weaklyReachablePayloadHeapDump(): File {
    val file = testFolder.newFile("weakly-reachable.hprof")
    file.dump {
      val classes = referenceClasses()
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val weakReference = reference(classes.weakId, payload)
      gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 0))
    }
    return file
  }

  /** A heap dump with a bitmap in it, whose pixels live in native memory rather than in its fields. */
  private fun bitmapHeapDump(): File {
    val file = testFolder.newFile("bitmap.hprof")
    file.dump {
      val bitmap = "android.graphics.Bitmap" instance {
        field["mWidth"] = IntHolder(420)
        field["mHeight"] = IntHolder(467)
        field["mRecycled"] = BooleanHolder(false)
      }
      gcRoot(JniGlobal(id = bitmap.value, jniGlobalRefId = 0))
    }
    return file
  }

  /** A heap dump where two unrelated instances both hold the same object array. */
  private fun sharedPayloadHeapDump(): File {
    val file = testFolder.newFile("shared-payload.hprof")
    file.dump {
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val holder = "com.example.Holder" instance { field["payload"] = payload }
      val otherHolder = "com.example.OtherHolder" instance { field["payload"] = payload }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = otherHolder.value, jniGlobalRefId = 1))
    }
    return file
  }

  /** A heap dump where an object array is held by an instance and pointed at by a `WeakReference`. */
  private fun stronglyAndWeaklyReachablePayloadHeapDump(): File {
    val file = testFolder.newFile("strongly-and-weakly-reachable.hprof")
    file.dump {
      val classes = referenceClasses()
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val holder = "com.example.Holder" instance {
        field["payload"] = payload
      }
      val weakReference = reference(classes.weakId, payload)
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 1))
    }
    return file
  }

  /**
   * A heap dump shaped like the one this feature came from: an image cache and the view showing the
   * image both hold it, and the view holds it twice — once as what it draws and once as the result of
   * the request that loaded it.
   *
   * The payload is what the bitmap stands for. Its two referrers are the wrapper and the view, and the
   * wrapper's own two referrers are the cache and the tile, so the paths only meet at the root even
   * though a tile is what actually keeps the payload in memory.
   */
  private fun cachedPayloadHeapDump(): File {
    val file = testFolder.newFile("cached-payload.hprof")
    file.dump {
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val wrapper = "com.example.Wrapper" instance { field["payload"] = payload }
      val view = "com.example.View" instance { field["drawable"] = payload }
      val tile = "com.example.Tile" instance {
        field["result"] = wrapper
        field["view"] = view
      }
      val cache = "com.example.Cache" instance { field["entry"] = wrapper }
      gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = cache.value, jniGlobalRefId = 1))
    }
    return file
  }

  /**
   * A heap dump where an object is held by its owner and by a helper of its own, the way an
   * `AppCompatImageView` is held by the layout above it and by the helpers it created, which point back
   * at it.
   */
  private fun cyclicHolderHeapDump(): File {
    val file = testFolder.newFile("cyclic-holder.hprof")
    file.dump {
      val viewClassId = clazz(
        className = "com.example.View",
        fields = listOf("helper" to ReferenceHolder::class, "payload" to ReferenceHolder::class)
      )
      val helperClassId = clazz(
        className = "com.example.Helper",
        fields = listOf("view" to ReferenceHolder::class)
      )
      // The helper points back at the view, so the view's id has to exist before the view is written.
      val viewId = reserveObjectId()
      val helper = instance(helperClassId, listOf(viewId))
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val view = instance(viewClassId, listOf(helper, payload), objectId = viewId)
      val tile = "com.example.Tile" instance { field["view"] = view }
      gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 0))
    }
    return file
  }

  /**
   * A heap dump with more children under the root than a view can draw one by one: [TILE_COUNT]
   * instances of one class, each a GC root of its own, plus one instance of another class.
   */
  private fun crowdedRootHeapDump(withJavaLangClass: Boolean = false): File {
    val file = testFolder.newFile("crowded-root${if (withJavaLangClass) "-with-class" else ""}.hprof")
    file.dump {
      if (withJavaLangClass) {
        clazz(className = "java.lang.Class")
      }
      val tileClassId = clazz(
        className = TILE_CLASS_NAME,
        fields = listOf("payload" to ReferenceHolder::class)
      )
      repeat(TILE_COUNT) { index ->
        // A different payload size per tile, so that the instances of one class don't all weigh the same.
        val payload = ReferenceHolder(
          objectArray(arrayClass("java.lang.Object"), LongArray(index + 1))
        )
        val tile = instance(tileClassId, listOf(payload))
        gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = index.toLong()))
      }
      val solo = "com.example.Solo" instance { field["name"] = string("Only one of me") }
      gcRoot(JniGlobal(id = solo.value, jniGlobalRefId = TILE_COUNT.toLong()))
    }
    return file
  }

  private fun HeapDominatorTreemap.findByLabel(label: String): HeapObjectSummary =
    allSummaries().single { it.label == label }

  /** What every node of the tree dominates, by label: the shape of the treemap, in one value. */
  private fun HeapDominatorTreemap.dominatedLabels(): Map<String, List<String>> =
    allSummaries().associate { summary ->
      summary.label to children(summary.objectId).map { label(it) }.sorted()
    }

  private fun HeapDominatorTreemap.allSummaries(): List<HeapObjectSummary> {
    val summaries = mutableListOf<HeapObjectSummary>()
    val toVisit = ArrayDeque(listOf(root))
    while (toVisit.isNotEmpty()) {
      val objectId = toVisit.removeFirst()
      summaries += summarize(objectId)
      toVisit += children(objectId)
    }
    return summaries
  }

  companion object {
    private const val PAYLOAD_ELEMENT_COUNT = 1024

    private const val TILE_CLASS_NAME = "com.example.Tile"

    /** Past `MIN_CHILDREN_TO_GROUP_BY_CLASS` in [HeapDominatorTreemap], which is 200. */
    private const val TILE_COUNT = 205

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 800.0, bottom = 600.0)

    /** Matches `MAX_FIELDS` in [HeapDominatorTreemap], which isn't public. */
    private const val MAX_FIELDS_SHOWN = 500

    /** Object ids are 4 bytes in a dump built by the test DSL. */
    private const val PAYLOAD_BYTE_SIZE = PAYLOAD_ELEMENT_COUNT * 4L
  }
}
