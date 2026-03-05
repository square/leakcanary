@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package shark

import shark.ObjectDominators.DominatorNode

/**
 * Computes an exact dominator tree using the Lengauer-Tarjan algorithm with link-eval
 * (union-find with path compression). Adapted from Android Studio's
 * `perflib/.../LinkEvalDominators.kt` (Apache 2.0,
 * http://adambuchsbaum.com/papers/dom-toplas.pdf).
 *
 * Unlike [DominatorTree], which builds an approximate dominator tree incrementally during
 * BFS and can leave dominators too specific when cross-edges are processed with stale
 * parent dominators, this class performs a dedicated pass and produces always-exact results.
 *
 * ## Algorithm (4 phases)
 *
 * **Phase 1 — iterative DFS**: assigns depth-first numbers (DFNs) to reachable objects,
 * records each node's DFS-tree parent, and accumulates all edges (tree + cross) in a flat
 * buffer. All GC roots are children of a virtual root at DFN 0, which handles the
 * multiple-root case uniformly. Children are pushed to the stack unconditionally; duplicates
 * are detected at pop time (DFN already assigned), deferring the `objectIndex` lookup.
 *
 * **Phase 2 — CSR predecessor list**: two-pass conversion of the flat edge buffer into a
 * Compressed Sparse Row structure (offsets array + packed predecessor DFNs). Only this
 * structure scales with edge count; all other arrays are sized by node count.
 *
 * **Phase 3 — Lengauer-Tarjan Steps 2+3+4**: reverse-DFS-order computation of
 * semi-dominators (Step 2) and tentative immediate dominators (Step 3) using intrusive
 * singly-linked bucket lists; then a forward pass (Step 4) to resolve deferred assignments.
 * The link-eval `compress` function is iterative (not recursive) to avoid stack overflow on
 * heap graphs with deep object chains.
 *
 * **Phase 4 — retained sizes**: maps DFN results back to object IDs, populates a
 * [DominatorTree] instance, and delegates to [DominatorTree.buildFullDominatorTree].
 *
 * ## Memory design
 *
 * All large arrays use [VarIntArray] / [VarLongArray] (ByteArray-backed, variable bytes per
 * entry). For heaps with up to ~16 M objects `bytesPerDfn = 3`, saving 25% vs `IntArray`.
 * The INVALID sentinel is the all-0xFF bit pattern, which always exceeds any valid DFN.
 *
 * [HeapObject.objectIndex] (a dense 0-based `Int`) is used as the node key in Phase 1,
 * replacing the `IdentityHashMap` in Android Studio's generic implementation and avoiding
 * the ~20 MB overhead of a `LongLongScatterMap`.
 *
 * Memory on a 193 MB heap dump (~983 K reachable objects, 2.5 M edges):
 * ~48 MB peak during CSR construction, ~43 MB during L-T, ~13 MB during retained-size
 * computation (only `doms[]` + id-mapping arrays remain alongside `DominatorTree.dominated`).
 */
