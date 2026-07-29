# Dominator tree

## Which implementation to use

`shark.HeapDominatorTree` — exact Lengauer–Tarjan with the simple link-eval, on `main` since
PR #2870. `shark.ObjectDominators.buildDominatorTree` is a thin wrapper that also computes sizes.
`shark.ApproximateDominatorTree` is the on device BFS approximation and must not be used here.

Why exactness was worth it: the approximation updates dominators incrementally during BFS, so when a
cross edge is processed the parent's dominator may still be stale and is never revisited. Retained
sizes come out under-attributed. Minimal failing case — edges `root→a`, `root→d`, `a→b`, `a→c`,
`d→e`, `e→b`, `b→c`: `dom(c)` is `root`, but BFS says `a`, because `b→c` is processed while `dom(b)`
is still `a`.

## How the explorer uses it

`HeapExplorer.treeFor` calls `HeapDominatorTree.buildFor` with a
`StrengthFilteringReferenceReader` wrapping `ActualMatchingReferenceReaderFactory`, then `buildNodes`
with `AndroidObjectSizeCalculator`.

**Which reference matchers go in matters, and it isn't "none".** The matchers do two unrelated jobs,
and only one of them belongs here:

- `JdkReferenceMatchers.REFERENCES` is where **reference strength** lives. `WeakReference.referent`,
  `SoftReference.referent`, `PhantomReference.referent`, `KeyedWeakReference.referent` and the
  `Finalizer` / `FinalizerReference` / `Cleaner` list links are `IgnoredReferenceMatcher`s and nothing
  else marks them. Follow them and a weak reference looks like it retains its referent, and the
  finalizer list looks like one long chain of objects retaining each other. Retained size stops
  meaning anything. **Required.**
- `AndroidReferenceMatchers` mostly suppresses noise so a leak trace stays readable. Those are real
  strong references, so ignoring them here would hide memory that really is retained. **Left out.**
  This is why `ActualMatchingReferenceReaderFactory` and not `AndroidReferenceReaderFactory`: the
  latter also installs `FlatteningPartitionedInstanceReferenceReader`, which surfaces a map's or
  list's internals as direct children of the collection and marks them leaf objects. Good for a leak
  trace, wrong here — a treemap needs every object to be a node exactly once, and the array a
  `HashMap` actually holds has to be a node of its own or its bytes land nowhere.

The strengths the reader follows are a parameter, which is what the checkboxes in the UI change. With
none of them, an object only a weak reference points at is **absent from the treemap entirely**, so
the root doesn't add up to the size of the heap dump; `HeapSizes` accounts for the rest.

Two more reference reader behaviours that surprise you when reading a treemap:

- **A `java.lang.String`'s char array is not a node of its own.** `FieldInstanceReferenceReader`
  skips the `value` field, and `ShallowSizeCalculator` adds the array's bytes to the string instead.
- Primitive wrapper arrays are folded into their array the same way.

## Reachability strength

`HeapReachability` classifies every reachable object as `STRONG`, `SOFT`, `WEAK`, `FINALIZER` or
`PHANTOM`, per `java.lang.ref`. Two things make this more than a flag per reference:

**An object's strength is the strongest of its weakest links.** A path is only as strong as its
weakest reference, and the object gets the best path available — a max-min (widest path) problem, not
a shortest path one. Implemented as one BFS queue per strength, drained strongest first, so an object
found strongly is never revisited weakly. `FINALIZER` sits between `WEAK` and `PHANTOM`: on Android
`java.lang.ref.FinalizerReference extends PhantomReference` and holds its referent through `zombie`
while it's being finalized, and OpenJDK's package-private `java.lang.ref.Finalizer` extends
`FinalReference`.

**Unreachable bytes come out of arithmetic, not a second walk.** `unreachable = totalRaw + totalNative
− Σ computeSize(reached)`, where `totalRaw` sums `byteSize`/`recordSize` over every object in the dump
(from the index, so no IO) and `totalNative` is `AndroidNativeSizeMapper`. This works *because* of the
folding above: the folded objects are in no walk, and their bytes are already inside the referrer's
`computeSize`, so `Σ_reached computeSize == Σ_(reached ∪ folded) rawShallowSize` and they cancel.
Diffing id sets instead double counts them — measurably, 48 bytes on a two object test dump.

Known limitation: a folded array that something *else* also points at is counted both inside its
referrer and as a node of its own, which makes `unreachableByteCount` come out low by that much.

## `ObjectDominators` had the same weak reference bug

`ObjectDominators.buildDominatorTree(graph, ignoredRefs)` used to pass `ignoredRefs` to
`MatchingGcRootProvider` only — its reference reader was hardcoded to
`ActualMatchingReferenceReaderFactory(emptyList())`, so it followed weak references and the finalizer
list links no matter what was passed in. Fixed in PR #2877. Nothing in the repo actually consumed the
sizes (`HprofRetainedHeapPerfTest` only renders the tree as an assertion description), so no frozen
number moved.

## Cost

Measured on a 193 MB heap dump (~983 K reachable objects, 2.5 M edges): **~48 MB peak** while
building the predecessor lists, ~43 MB during Lengauer–Tarjan, ~13 MB once only the dominators and
the id mapping remain. Only the predecessor structure scales with edge count; every other array is
sized by node count.

That's affordable on a phone but comfortable on desktop, which is part of why the explorer is a
desktop app. For reference, the app sits at ~240 MB RSS with a 24 MB heap dump open.

## Remaining inefficiency

`buildNodes` materialises a `Map<Long, DominatorNode>` holding a boxed `List<Long>` of children per
node, which is 100+ MB for a 1 M-object heap. `HeapDominatorTree.immediateDominatorOf` is the
primitive path, but there's no primitive equivalent for retained sizes and children yet. Worth doing
when a heap dump gets big enough to hurt — `TreemapTree` already hides the difference from the UI.

`DominatorNode.retainedSize` was widened from `Int` to `Long` in PR #2871, because the root of the
tree retains the whole reachable heap and wrapped negative past 2 GB.
