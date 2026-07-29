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

**The strength that decides whether an edge is followed is the target's, not the reference's.** A
`WeakReference` whose referent is also held strongly is the common case, and following that edge adds a
second path to an object that was already in the tree: its bytes move up to the two paths' common
ancestor and the treemap reshuffles, revealing nothing. So `StrengthFilteringReferenceReader` asks
`HeapReachability` how the target is classified and follows the edge only if that classification is
being followed. Checking a strength nothing in the dump is reachable at then leaves the treemap exactly
as it was — which, as below, is nearly every strength in nearly every dump.

**`SOFT`, `WEAK` and `PHANTOM` often come out at 0 bytes — but don't treat that as a rule.** The
collection that precedes a dump clears a `Reference` whose referent nothing else was holding, so on
many dumps there's nothing left at those strengths. Measured on `compose_leak.hprof`: 12 MB strong,
exactly 0 B soft/weak/finalizer/phantom, 3 MB uncollected garbage. On a 287 MB production dump: 262 MB
strong, 7 MB uncollected garbage, and 5 finalizer reachable objects totalling 1.1 KB.

**A dump can and does contain objects that are only weakly reachable**, and not as uncollected
garbage: a referent a thread pulled out of a reference, used, and has since let go of is weakly
reachable *again*, and stays that way until the next collection — the collection before the dump saw a
strong path to it and had no reason to clear anything. A weak cache being repeatedly revived that way
is worth seeing, which is the reason the checkboxes exist rather than the explorer just always
following what the collector would. Soft references are cleared only under memory pressure, so a
`SOFT` count of 0 says more about a dump taken at OOM than about heaps in general. `FINALIZER` shows
up when objects are queued for finalization and their `finalize()` hadn't run.

The UI disables a strength with nothing at it and says why, rather than offering a checkbox that can't
change anything, and the log line at load says which strengths have bytes.

Two more reference reader behaviours that surprise you when reading a treemap:

- **A `java.lang.String`'s char array is not a node of its own.** `FieldInstanceReferenceReader`
  skips the `value` field, and `ShallowSizeCalculator` adds the array's bytes to the string instead.
- Primitive wrapper arrays are folded into their array the same way.

## Why big objects sit flat under the root

The first thing a production dump shows is a crowd of rectangles directly under the root — on an 82 MB
Android OOM dump, **129,423 direct children of the root retaining 74 MB**, among them 82 bitmaps worth
17.7 MB. The tempting explanation is JNI: something native holds them, so the root does. It's wrong,
and worth not re-deriving.

**They're shared, not natively held.** Two or more objects reach them by paths that meet only at the
root, so no single owner would free them and the dominator tree attributes their bytes to the whole
heap. Measured on that dump: of the root's direct children, **119,444 (64.8 MB) are not GC roots at
all** — nothing points at them from outside the heap. Every one of the 82 bitmaps came out with an
empty root list. The biggest, 1.1 MB, is held by a `BitmapImage.bitmap` and by a
`BitmapDrawable$BitmapState.mBitmap` under a `RecyclerView`; kill either and the other still holds it.

**Deprioritizing native roots would move under 1%.** The dump has 10,615 GC roots — 9,502 sticky
classes, 579 JNI globals, 200 native stack, 171 JNI locals, 163 thread objects. Objects reachable
*only* from a native root account for 645 KB of the root's 74 MB. So none of "ignore native refs",
"treat native refs to bitmaps specially" or "look at native refs only for objects not already seen"
buys anything here, and each would make the tree lie about a real retention path.

What actually helps is showing the sharing, so `HeapDominatorTreemap.referrersOf` reports every
referrer of an object plus whether the root dominates it, and the details panel says so in words. It
costs a full pass over the dump — a heap dump only records references in the direction they point, so
there is no reverse index — about **1 second per 82 MB**, hence a call of its own that the panel fills
in a moment after the rest rather than part of `summarize`.

**The crowd is drawn one cell per class**, by `groupRootChildrenByClass`, so 129 K rectangles become a
few hundred. Only under the root — everywhere else the children are objects a specific owner holds, and
gathering those would hide the structure rather than reveal it — and only above
`MIN_CHILDREN_TO_GROUP_BY_CLASS`, below which the objects fit and are more informative than their
classes. A class with a single child under the root is left ungrouped, since a group of one says less
than the object.

A group's node id is the *negated* class id, which makes "is this a group" a sign test and needs no
second id space. The cost is that every entry point taking a node id has to ask `classGroup()` first, so
`summarize()` throws with an actionable message instead of failing on a missing key. Class objects group
under `java.lang.Class`, which every real dump has — 9,502 sticky class roots on the 82 MB dump — but a
`dump { }` fixture doesn't, so in tests they stay ungrouped; two of the core tests exist to pin both
cases.

## What logically owns a bitmap: Coil, measured

The dominator answer to "what holds this bitmap" is "nothing on its own", which isn't what someone
looking at a 1.1 MB rectangle wants to know. Traced on the same dump, since re-deriving it means reading
a lot of Coil.

`coil3.memory.RealMemoryCache` is two caches. `RealStrongMemoryCache` is an LRU of decoded images —
`maxSize` is 20% of the heap, 53,687,091 B here, holding 17,652,992 B over 22 images.
`RealWeakMemoryCache` is a `LinkedHashMap` of `WeakReference`s that `entryRemoved` demotes evicted
entries into; empty here, nothing having been evicted yet. Trimming is
`AndroidSystemCallbacks.onTrimMemory`, which holds the `ImageLoader` weakly: at
`TRIM_MEMORY_BACKGROUND` and above it clears the cache, at `TRIM_MEMORY_RUNNING_LOW` and above it
halves it. So the cache explains the bytes but never pins them.

What pins them is the app. `CheckoutGridTile` keeps the `Disposable` returned by `ImageLoader.enqueue`
in a field, and `OneShotDisposable.dispose()` returns early once the job has completed, so that field
holds a completed `Deferred` → `SuccessResult` → `BitmapImage` → the decoded bitmap until the tile loads
another URL. The tile holds the same bitmap the ordinary way as well, via `ImageView.mDrawable` →
`BitmapDrawable$BitmapState.mBitmap`. Coil's own view lifecycle handling doesn't apply, because the
request passes a lambda target rather than a `ViewTarget`. Every one of the 25 tiles in this dump is
attached, so nothing is being wasted — but the retention belongs to the tile, not to the cache.

The shape generalises: an object's holders often divide into a *cache*, which is bounded and clears
itself under pressure, and an *owner*, which is a piece of UI or a job. The dominator tree can't tell
them apart, so it hands the bytes to the root. Making that distinction visible — a curated list of cache
edges, treated like a reachability strength between `STRONG` and `SOFT`, so a cached-but-owned object
nests under its owner — is the open design question, not a decided one.

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
desktop app. For reference, the app sits at ~240 MB RSS with a 24 MB heap dump open, and at ~1.2 GB
with the 287 MB production dump open — 504 K `DominatorNode`s, which is what the section below is
about.

## Remaining inefficiency

`buildNodes` materialises a `Map<Long, DominatorNode>` holding a boxed `List<Long>` of children per
node, which is 100+ MB for a 1 M-object heap. `HeapDominatorTree.immediateDominatorOf` is the
primitive path, but there's no primitive equivalent for retained sizes and children yet. Worth doing
when a heap dump gets big enough to hurt — `TreemapTree` already hides the difference from the UI.

`DominatorNode.retainedSize` was widened from `Int` to `Long` in PR #2871, because the root of the
tree retains the whole reachable heap and wrapped negative past 2 GB.