class LinkEvalDominatorTree(
  private val graph: HeapGraph,
  private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
  private val gcRootProvider: GcRootProvider,
) {

  fun compute(
    objectSizeCalculator: ObjectSizeCalculator
  ): Map<Long, DominatorNode> {
    val referenceReader = referenceReaderFactory.createFor(graph)
    val objectCount = graph.objectCount
    val identifierByteSize = graph.identifierByteSize

    // bytes needed to store any DFN in [0..reachableCount] plus the INVALID sentinel.
    // +1 guarantees that INVALID (all-0xFF pattern) > objectCount >= any valid DFN.
    val bytesPerDfn = byteSizeForUnsigned(objectCount.toLong() + 1)

    // All-bits-set for bytesPerDfn bytes: always greater than any valid DFN.
    val invalidDfn = -1 ushr ((4 - bytesPerDfn) * 8)

    // DFN 0 = virtual root (not a real heap object; parent of all GC roots).
    // Real object DFNs are assigned starting at 1.

    // ── Phase 1 arrays ────────────────────────────────────────────────────────────────

    // objectIndex → DFN; invalidDfn = not yet visited.
    val dfnByObjectIndex = VarIntArray(objectCount, bytesPerDfn).also { it.fillInvalid() }
    // DFN → objectIndex (DFNs range 1..objectCount; +1 for the virtual root at DFN 0).
    val dfnToObjectIndex = VarIntArray(objectCount + 1, bytesPerDfn)
    // DFN → objectId (for final output).
    val dfnToObjectId = VarLongArray(objectCount + 1, identifierByteSize)
    // DFN → DFS-tree parent DFN.
    val parents = VarIntArray(objectCount + 1, bytesPerDfn)

    var counter = 1 // next DFN to assign; 0 is reserved for the virtual root

    // Edge list: flat IntArray of (fromDfn, toDfn) pairs collected during DFS.
    // Freed after CSR construction; 8 bytes/pair (2 ints) for simplicity.
    var edgeData = IntArray(objectCount.coerceAtLeast(8) * 2)
    var edgeCount = 0

    fun addEdge(fromDfn: Int, toDfn: Int) {
      if (edgeCount * 2 >= edgeData.size) {
        edgeData = edgeData.copyOf(edgeData.size * 2)
      }
      edgeData[edgeCount * 2] = fromDfn
      edgeData[edgeCount * 2 + 1] = toDfn
      edgeCount++
    }

    // DFS stack: flat IntArray of (objectIndex, parentDfn) pairs.
    var stackData = IntArray(1024)
    var stackTop = 0

    fun push(objectIndex: Int, parentDfn: Int) {
      if (stackTop * 2 >= stackData.size) {
        stackData = stackData.copyOf(stackData.size * 2)
      }
      stackData[stackTop * 2] = objectIndex
      stackData[stackTop * 2 + 1] = parentDfn
      stackTop++
    }

    // ── Phase 1: DFS traversal ────────────────────────────────────────────────────────

    gcRootProvider.provideGcRoots(graph).forEach { gcRootRef ->
      val id = gcRootRef.gcRoot.id
      val obj = graph.findObjectByIdOrNull(id) ?: return@forEach
      push(obj.objectIndex, 0) // parentDfn = 0 = virtual root
    }

    while (stackTop > 0) {
      stackTop--
      val oIdx = stackData[stackTop * 2]
      val parentDfn = stackData[stackTop * 2 + 1]

      val existingDfn = dfnByObjectIndex[oIdx]
      if (existingDfn != invalidDfn) {
        // Duplicate pop: same target was pushed by multiple parents before first visit.
        // Record as a cross-edge and skip re-processing.
        addEdge(parentDfn, existingDfn)
        continue
      }

      // First visit: assign DFN and record ancestry.
      val dfn = counter++
      dfnByObjectIndex[oIdx] = dfn
      dfnToObjectIndex[dfn] = oIdx
      val heapObject = graph.findObjectByIndex(oIdx) // O(1)
      dfnToObjectId[dfn] = heapObject.objectId
      parents[dfn] = parentDfn
      addEdge(parentDfn, dfn) // tree edge

      // Explore outgoing references.
      referenceReader.read(heapObject).forEach { ref ->
        if (ref.isLeafObject) return@forEach
        val targetId = ref.valueObjectId
        if (targetId == 0L) return@forEach
        val targetObj = graph.findObjectByIdOrNull(targetId) ?: return@forEach
        val tOIdx = targetObj.objectIndex
        val tDfn = dfnByObjectIndex[tOIdx]
        if (tDfn != invalidDfn) {
          addEdge(dfn, tDfn) // target already visited: cross-edge
        } else {
          push(tOIdx, dfn) // not yet visited: explore later
        }
      }
    }

    val reachableCount = counter - 1
    if (reachableCount == 0) return emptyMap()

    // ── Phase 2: Build CSR predecessor list ───────────────────────────────────────────

    val inDegree = IntArray(reachableCount + 1)
    for (i in 0 until edgeCount) {
      inDegree[edgeData[i * 2 + 1]]++
    }

    val totalEdges = edgeCount
    val bytesPerOffset = byteSizeForUnsigned(totalEdges.toLong() + 1)
    // predOffsets[v] = start of pred[v] in predData; size = reachableCount+2 for end sentinel.
    val predOffsets = VarIntArray(reachableCount + 2, bytesPerOffset)
    var prefixSum = 0
    for (v in 0..reachableCount) {
      predOffsets[v] = prefixSum
      prefixSum += inDegree[v]
    }
    predOffsets[reachableCount + 1] = prefixSum

    val predData = VarIntArray(totalEdges, bytesPerDfn)
    // Reuse inDegree as a write cursor initialised to each node's start offset.
    for (v in 0..reachableCount) inDegree[v] = predOffsets[v]
    for (i in 0 until edgeCount) {
      val fromDfn = edgeData[i * 2]
      val toDfn = edgeData[i * 2 + 1]
      predData[inDegree[toDfn]++] = fromDfn
    }
    // Release the edge buffer; it is no longer needed.
    edgeData = IntArray(0)

    // ── Phase 3: Lengauer-Tarjan (Steps 2, 3, 4) ──────────────────────────────────────
    //
    // Follows Android Studio's LinkEvalDominators.kt faithfully.
    // Nodes are DFNs 0..reachableCount; DFN 0 = virtual root.

    val n = reachableCount

    // semis[v] = DFN of v's semi-dominator; initialised to identity.
    val semis = VarIntArray(n + 1, bytesPerDfn)
    for (i in 0..n) semis[i] = i

    // ancestors[v] = link-eval forest parent; invalidDfn = not yet linked.
    val ancestors = VarIntArray(n + 1, bytesPerDfn).also { it.fillInvalid() }

    // labels[v] = node with minimum semi-dominator on the path from v to its forest root;
    // initialised to identity.
    val labels = VarIntArray(n + 1, bytesPerDfn)
    for (i in 0..n) labels[i] = i

    // doms[v] = immediate dominator DFN; zero-initialised (ByteArray default).
    val doms = VarIntArray(n + 1, bytesPerDfn)

    // Bucket chains: intrusive singly-linked lists to avoid per-bucket allocations.
    // bucketHead[v] = head of the list for bucket v; invalidDfn = empty.
    // bucketNext[v] = next node in v's bucket chain; invalidDfn = end of list.
    val bucketHead = VarIntArray(n + 1, bytesPerDfn).also { it.fillInvalid() }
    val bucketNext = VarIntArray(n + 1, bytesPerDfn).also { it.fillInvalid() }

    // Scratch stack for iterative compress (avoids stack overflow on deep paths).
    var compressData = IntArray(64)
    var compressTop = 0

    // eval: return the node with minimum semi-dominator label on the path from [node]
    // to its link-eval forest root. Applies iterative path compression.
    //
    // Mirrors Android Studio's eval/compress pair, adapted to VarIntArray.
    // The compress loop stops one step before the forest root to avoid mutating
    // the root's ancestor (which must stay invalidDfn).
    fun eval(node: Int): Int {
      if (ancestors[node] == invalidDfn) return node

      // Collect nodes to compress: everything except the node directly below the root.
      compressTop = 0
      var cur = node
      // Invariant: ancestors[cur] != invalidDfn (guaranteed on entry; maintained below).
      while (ancestors[ancestors[cur]] != invalidDfn) {
        if (compressTop >= compressData.size) {
          compressData = compressData.copyOf(compressData.size * 2)
        }
        compressData[compressTop++] = cur
        cur = ancestors[cur]
      }
      // cur is now the node whose parent IS the current forest root.
      // Walk back from deepest to shallowest, compressing labels and ancestors.
      for (i in compressTop - 1 downTo 0) {
        val v = compressData[i]
        val anc = ancestors[v]
        if (semis[labels[anc]] < semis[labels[v]]) {
          labels[v] = labels[anc]
        }
        ancestors[v] = ancestors[anc]
      }
      return labels[node]
    }

    // Steps 2 + 3: process nodes in reverse DFS order (skip virtual root at 0).
    for (currentNode in n downTo 1) {

      // Step 2: compute semi-dominator of currentNode.
      val predStart = predOffsets[currentNode]
      val predEnd = predOffsets[currentNode + 1]
      for (pi in predStart until predEnd) {
        val p = predData[pi]
        val evaledP = eval(p)
        if (semis[evaledP] < semis[currentNode]) {
          semis[currentNode] = semis[evaledP]
        }
      }

      // Link currentNode to its DFS-tree parent in the link-eval forest.
      ancestors[currentNode] = parents[currentNode]

      // Add currentNode to the bucket of its semi-dominator (Corollary 1 setup).
      val semiNode = semis[currentNode]
      bucketNext[currentNode] = bucketHead[semiNode]
      bucketHead[semiNode] = currentNode

      // Step 3: process bucket of parents[currentNode] (Corollary 1 application).
      val par = parents[currentNode]
      var v = bucketHead[par]
      while (v != invalidDfn) {
        val nextV = bucketNext[v]
        bucketNext[v] = invalidDfn
        val u = eval(v)
        doms[v] = if (semis[u] < semis[v]) u else par
        v = nextV
      }
      bucketHead[par] = invalidDfn
    }

    // Step 4: explicitly define immediate dominators (forward DFS order).
    for (currentNode in 1..n) {
      if (doms[currentNode] != semis[currentNode]) {
        doms[currentNode] = doms[doms[currentNode]]
      }
    }

    // ── Phase 4: Populate DominatorTree and compute retained sizes ────────────────────
    //
    // L-T guarantees doms[v] < v for all v >= 1, so processing in order 1..n ensures
    // each dominator is inserted before the nodes it dominates.

    val dominatorTree = DominatorTree(reachableCount)
    for (v in 1..n) {
      val objectId = dfnToObjectId[v]
      val domDfn = doms[v]
      if (domDfn == 0) {
        dominatorTree.updateDominatedAsRoot(objectId)
      } else {
        dominatorTree.updateDominated(objectId, dfnToObjectId[domDfn])
      }
    }

    return dominatorTree.buildFullDominatorTree(objectSizeCalculator)
  }
}

