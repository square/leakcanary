package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.HeapDominatorTreemap.Companion.GC_ROOTS_NODE_ID
import shark.explorer.ReachabilityStrength.CACHE
import shark.explorer.ReachabilityStrength.LOCAL
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK

class HeapExplorerTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the root is the whole heap dump`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val rootChildren = tree.children(tree.root)

      // A dump whose garbage was all collected has the one half, which is this one.
      assertThat(rootChildren).containsExactly(GC_ROOTS_NODE_ID)
      // The virtual root has no shallow size of its own, so it weighs exactly what it dominates.
      assertThat(tree.weight(tree.root)).isEqualTo(rootChildren.sumOf { tree.weight(it) })
      assertThat(tree.weight(tree.root)).isEqualTo(explorer.sizes.totalByteCount)
    }
  }

  @Test fun `children are ordered largest retained first`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val weights = tree.children(GC_ROOTS_NODE_ID).map { tree.weight(it) }

      assertThat(weights).isEqualTo(weights.sortedDescending())
    }
  }

  @Test fun `the root is labelled rather than read from the heap`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      assertThat(tree.label(tree.root)).isEqualTo(HeapDominatorTreemap.ROOT_LABEL)
      assertThat(tree.summarize(tree.root).inspectorLabels).isEmpty()
    }
  }

  @Test fun `an instance is labelled with its simple class name`() {
    testFolder.openTestHeapDump().use { explorer ->
      val holder = explorer.tree.findByLabel("Holder")

      assertThat(holder.className).isEqualTo("com.example.Holder")
    }
  }

  @Test fun `an object retains what it dominates`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
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
    testFolder.openTestHeapDump().use { explorer ->
      val string = explorer.tree.findByLabel("String")

      assertThat(string.headline).isEqualTo("\"Kept alive by the holder\"")
    }
  }

  @Test fun `a bitmap leads with its dimensions`() {
    HeapExplorer.open(testFolder.bitmapHeapDump()).use { explorer ->
      val bitmap = explorer.tree.findByLabel("Bitmap")

      assertThat(bitmap.headline).isEqualTo("420 × 467 pixels")
    }
  }

  @Test fun `a bitmap is a cell with its pixels on it`() {
    HeapExplorer.open(testFolder.pixelBitmapHeapDump()).use { explorer ->
      val tree = explorer.tree
      val bitmap = tree.findByLabel("Bitmap")
      val presented = tree.present(TreemapLayout(), VIEWPORT)
        .cells
        .single { (it.cell.subject as? CellSubject.Node)?.node == bitmap.objectId }

      // Which is what has the view draw the image on that rectangle instead of a label on it.
      assertThat(presented.content).isEqualTo(CellContent.Object(STRONG, isBitmap = true))
      assertThat(tree.bitmapCounts()).isEqualTo(
        BitmapCounts(count = 1, withImageCount = 1, carriesNativePixels = false)
      )
      val image = tree.bitmapImages(listOf(bitmap.objectId), maxDimension = 64)
        .getValue(bitmap.objectId)
      assertThat((image as BitmapImage.Pixels).argb.single().toUInt().toString(16))
        .isEqualTo("ffff0000")
    }
  }

  @Test fun `an object lists its fields, references reading as what they point at`() {
    testFolder.openTestHeapDump().use { explorer ->
      val holder = explorer.tree.findByLabel("Holder")

      assertThat(holder.fields.map { "${it.name} = ${it.value}" })
        .containsExactly("payload = Object[]", "name = \"Kept alive by the holder\"")
      assertThat(holder.fields.map { it.declaringClassName }).containsOnly("Holder")
      // Both point at objects in the tree, so the panel can walk to them.
      assertThat(holder.fields.map { it.inspectableObjectId }).doesNotContainNull()
    }
  }

  @Test fun `an array lists its elements, and says how many it left out`() {
    HeapExplorer.open(testFolder.weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val array = tree.findByLabel("Object[]")

      assertThat(array.headline).isEqualTo("$PAYLOAD_ELEMENT_COUNT elements")
      assertThat(array.fields).hasSize(MAX_FIELDS_SHOWN)
      assertThat(array.hiddenFieldCount).isEqualTo(PAYLOAD_ELEMENT_COUNT - MAX_FIELDS_SHOWN)
      assertThat(array.fields.first().name).isEqualTo("[0]")
    }
  }

  @Test fun `an object two others hold is dominated by all the gc roots together`() {
    HeapExplorer.open(testFolder.sharedPayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val payload = tree.findByLabel("Object[]")

      // Neither holder alone would free the payload, so the dominator tree can only attribute it to the
      // whole heap. That is what makes a big rectangle sit flat under the root, and the one path per
      // holder is the answer to why.
      assertThat(tree.children(GC_ROOTS_NODE_ID)).contains(payload.objectId)
      val dominator = tree.dominatorOf(payload.objectId)!!
      assertThat(dominator.kind).isEqualTo(DominatorKind.ALL_GC_ROOTS)
      assertThat(dominator.nodeId).isEqualTo(GC_ROOTS_NODE_ID)
      assertThat(tree.independentPathsTo(payload.objectId).paths.map { it.stepLabels() })
        .containsExactlyInAnyOrder(
          listOf("Holder", "payload → Object[]"),
          listOf("OtherHolder", "payload → Object[]")
        )
    }
  }

  @Test fun `an object held two ways has a path for each, sharing nothing in between`() {
    HeapExplorer.open(testFolder.cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val payload = tree.findByLabel("Object[]")

      val paths = tree.independentPathsTo(payload.objectId)

      // Two chains sharing nothing in between: the tile that shows the payload holds it as what its view
      // draws, and the cache holds it through the wrapper. There is no third — the tile holds the wrapper
      // too, but that chain would have to go round through the wrapper the cache's path already went
      // through, and that isn't another reason the payload is in memory.
      assertThat(paths.paths.map { it.stepLabels() }).containsExactlyInAnyOrder(
        listOf("Tile", "view → View", "drawable → Object[]"),
        listOf("Cache", "entry → Wrapper", "payload → Object[]")
      )
      assertThat(paths.paths.map { it.gcRootLabel }.distinct())
        .containsExactly("GC root: JNI global reference")
      assertThat(paths.hasMore).isFalse()
    }
  }

  @Test fun `an object with an owner is held below it`() {
    HeapExplorer.open(testFolder.cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val view = tree.findByLabel("View")

      val dominator = tree.dominatorOf(view.objectId)!!
      val paths = tree.independentPathsTo(view.objectId)

      // Everything holding the view goes through the tile, so the tile is the whole story and the one
      // path below it is the field it holds the view in.
      assertThat(dominator.kind).isEqualTo(DominatorKind.OBJECT)
      assertThat(dominator.label).isEqualTo("Tile")
      assertThat(paths.paths.map { it.stepLabels() }).containsExactly(listOf("view → View"))
    }
  }

  @Test fun `the paths to a starred object lead back to where the treemap draws it`() {
    HeapExplorer.open(testFolder.cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val view = tree.findByLabel("View")
      val tile = tree.findByLabel("Tile")

      // What clicking a step of a path does: the treemap zooms to where that object is drawn, however
      // deep in the tree the heap dump's own references led.
      assertThat(tree.pathToOpen(view.objectId))
        .containsExactly(tree.root, GC_ROOTS_NODE_ID, tile.objectId)
      assertThat(tree.pathToOpen(tile.objectId))
        .containsExactly(tree.root, GC_ROOTS_NODE_ID, tile.objectId)
      assertThat(tree.pathToOpen(GC_ROOTS_NODE_ID)).containsExactly(tree.root, GC_ROOTS_NODE_ID)
    }
  }

  @Test fun `a cache that evicts is not what keeps an image in memory`() {
    HeapExplorer.open(testFolder.coilCachedImageHeapDump(alsoShownByATile = true)).use { explorer ->
      val tree = explorer.tree
      val pixels = tree.findByLabel("Object[]")

      // Coil's cache and the tile showing the image both hold it, which is what would otherwise leave the
      // root dominating it: the cache isn't why it's in memory, so its reference doesn't count and the
      // bytes land on the tile.
      assertThat(tree.children(GC_ROOTS_NODE_ID)).doesNotContain(pixels.objectId)
      assertThat(pixels.strength).isEqualTo(STRONG)
      val dominator = tree.dominatorOf(pixels.objectId)!!
      assertThat(dominator.kind).isEqualTo(DominatorKind.OBJECT)
      assertThat(dominator.label).isEqualTo("Tile")
    }
  }

  @Test fun `both ways an owner holds an object are spelled out below it`() {
    HeapExplorer.open(testFolder.coilCachedImageHeapDump(alsoShownByATile = true)).use { explorer ->
      val tree = explorer.tree

      val paths = tree.independentPathsTo(tree.findByLabel("Object[]").objectId)

      // The tile holds the pixels two ways, as what its view draws and through the result of the request
      // that loaded them, and neither way is round through the other. The cache holds them as well and is
      // on neither path: a path is a reason the object is in memory, and an evicting cache isn't one.
      assertThat(paths.paths.map { it.stepLabels() }).containsExactlyInAnyOrder(
        listOf("view → View", "drawable → Object[]"),
        listOf("result → SuccessResult", "image → BitmapImage", "bitmap → Object[]")
      )
      // No GC root to name below an object: the path starts at the dominator, which the panel has above.
      assertThat(paths.paths.map { it.gcRootLabel }).containsOnlyNulls()
    }
  }

  @Test fun `an image nothing but a cache holds is reachable at the cache strength`() {
    HeapExplorer.open(testFolder.coilCachedImageHeapDump(alsoShownByATile = false)).use { explorer ->
      // The bytes are the cache's, and they are bytes: an image no view is showing any more is exactly
      // what someone looking at a treemap of a heap dump wants to find.
      assertThat(explorer.sizes.byteCountByStrength.getValue(CACHE)).isGreaterThan(PAYLOAD_BYTE_SIZE)

      val tree = explorer.tree
      val cacheEntry = tree.findByLabel(CACHE_ENTRY_LABEL)

      assertThat(tree.children(cacheEntry.objectId).map { tree.label(it) })
        .containsExactly("BitmapImage")
      assertThat(tree.findByLabel("BitmapImage").strength).isEqualTo(CACHE)
      // The cache's own bookkeeping is held strongly by it, which is what cutting at the value a cache
      // entry wraps rather than at the cache itself is for.
      assertThat(cacheEntry.strength).isEqualTo(STRONG)
    }
  }

  @Test fun `a reference from a cache is not a way of holding what it caches`() {
    HeapExplorer.open(testFolder.coilCachedImageHeapDump(alsoShownByATile = true)).use { explorer ->
      val tree = explorer.tree
      val image = tree.findByLabel("BitmapImage")
      val cacheEntry = tree.findByLabel(CACHE_ENTRY_LABEL)

      // The cache entry points at the image as squarely as the request result does, and the panel still
      // shows the reference as one of the entry's fields. What it isn't is a reason the image is in
      // memory, so no path goes through it and the entry retains nothing but itself.
      assertThat(cacheEntry.fields.map { "${it.name} = ${it.value}" }).containsExactly("image = BitmapImage")
      assertThat(tree.children(cacheEntry.objectId)).isEmpty()
      assertThat(tree.dominatorOf(image.objectId)!!.label).isEqualTo("SuccessResult")
      assertThat(tree.independentPathsTo(image.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("image → BitmapImage"))
    }
  }

  @Test fun `what only holds an object until the runtime is done with it is on no path`() {
    HeapExplorer.open(testFolder.lastResortHoldersHeapDump()).use { explorer ->
      val tree = explorer.tree

      // A thread inside a method, a value left in a thread local, an object queued for finalization: each
      // of the three holds one of these objects, and none of the three is why it is in memory. Answering
      // "what holds this" with one of them is answering with the runtime's bookkeeping, so the field of
      // the owner is the whole answer, and the owner is what the bytes are drawn under.
      listOf("OnStack", "InThreadLocal", "Finalized").forEach { className ->
        val held = tree.findByLabel(className)
        val field = className.replaceFirstChar { it.lowercase() }

        assertThat(held.strength).describedAs(className).isEqualTo(STRONG)
        assertThat(tree.dominatorOf(held.objectId)!!.label).describedAs(className).isEqualTo("Holder")
        assertThat(tree.independentPathsTo(held.objectId).paths.map { it.stepLabels() })
          .describedAs(className)
          .containsExactly(listOf("$field → $className"))
      }
    }
  }

  @Test fun `an object nothing but a stack frame holds is drawn under the frame's root`() {
    HeapExplorer.open(testFolder.lastResortHoldersHeapDump()).use { explorer ->
      val tree = explorer.tree
      val onlyOnStack = tree.findByLabel("OnlyOnStack")

      // Nothing is dropped by ignoring the weaker way of holding something: an object a running method is
      // the only holder of is still in the tree, at the strength that says so, which is the same rule that
      // keeps a weakly reachable object in it.
      assertThat(onlyOnStack.strength).isEqualTo(LOCAL)
      assertThat(tree.children(GC_ROOTS_NODE_ID)).contains(onlyOnStack.objectId)
      assertThat(tree.independentPathsTo(onlyOnStack.objectId).paths.map { it.gcRootLabel })
        .containsExactly("GC root: local variable of a running method")
    }
  }

  @Test fun `the root has no dominator and no path leading to it`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      assertThat(tree.dominatorOf(tree.root)).isNull()
      assertThat(tree.independentPathsTo(tree.root).paths).isEmpty()
    }
  }

  @Test fun `an object a gc root points at is held by the root itself`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val holder = tree.findByLabel("Holder")

      val dominator = tree.dominatorOf(holder.objectId)!!
      val path = tree.independentPathsTo(holder.objectId).paths.single()

      // Nothing in the heap dump points at it, so there is no chain to walk up and the whole answer to how
      // it's held is which kind of GC root reaches it.
      assertThat(dominator.kind).isEqualTo(DominatorKind.ALL_GC_ROOTS)
      assertThat(dominator.nodeId).isEqualTo(GC_ROOTS_NODE_ID)
      assertThat(path.gcRootLabel).isEqualTo("GC root: JNI global reference")
      assertThat(path.stepLabels()).containsExactly("Holder")
    }
  }

  @Test fun `a path that leads back to the object is not a way of holding it`() {
    HeapExplorer.open(testFolder.cyclicHolderHeapDump()).use { explorer ->
      val tree = explorer.tree
      val view = tree.findByLabel("View")

      // The view's own helper points back at it, so one of its two referrers is only reachable through the
      // view itself. Counting that as a way of holding the view would report the view as holding itself.
      assertThat(tree.children(view.objectId).map { tree.label(it) }).contains("Helper")
      assertThat(tree.independentPathsTo(view.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("view → View"))
    }
  }

  @Test fun `the shortest way a gc root holds an object marks what dominates it`() {
    HeapExplorer.open(testFolder.twoWaysToOnePayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val payload = tree.findByLabel("Object[]")

      val path = tree.rootPathTo(payload.objectId)

      // The holder points at the payload and also at the relay that leads to it the long way round. The
      // chain beside the treemap is the plainest answer to how the payload is held, so it's the short way,
      // and the holder is marked because letting go of it is what would free the payload.
      assertThat(path.gcRootLabel).isEqualTo("GC root: JNI global reference")
      assertThat(path.stepLabels()).containsExactly("Holder", "payload → Object[]")
      assertThat(path.steps.map { it.isDominator }).containsExactly(true, false)
      assertThat(path.hiddenStepCount).isZero()
    }
  }

  @Test fun `a chain too long to read leaves out the steps nearest the gc root`() {
    HeapExplorer.open(testFolder.longChainHeapDump()).use { explorer ->
      val tree = explorer.tree
      val payload = tree.findByLabel("Object[]")

      val path = tree.rootPathTo(payload.objectId)

      // What the reader is after is what holds the object, and the plumbing between a GC root and an app's
      // own objects rarely is, so a chain past what fits is cut at the root end.
      assertThat(path.hiddenStepCount).isEqualTo(CHAIN_LINK_COUNT + 1 - MAX_ROOT_PATH_STEPS_SHOWN)
      assertThat(path.steps).hasSize(MAX_ROOT_PATH_STEPS_SHOWN)
      // Named by the field it is held in even though the object holding it is one of the steps left out.
      assertThat(path.stepLabels().first()).isEqualTo("next → Link18")
      assertThat(path.stepLabels().last()).isEqualTo("next → Object[]")
    }
  }

  @Test fun `an object a gc root points at is the whole chain on its own`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val holder = tree.findByLabel("Holder")

      val path = tree.rootPathTo(holder.objectId)

      // Nothing in the heap dump points at it, so which kind of GC root reaches it is the whole answer.
      assertThat(path.gcRootLabel).isEqualTo("GC root: JNI global reference")
      assertThat(path.stepLabels()).containsExactly("Holder")
      assertThat(path.steps.single().step.reference).isNull()
    }
  }

  @Test fun `a chain that starts at no gc root says the object is garbage`() {
    HeapExplorer.open(testFolder.uncollectedGarbageHeapDump()).use { explorer ->
      val tree = explorer.tree
      val payload = tree.findByLabel("Object[]")

      val path = tree.rootPathTo(payload.objectId)

      // Bytes that are still bytes, held by an object that is garbage itself: there is a chain to draw,
      // and no GC root at the top of it.
      assertThat(path.gcRootLabel).isEqualTo("Uncollected garbage")
      assertThat(path.stepLabels()).containsExactly("Forgotten", "payload → Object[]")
    }
  }

  @Test fun `neither the whole heap dump nor a pile of objects has a chain leading to it`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      assertThat(tree.rootPathTo(tree.root)).isEqualTo(RootPath.NONE)
      assertThat(tree.rootPathTo(GC_ROOTS_NODE_ID)).isEqualTo(RootPath.NONE)
    }
  }

  @Test fun `an object above the 2 GB mark is described like any other`() {
    val dump = testFolder.highAddressHeapDump()
    HeapExplorer.open(dump.file).use { explorer ->
      val tree = explorer.tree
      val holder = tree.findByLabel("Holder")

      // The premise: a 4 byte id that far up the address space reads back negative. Which the tree's own
      // ids have to stay clear of, or the biggest objects of a 32 bit Android dump — its bitmaps and their
      // pixels — are each read as a pile of objects that this tree has never heard of, and every panel
      // asking about one is told there is nothing there.
      assertThat(dump.payloadObjectId).isNegative()
      assertThat(HeapDominatorTreemap.isPileId(dump.payloadObjectId)).isFalse()
      assertThat(dump.payloadObjectId in tree).isTrue()
      assertThat(tree.groupOrNull(dump.payloadObjectId)).isNull()
      assertThat(tree.summarize(dump.payloadObjectId).className).isEqualTo("java.lang.Object[]")
      assertThat(tree.rootPathTo(dump.payloadObjectId).stepLabels())
        .containsExactly("Holder", "payload → Object[]")
      // And zooming to it lands on what holds it, rather than back at the root.
      assertThat(tree.pathToOpen(dump.payloadObjectId))
        .containsExactly(tree.root, GC_ROOTS_NODE_ID, holder.objectId)
    }
  }

  @Test fun `progress is reported for each step`() {
    val steps = mutableListOf<String>()

    testFolder.openTestHeapDump(onProgress = { steps += it }).use { }

    // Indexing, ownership, reachability, dominators: the passes over the heap dump the UI waits for.
    assertThat(steps).hasSize(4)
    assertThat(steps).allSatisfy { assertThat(it).isNotEmpty() }
  }

  @Test fun `opening a file that is not a heap dump fails`() {
    val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }

    assertThatThrownBy { HeapExplorer.open(notAHeapDump) }
      .isInstanceOf(Exception::class.java)
  }

  @Test fun `a weakly reachable object nests inside the weak reference reaching it`() {
    HeapExplorer.open(testFolder.weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val weakReference = tree.findByLabel("WeakReference")
      val dominated = tree.children(weakReference.objectId).map { tree.summarize(it) }

      // What makes a weakly reachable rectangle show up inside a strongly reachable one: the weak
      // reference itself is strongly reachable, and it dominates a referent that isn't. 1024 ids at 4
      // bytes each, so the referent is the biggest thing in the treemap — and it's in there, because it's
      // in the heap dump, however easily the next collection would take it.
      assertThat(weakReference.strength).isEqualTo(STRONG)
      assertThat(dominated.map { it.label }).containsExactly("Object[]")
      assertThat(dominated.single().strength).isEqualTo(WEAK)
      assertThat(tree.weight(tree.root)).isGreaterThan(PAYLOAD_BYTE_SIZE)
    }
  }

  @Test fun `a referent something strong also holds stays where the strong reference put it`() {
    HeapExplorer.open(testFolder.stronglyAndWeaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.tree

      // The payload is strongly reachable through the holder, so the weak reference has nothing to
      // reveal. Following it as well would make both a path to the payload, moving its bytes up to their
      // common ancestor — the root — and leaving the holder retaining nothing.
      assertThat(tree.children(tree.findByLabel("Holder").objectId).map { tree.label(it) })
        .containsExactly("Object[]")
      assertThat(tree.children(tree.findByLabel("WeakReference").objectId)).isEmpty()
      assertThat(tree.findByLabel("Object[]").strength).isEqualTo(STRONG)
    }
  }

  @Test fun `a half with few children keeps them as they are`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      assertThat(tree.children(GC_ROOTS_NODE_ID)).allSatisfy { child ->
        assertThat(tree.groupOrNull(child)).isNull()
      }
    }
  }

  @Test fun `a half with too many children to draw gathers them by class`() {
    HeapExplorer.open(testFolder.crowdedRootHeapDump()).use { explorer ->
      val tree = explorer.tree
      val children = tree.children(GC_ROOTS_NODE_ID)
      val groups = children.mapNotNull { tree.groupOrNull(it) }

      // One cell for the tiles, and the class with a single instance up here left as that instance: a
      // group of one would say nothing and add a level to click through.
      assertThat(groups.map { it.className }).containsExactly(TILE_CLASS_NAME)
      assertThat(groups.single().objectCount).isEqualTo(TILE_COUNT)
      assertThat(children.map { tree.label(it) }).contains("Solo")
      assertThat(children.map { tree.label(it) }).doesNotContain("Tile")
      // The loaded classes are up here too, and this dump has no `java.lang.Class` for them to gather
      // under, so they're left alone rather than forced into a group.
      assertThat(children.map { tree.label(it) }).contains("class Tile")
    }
  }

  @Test fun `the loaded classes gather under java lang Class`() {
    HeapExplorer.open(testFolder.crowdedRootHeapDump(withJavaLangClass = true)).use { explorer ->
      val tree = explorer.tree
      val children = tree.children(GC_ROOTS_NODE_ID)
      val groups = children.mapNotNull { tree.groupOrNull(it) }

      // What this is for: a real dump has thousands of loaded classes at the top of the tree — 9,502 in
      // the production dump this was measured on — and as one cell they stop drowning everything else.
      val classGroup = groups.single { it.className == "java.lang.Class" }
      assertThat(classGroup.objectCount).isGreaterThan(TILE_COUNT)
      assertThat(children.map { tree.label(it) }).doesNotContain("class Tile")
    }
  }

  @Test fun `a class group weighs and holds what its instances do`() {
    HeapExplorer.open(testFolder.crowdedRootHeapDump()).use { explorer ->
      val tree = explorer.tree
      val group = tree.classGroup()
      val instances = tree.children(group.nodeId)

      assertThat(instances).hasSize(TILE_COUNT)
      assertThat(instances.map { tree.label(it) }.distinct()).containsExactly("Tile")
      assertThat(tree.weight(group.nodeId)).isEqualTo(instances.sumOf { tree.weight(it) })
      assertThat(group.retainedSize).isEqualTo(tree.weight(group.nodeId))
      // Zooming into a group is laying it out as a root, so the tree has to know the id the navigation
      // path kept hold of.
      assertThat(group.nodeId in tree).isTrue()
    }
  }

  @Test fun `a class group reads as a pile of objects rather than as one`() {
    HeapExplorer.open(testFolder.crowdedRootHeapDump()).use { explorer ->
      val tree = explorer.tree
      val group = tree.classGroup().nodeId
      val presented = tree.present(TreemapLayout(), VIEWPORT)
        .cells
        .single { (it.cell.subject as? CellSubject.Node)?.node == group }

      assertThat(tree.label(group))
        .isEqualTo("$TILE_COUNT ${HeapDominatorTreemap.CLASS_GROUP_LABEL_SEPARATOR} Tile")
      assertThat(presented.content)
        .isEqualTo(CellContent.ObjectGroup(ObjectGroupKind.CLASS, STRONG, TILE_COUNT))
      // Held as firmly as the instances in it, which are all held the same way — being up here is what
      // gathered them.
      assertThat(presented.strength).isEqualTo(STRONG)
      assertThatThrownBy { tree.summarize(group) }
        .isInstanceOf(IllegalArgumentException::class.java)
    }
  }

  private fun HeapDominatorTreemap.classGroup(): ObjectGroupSummary =
    children(GC_ROOTS_NODE_ID).mapNotNull { groupOrNull(it) }.single()

  companion object {
    /** What a cache entry reads as on a rectangle, off [CACHE_ENTRY_CLASS_NAME]. */
    private const val CACHE_ENTRY_LABEL = "RealStrongMemoryCache\$InternalValue"

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 800.0, bottom = 600.0)

    /** Matches `MAX_FIELDS` in [HeapDominatorTreemap], which isn't public. */
    private const val MAX_FIELDS_SHOWN = 500

    /** Matches `MAX_ROOT_PATH_STEPS` in [HeapDominatorTreemap], which isn't public. */
    private const val MAX_ROOT_PATH_STEPS_SHOWN = 20

    /** Object ids are 4 bytes in a dump built by the test DSL. */
    private const val PAYLOAD_BYTE_SIZE = PAYLOAD_ELEMENT_COUNT * 4L
  }
}
