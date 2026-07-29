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

`HeapExplorer.open` calls `HeapDominatorTree.buildFor` with a `WeakeningAwareReferenceReader` wrapping
`ActualMatchingReferenceReaderFactory`, then `buildNodes` with `AndroidObjectSizeCalculator`. One tree
per open heap dump, built once, holding every object of the dump.

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

**The strength that decides whether a weakening edge is followed is the target's, not the reference's.**
A `WeakReference` whose referent is also held strongly is the common case, and following that edge adds a
second path to an object that was already in the tree: its bytes move up to the two paths' common
ancestor and the treemap reshuffles, revealing nothing. So `WeakeningAwareReferenceReader` asks
`HeapReachability` how the target came out and follows a weakening edge only when nothing strong reaches
it. Every weakly reachable object is therefore in the tree, under the reference that is the only thing
holding it, and nothing else moves.

None of that is a parameter and the reachability checkboxes in the UI don't change it — the tree covers
the whole heap dump and they only pick which strengths are drawn in colour. See `decisions.md`.

**`SOFT`, `WEAK` and `PHANTOM` often come out at 0 bytes — but don't treat that as a rule.** The
collection that precedes a dump clears a `Reference` whose referent nothing else was holding, so on
many dumps there's nothing left at those strengths. Measured on `compose_leak.hprof`: 12 MB strong,
exactly 0 B soft/weak/finalizer/phantom, 3 MB uncollected garbage. On a 287 MB production dump: 262 MB
strong, 7 MB uncollected garbage, and 5 finalizer reachable objects totalling 1.1 KB.

**A dump can and does contain objects that are only weakly reachable**, and not as uncollected
garbage: a referent a thread pulled out of a reference, used, and has since let go of is weakly
reachable *again*, and stays that way until the next collection — the collection before the dump saw a
strong path to it and had no reason to clear anything. A weak cache being repeatedly revived that way is
worth seeing, which is why the tree holds those objects rather than the explorer only following what the
collector would. Soft references are cleared only under memory pressure, so a `SOFT` count of 0 says
more about a dump taken at OOM than about heaps in general. `FINALIZER` shows up when objects are queued
for finalization and their `finalize()` hadn't run.

Every legend row in the top bar says how firmly, how many bytes and how many objects — `Weak 0 B ·
0 objects` — so a strength with nothing at it says so on its own. `NOTHING_WEAKER` then says why that's
normal, since all four `java.lang.ref` strengths coming out empty reads as a broken computation until you
know.

Two more reference reader behaviours that surprise you when reading a treemap:

- **A `java.lang.String`'s char array is not a node of its own.** `FieldInstanceReferenceReader`
  skips the `value` field, and `ShallowSizeCalculator` adds the array's bytes to the string instead.
- The elements of a primitive wrapper array are folded into the array the same way, by
  `ObjectArrayReferenceReader`.

Both are why `ReferenceStrengthReader.foldedObjectIdsOf` exists: **whatever Shark folds has to be
tracked explicitly**, because the object is in no walk and in no tree, and anything counting objects or
bytes over the whole dump would otherwise either miss it or count it twice. See the reachability section.

## Why big objects sit flat under the root

The first thing a production dump shows is a crowd of rectangles directly under the GC roots — on an
82 MB Android OOM dump, **129,423 of them retaining 74 MB**, among them 82 bitmaps worth 17.7 MB. The
tempting explanation is JNI: something native holds them, so the root does. It's wrong, and worth not
re-deriving.