// ── Compact array helpers ──────────────────────────────────────────────────────────────

/**
 * A ByteArray-backed array of fixed-width unsigned integers stored in big-endian order.
 * Each entry occupies [bytesPerEntry] bytes (1–4).
 *
 * Reduces memory pressure compared to [IntArray]: heaps with up to ~16 M objects need
 * only 3 bytes per DFN entry instead of 4.
 *
 * The all-0xFF pattern (`fillInvalid()`) serves as an INVALID sentinel — always greater
 * than any valid index when [bytesPerEntry] is chosen via [byteSizeForUnsigned].
 */
internal class VarIntArray(val size: Int, val bytesPerEntry: Int) {
  val bytes = ByteArray(Math.toIntExact(size.toLong() * bytesPerEntry))

  /** Fills every entry with the all-0xFF INVALID sentinel. */
  fun fillInvalid() {
    bytes.fill(-1) // -1.toByte() == 0xFF
  }

  operator fun get(index: Int): Int {
    var offset = index * bytesPerEntry
    var value = 0
    repeat(bytesPerEntry) {
      value = (value shl 8) or (bytes[offset++].toInt() and 0xFF)
    }
    return value
  }

  operator fun set(index: Int, value: Int) {
    var offset = index * bytesPerEntry + bytesPerEntry - 1
    var v = value
    repeat(bytesPerEntry) {
      bytes[offset--] = v.toByte()
      v = v ushr 8
    }
  }
}

