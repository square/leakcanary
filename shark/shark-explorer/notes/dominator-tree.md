# Dominator tree

## The bug in the existing implementation

`shark.DominatorTree` (and `shark.ObjectDominators` on top of it) builds dominators incrementally
during BFS using a lowest-common-ancestor update. That's an approximation: when a cross edge is
processed, the parent's dominator may still be stale and is never revisited, leaving the child's
dominator too specific. Retained sizes come out under-attributed.

Minimal failing case — edges `root→a`, `root→d`, `a→b`, `a→c`, `d→e`, `e→b`, `b→c`:

- Correct: `dom(c) = root`
- BFS: `dom(c) = a`, because `b→c` is processed while `dom(b)` is still `a`, before `e→b` raises
  `dom(b)` to `root`

At 10 bytes per object, BFS reports `a.retainedSize = 20` (a + c) where the answer is `10` (a only).

## The implementation to use

`shark.LinkEvalDominatorTree` — exact Lengauer–Tarjan with link-eval (union-find with path
compression), adapted from Android Studio's `perflib` `LinkEvalDominators.kt`
(http://adambuchsbaum.com/papers/dom-toplas.pdf).

**As of 2026-07-28 it is not on `main`.** It lives on branch `worktree-treemap-heapdump` (PR #2800),
tangled up with a larger retained-size and `PathFinder` rewrite. The plan is to lift it out onto its
own PR; until that lands, that branch is the source.

Four phases: iterative DFS numbering → CSR predecessor list → Lengauer–Tarjan steps 2/3/4 → retained
sizes bottom-up.

Details worth not rediscovering:

- A **virtual root at DFN 0** is the parent of every GC root, which makes the multiple-roots case
  fall out for free instead of needing a forest.
- `compress` is **iterative, not recursive**, deliberately: heap graphs have deep object chains and
  the recursive form overflows the stack.
- Large arrays are `VarIntArray`/`VarLongArray` — `ByteArray`-backed with 1–4 bytes per entry chosen
  from the node count. Around 16 M objects that's 3 bytes per DFN, 25% smaller than `IntArray`. The
  all-`0xFF` pattern is the INVALID sentinel and is always greater than any valid DFN.
- Nodes are keyed by `HeapObject.objectIndex` (dense, 0-based, `Int`), which replaces an
  `IdentityHashMap` and avoids a ~20 MB `LongLongScatterMap`.
- Lengauer–Tarjan guarantees `doms[v] < v`, so `1..n` is already a topological order for
  accumulating retained sizes bottom-up. No sort needed.

## Cost

Measured on a 193 MB heap dump (~983 K reachable objects, 2.5 M edges): **~48 MB peak** during CSR
construction, ~43 MB during Lengauer–Tarjan, ~13 MB once only `doms[]` and the id mapping remain.
Only the CSR structure scales with edge count; every other array is sized by node count.

That's affordable on a phone but comfortable on desktop, which is part of why the explorer is a
desktop app.

## Known gaps to fix when integrating

- **`DominatorNode.retainedSize` is `Int`, and phase 4 accumulates into `IntArray`.** Fine for
  Android heaps, overflows above 2 GB. Widen to `Long` for desktop dumps.
- **The result type is `Map<Long, DominatorNode>` with a `List<Long>` of children per node.** For a
  1 M-object heap that's 100+ MB of boxed longs. The UI needs a compact form instead: keep
  `parent[]` / `retainedSize[]` as primitive arrays in DFN space and resolve object ids on demand.