(Those counts were measured before `CACHE` and before the garbage went into the tree, both of which
moved objects around; the current tree has 221,180 children under the virtual root, the garbage entry
points included. The reasoning below is what hasn't changed.)

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

**The crowd is drawn one cell per class**, by `splitRootChildren` and `groupByClass`, so 129 K rectangles
become a few hundred. Only at the top of the tree — everywhere else the children are objects a specific
owner holds, and gathering those would hide the structure rather than reveal it — and only above
`MIN_CHILDREN_TO_GROUP_BY_CLASS`, below which the objects fit and are more informative than their
classes. A class with a single object in it is left ungrouped, since a group of one says less than the
object.

**Every node id below zero stands for a pile of objects rather than for one**, which makes "is this a
group" a sign test and needs no second id space: `GC_ROOTS_NODE_ID` and `UNREACHABLE_NODE_ID` are −1 and
−2, and `GroupIds` counts down from −3 for the class groups. Sequential rather than derived from the
class, because the same class can have a group under either half. The cost is that every entry point
taking a node id has to ask `groupOrNull()` first, so `summarize()` throws with an actionable message
instead of failing on a missing key. Class objects group under `java.lang.Class`, which every real dump
has — 9,502 sticky class roots on the 82 MB dump — but a `dump { }` fixture doesn't, so in tests they
stay ungrouped; two of the core tests exist to pin both cases.

**`HeapGraph.findClassByName` is not a lookup**, it's two linear scans over every string in the dump, and
grouping is the one place that's tempting to call it per object. Doing it per class object cost 49 s and
doing it per primitive array — via `HeapPrimitiveArray.arrayClass`, which calls it internally — cost
another 6.8 s, so `HeapDominatorTreemap` memoizes it in `classIdByName` and reads `instanceClassId` /
`arrayClassId` directly where they exist.

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
them apart, so it hands the bytes to the root — which is what `ReachabilityStrength.CACHE` is for.

## A cache is not an owner: the `CACHE` strength

The fix for the above is to stop treating a cache's reference as retaining. `CACHE` ranks between
`STRONG` and `SOFT`, and `ReferenceStrengthReader.CACHE_FIELDS_BY_CLASS_NAME` is the curated list of
fields it applies to — one entry today, `coil3.memory.RealStrongMemoryCache$InternalValue.image`.

The mechanics fall out of the weak reference machinery that was already there, because the rule the
explorer wants is exactly the rule for a weak reference: **the target's strength decides**. A weakening
edge is followed only when nothing stronger reaches the object, so an image a tile also holds is the
tile's and the cache edge is dropped, while an image nothing else holds stays in the tree, dominated by
the cache entry and drawn at `CACHE`. Which is what "only keep the cache reference if nothing else is
there" means, without a line of special casing in the dominator tree.

Measured on the 82 MB dump: the three 1 MB bitmaps stop being root children and nest under
`CheckoutGridTile` → `RecyclerView`, which becomes the biggest child of the root at 17 MB. `CACHE` comes
out at 0 B there, since every cached image is also on screen — the strength changed where the bytes are
attributed, not how many are reachable.

Two things to know before adding an entry to that list:

- **The class and field names are matched literally**, so a wrong guess doesn't fail, it silently does
  nothing. Add one only against a heap dump that has it. An obfuscated dump needs its mapping applied
  first, for the same reason.
- **Cut as low as the value a cache entry wraps**, not at the cache itself. Cutting at
  `InternalValue.image` leaves the map, the entries and the size bookkeeping strongly held by the cache,
  where they belong, and moves only the images.

## Spelling out what holds an object

`HeapDominatorTreemap.holdingPathsTo` answers "what is keeping this in memory" with a chain of
references per way the object is held, which is what the tree leaves open. Three things about how it
gets there, none of them obvious from the code alone:

**Where the walk forks comes out of the dominator tree, and nowhere else.** Every path to an object with
a dominator goes through that dominator — that's what dominance is — so one chain says everything there
is to say about how it's held, however many references point at it. Only an object the *root* dominates
is held in ways that differ, and only those fork, the object asked about included.

That last clause is the part that was wrong for a while. Forking at the object unconditionally listed
three holders for a bitmap the treemap draws inside a single owner, which reads as the panel
contradicting the picture; the two have to give the same answer, and dominance is the answer. It's also
what makes an owned object cost the walk from the GC roots and nothing more, since every level that forks
costs a pass over the heap dump.

**What every path goes through is the dominator, not the deepest shared step.** Counting how many shown
paths a step is on says where the paths join, but not that the object is held through it: each path is
only the *shortest* way to one holder, so a step they share may still have ways round it that were never
walked. `withPathCounts` therefore asks the tree — the deepest step on the shortest path that dominates
the object. On the bitmap under a tile, both chains run through the view and the image respectively,
deeper than the tile, and the tile is still the only correct answer.

**A path that loops back through the object isn't a way of holding it.** An `AppCompatImageView` has
seven referrers, five of which are helpers it created that point back at it. Following those produced
five near-identical chains ending `.mView AppCompatImageView`. Dropping any path that already went
through the object cut it to the two real ones.

**Shark's path finder hides a target that another target holds**, because
`PrioritizingShortestPathFinder` treats targets as leaves: ask it for a tile and the view that tile holds
and it reports the tile only. Hence a second walk for whatever went missing, without the targets that
swallowed it.

Cost on the 82 MB dump: **~2.3 s** for the bitmap above (one referrer pass plus the walk from the GC
roots), **~3.4 s** when a holder is shared as well and a second pass is needed. Worth knowing before this
moves anywhere near being computed eagerly: a reverse index built once per tree would make the passes
instant, at the price of an edge-sized structure — the same predecessor lists `HeapDominatorTree` builds
and throws away. The walk from the GC roots is the other ~1.7 s and wouldn't be helped by it.

A referrer that holds the object without keeping it in memory is on none of the chains, which reads as a
bug unless the panel says so: a bitmap's referrers are its `Cleaner`, phantom and therefore on no path,
plus the two that hold it. Hence `Referrer.weakeningStrength` and `ObjectReferrers.holdingReferrerCount`.

## Reachability strength

`HeapReachability` classifies **every** object of the heap dump as `STRONG`, `CACHE`, `SOFT`, `WEAK`,
`FINALIZER`, `PHANTOM` or `UNREACHABLE` — `java.lang.ref`'s strengths, the one above, and garbage. Making
garbage the last strength rather than a leftover is what makes the breakdown a partition: object counts
add up to the dump's object count exactly, and byte counts to its byte count, so a UI can put a number
next to each one and have them sum. Three things make this more than a flag per reference:

**An object's strength is the strongest of its weakest links.** A path is only as strong as its
weakest reference, and the object gets the best path available — a max-min (widest path) problem, not
a shortest path one. Implemented as one BFS queue per strength, drained strongest first, so an object
found strongly is never revisited weakly. `FINALIZER` sits between `WEAK` and `PHANTOM`: on Android
`java.lang.ref.FinalizerReference extends PhantomReference` and holds its referent through `zombie`
while it's being finalized, and OpenJDK's package-private `java.lang.ref.Finalizer` extends
`FinalReference`.

**The garbage is enumerated, not subtracted.** It used to come out of arithmetic —
`total − Σ computeSize(reached)` — which is a byte count and nothing else, and the tree needs the objects.
So `markUnreachable` passes over `graph.objects` and takes what no walk reached, and `rootsOf` reduces
that to the objects a walk of the garbage has to start from: the ones no other piece of garbage points at.
`UncollectedGarbageGcRootProvider` hands those to `HeapDominatorTree` as `GcRoot.Unreachable`, so garbage
nests under whatever was holding it and the whole dump is one tree.

**A garbage cycle has no entry point**, and that's the part to get right: a doubly linked list nothing
points at any more has every node pointed at by another, so "the ones nothing points at" finds none of
them and a walk from the others never arrives. Hence the second loop in `rootsOf` — walk from the entry
points found, then pick the first object that walk missed, over and over. Which node of a cycle that is,
is arbitrary; nothing in the heap dump makes one of them the owner.

**Folding has to be tracked, because the arithmetic no longer hides it.** A folded object is reached by no
walk, so it would land in the garbage list, get a rectangle of its own, and have its bytes counted twice —
once inside its holder and once as a node. `foldedObjectIndexes` is the `BitSet` that stops that, and
`fold()` also gives the folded object its holder's strength so the counts stay a partition. Note the
second pass in `markUnreachable`: a candidate can turn out to be folded into a candidate that appears
later in the same pass, so the decision can't be made on first sight.

The one thing that stays approximate is `totalByteCount`, which sums raw shallow sizes over every object:
a folded object that something *else* also points at is counted once inside its holder and once as a node,
so the per-strength byte counts come out a little under the total. `HeapSizes` says so in its KDoc.

## `ObjectDominators` had the same weak reference bug

`ObjectDominators.buildDominatorTree(graph, ignoredRefs)` used to pass `ignoredRefs` to
`MatchingGcRootProvider` only — its reference reader was hardcoded to
`ActualMatchingReferenceReaderFactory(emptyList())`, so it followed weak references and the finalizer
list links no matter what was passed in. Fixed in PR #2877. Nothing in the repo actually consumed the
sizes (`HprofRetainedHeapPerfTest` only renders the tree as an assertion description), so no frozen
number moved.

## Time to open a heap dump

Measured on an 82 MB Android OOM dump, 1,019,837 objects, on a laptop:

| Step | |
| --- | --- |
| Indexing the hprof | 0.54 s |
| Working out what's reachable — the per strength walks, the garbage list, the garbage forest | 2.56 s |
| Working out what retains what — `HeapDominatorTree.buildFor` plus `buildNodes` | 2.61 s |
| First `children(root)` — the top level split and the grouping by class | 0.21 s |
| **To the first rectangle on screen** | **≈ 5.9 s** |

Where it went: 74.0 MB `STRONG` over 901,734 objects, 2.6 KB `FINALIZER` over 106, 12.0 MB `UNREACHABLE`
over 117,997, 86.0 MB total.

Two things made that number much worse before they were found, both of them a `findClassByName` in a
per-object loop: 49 s once, then 6.8 s once. Neither showed up as an obviously hot function — it's a
method on `HeapGraph` that reads like a map lookup. **Time the steps before optimizing anything else
here**; the walks and Lengauer–Tarjan are within a factor of two of each other and neither is the outlier
you'd expect.

## Memory cost

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