/**
 * A ByteArray-backed array of fixed-width unsigned longs stored in big-endian order.
 * [bytesPerEntry] matches [HeapGraph.identifierByteSize] (4 for standard Android hprof).
 */
internal class VarLongArray(val size: Int, val bytesPerEntry: Int) {
  val bytes = ByteArray(Math.toIntExact(size.toLong() * bytesPerEntry))

  operator fun get(index: Int): Long {
    var offset = index * bytesPerEntry
    var value = 0L
    repeat(bytesPerEntry) {
      value = (value shl 8) or (bytes[offset++].toLong() and 0xFF)
    }
    return value
  }

  operator fun set(index: Int, value: Long) {
    var offset = index * bytesPerEntry + bytesPerEntry - 1
    var v = value
    repeat(bytesPerEntry) {
      bytes[offset--] = v.toByte()
      v = v ushr 8
    }
  }
}

/**
 * Returns the minimum number of bytes required to represent [maxValue] as an unsigned
 * big-endian integer. Returns 1 for maxValue == 0 (a zero-size array still needs 1 byte
 * per entry to be indexable).
 *
 * Mirrors the private `byteSizeForUnsigned` in `HprofInMemoryIndex`.
 */
private fun byteSizeForUnsigned(maxValue: Long): Int {
  if (maxValue == 0L) return 1
  var v = maxValue
  var count = 0
  while (v != 0L) {
    v = v ushr 8
    count++
  }
  return count
}
