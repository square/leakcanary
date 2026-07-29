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

`HeapTreemap` calls `HeapDominatorTree.buildFor` with `AndroidReferenceReaderFactory`, then
`buildNodes` with `AndroidObjectSizeCalculator`.

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

Consequence worth stating plainly: an object only a weak reference points at is **absent from the
treemap entirely**, so the root doesn't add up to the size of the heap dump. Showing that space as its
own region is a wanted feature, not done yet — see below for why it's not a one liner.

Two more reference reader behaviours that surprise you when reading a treemap:

- **A `java.lang.String`'s char array is not a node of its own.** `FieldInstanceReferenceReader`
  skips the `value` field, and `ShallowSizeCalculator` adds the array's bytes to the string instead.
- Primitive wrapper arrays are folded into their array the same way.

## `ObjectDominators` has the weak reference bug

`ObjectDominators.buildDominatorTree(graph, ignoredRefs)` passes `ignoredRefs` to
`MatchingGcRootProvider` only — its reference reader is hardcoded to
`ActualMatchingReferenceReaderFactory(emptyList())`. So it follows weak references no matter what is
passed in, and `shark-cli`'s dominator output over-attributes retained size because of it. Not fixed
here because the explorer doesn't go through that API.

## Showing what isn't strongly reachable

The obvious implementation — sum the sizes of every object in the dump that isn't a key of the
dominator tree — is **wrong**, and measurably so: it comes out 48 bytes too high on a two object test
dump. The folded objects above (a string's char array) are in no dominator tree either, and their
bytes are already counted inside the object they were folded into, so they get counted twice.

The version that works: walk reachability twice, once with the strength matchers and once without,
and diff the two id sets. Folded objects are absent from both walks, so the diff is exactly the
weakly, softly and phantom reachable objects. Costs a second DFS over the whole graph. Truly
unreachable garbage still needs the folding problem solved to be measured.

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
