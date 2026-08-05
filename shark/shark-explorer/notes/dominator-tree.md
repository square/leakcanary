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

- **Reference strength** is `ReferenceStrengthReader.WEAKENING_REFERENCE_MATCHERS`, one
  `IgnoredReferenceMatcher` per field of `WEAKENING_FIELDS_BY_CLASS_NAME` and nothing else, read off the
  same map that gives those fields their strength: the `referent` and `zombie` of the five reference
  classes, plus the cache and thread local fields. Follow one of those and a weak reference looks like it
  retains its referent, and retained size stops meaning anything. **Required.** A field pattern matches on
  any class of an object's hierarchy, so a `KeyedWeakReference` is covered by the `WeakReference` entry and
  needs none of its own.
- **Deliberately not `JdkReferenceMatchers.REFERENCES`**, which is that list plus the `prev`, `next` and
  `element` links of the lists a runtime keeps its `Finalizer`s, `FinalizerReference`s and `Cleaner`s on,
  ignored there so that a leak trace can't run through the queue of objects waiting to be finalized.
  Those links retain what they point at, and on Android they are the only thing that does: the list hangs
  off one static field, the head through that static and every entry after it through the one before. So
  ignoring them left everything past the head of the list reading as uncollected garbage, and with it
  every object waiting to be finalized or cleaned — on `large-dump.hprof`, 4773 of its 4774
  `FinalizerReference`s and 3392 of its 3553 `Cleaner`s, a fifth of everything the explorer called
  garbage. Pinned by two `HeapReachabilityTest` cases that build such a list and by
  `JvmReferenceStrengthTest`, which dumps a real JVM that has one.
- `AndroidReferenceMatchers` mostly suppresses noise so a leak trace stays readable. Those are real
  strong references, so ignoring them here would hide memory that really is retained. **Left out.**
  This is why `ActualMatchingReferenceReaderFactory` and not `AndroidReferenceReaderFactory`: the
  latter also installs `FlatteningPartitionedInstanceReferenceReader`, which surfaces a map's or
  list's internals as direct children of the collection and marks them leaf objects. Good for a leak
  trace, wrong here — a treemap needs every object to be a node exactly once, and the array a
  `HashMap` actually holds has to be a node of its own or its bytes land nowhere. The explorer *adds*
  those readers instead of swapping them in, so a collection points straight at what it holds and its
  table is still a node: `DataStructureReferenceReader` for the structures Shark knows, and
  `ViewChildReferenceReader` for a `ViewGroup`'s children.

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
many dumps there's nothing left at those strengths. Measured on `compose_leak.hprof`: 15.8 MB strong over
243,672 objects, 8 B weak over one, exactly 0 B soft, finalizer and phantom, 418 KB uncollected garbage
over 15,320 objects. On a 287 MB production dump: 262 MB strong, 7 MB uncollected garbage, and 5 finalizer
reachable objects totalling 1.1 KB — measured before the list links above were followed, so its finalizer
count would be higher today.

**And a dump where none of them is 0 looks like this.** `leak_asynctask_o.hprof`, same repo, 9.0 MB strong
over 120,601 objects: 164 KB phantom over 1,266, 405 B finalizer over 17, 366 B thread local over 14,
140 B soft over 5, 97 B weak over 7, and 255 KB garbage over 7,847. Worth having a dump like that to hand,
because every one of those strengths reading 0 is also what a broken strength computation looks like.

**A dump can and does contain objects that are only weakly reachable**, and not as uncollected
garbage: a referent a thread pulled out of a reference, used, and has since let go of is weakly
reachable *again*, and stays that way until the next collection — the collection before the dump saw a
strong path to it and had no reason to clear anything. A weak cache being repeatedly revived that way is
worth seeing, which is why the tree holds those objects rather than the explorer only following what the
collector would. Soft references are cleared only under memory pressure, so a `SOFT` count of 0 says
more about a dump taken at OOM than about heaps in general. `FINALIZER` shows up when objects are queued
for finalization and their `finalize()` hadn't run.

Every legend row above the view says how firmly, how many bytes and how many objects — `Weak 0 B ·
0 objects` — so a strength with nothing at it says so on its own. `NOTHING_WEAKER` then says why that's
normal, since all four `java.lang.ref` strengths coming out empty reads as a broken computation until you
know. It is one line, with the paragraph above on hover: it is true of most dumps, so it would otherwise be
a paragraph sitting over the map for the whole session to answer a question asked once.

Two more reference reader behaviours that surprise you when reading a treemap:

- **A `java.lang.String`'s char array is not a node of its own.** `FieldInstanceReferenceReader`
  skips the `value` field, and `ShallowSizeCalculator` adds the array's bytes to the string instead.
- The elements of a primitive wrapper array are folded into the array the same way, by
  `ObjectArrayReferenceReader`.

Both are why `ReferenceStrengthReader.foldedObjectIdsOf` exists: **whatever Shark folds has to be
tracked explicitly**, because the object is in no walk and in no tree, and anything counting objects or
bytes over the whole dump would otherwise either miss it or count it twice. See the reachability section.

## Why big objects sit flat under the root

The first thing a production dump shows is a crowd of rectangles directly under the whole heap dump — on an
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

What actually helps is showing the sharing, which is what the paths below the dominator are for: an
object here has at least two of them, and they name the holders that would each have to go. See the
section on those below.

**What the GC roots reach is no level of its own.** `splitRootChildren` sorts the root's children into what
a GC root reaches and what nothing does, and only the second becomes a rectangle: the reachable side's
children *are* the root's children, so the top of the map is the whole heap dump wherever the reader is
rather than a rectangle to click through before anything shows. The garbage ends up a sibling of the objects
that are in memory on purpose, drawn where its size puts it, and only when there is any — a dump whose
garbage was all collected grows no rectangle saying so.

**The crowd is drawn one cell per class**, by `splitRootChildren` and `groupByClass`, so 129 K rectangles
become a few hundred. Only at the top of the tree — everywhere else the children are objects a specific
owner holds, and gathering those would hide the structure rather than reveal it — and only above
`MIN_CHILDREN_TO_GROUP_BY_CLASS`, below which the objects fit and are more informative than their
classes. A class with a single object in it is left ungrouped, since a group of one says less than the
object.

**The pile ids are their own range, at the bottom of the id space**: `UNREACHABLE_NODE_ID` is
`Long.MIN_VALUE` and `GroupIds` counts up from there for the class groups, so `isPileId` is a range check
rather than a sign test — see the note on negative object ids in `treemap-rendering.md`. Sequential rather
than derived from the class, because the same class can have a group at the top of the tree and another one
under the garbage. The cost is that every entry point
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
fields it applies to — one entry today, `coil3.memory.RealStrongMemoryCache$InternalValue.image`. That
list is half of it; the caches whose values no class name can name are the subsection below.

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

### A cache that wraps its values in nothing: `CachedMapValues`

The caches an app is most likely to have are the ones a class name can't describe. `android.util.LruCache`,
Picasso's and Glide's all keep what they cache in a `java.util.HashMap`, so the only thing between the
cache and the value is a `HashMap$Node`, a class every map in the dump shares: weakening its `value` by
class name would weaken every map there is, and weakening the cache's map field would take the table, the
entries and the keys down with it. So which entries are a cache's is read off the heap dump instead —
`CachedMapValues` reads the map of each cache listed in it through `shark.MapEntryReader` — the entry
level half of the walk Shark's own map readers make, which hands back the node as well as the key and the
value — and remembers which node holds which value, one pass over the classes and one over the instances,
30 to 45 ms on `large-dump.hprof`.

**Two references to drop per value, not one**, and this is the part that breaks silently:
`DataStructureReferenceReader` reads a map as the entries you put in it, so a cache's map points straight
at each value as well as through the node holding it. Dropping only the node's leaves the value strongly
held by the map, which is the cache holding it after all — the weakening then changes nothing and the only
symptom is a byte count that didn't move.

Measured on `large-dump.hprof`: 3.9 MB moves out of `STRONG` and reads as the Picasso cache it sits in,
13% of everything that dump held strongly. That cache holds four bitmaps — a 760×1262 and three 126×126,
4,026,992 B of pixels between them — and **two of them move**, 3,899,984 B: the big one and one of the
small ones, which nothing else holds. The other two stay `STRONG` under what shows them, which is the rule
working rather than a gap in it. Nothing moves for `android.util.LruCache` on that dump: all fourteen
instances of it there are the framework's or a library's own, holding prepared statements and typefaces
rather than images.

## An owner beats a bystander: the `OwnerReferences` rule

A view that's part of a hierarchy is held by its parent, and a dominator tree that doesn't know that
scatters a window across whatever happened to be closest to a GC root. Measured on the 82 MB dump before
the rule: all 279 attached views had rival referrers, median ~10 and up to 246, and **170 of the 277
attached child views were dominated by something other than their parent, misplacing 18 MB**. 125 landed
under a `CheckoutGridTile`, 44 flat at the top of the tree. Both `DecorView`s were dominated by the root
itself and retained 4.2 KB and 3.9 KB instead of their windows. The damaging referrers were
`InputMethodManager.mCurRootView` / `mServedView` / `mNextServedView`, `View$AttachInfo.mRootView`,
`PhoneFallbackEventHandler.mView`, `RealWorkflowLifecycleOwner.view`, `ComposeToastServiceImpl.rootView`,
view bindings and `StandardRowSpec$StandardViewHolder.itemView`.

`OwnerReferences` applies a curated list of `OwnerRule`s: a class whose instances something owns, plus the
references that own them — named fields, or the virtual references a class's instances hand out. Six
today — `android.view.View` owned by the `ViewGroup` that reads it as a child (see below) and by
`Activity.mDecor` or `Dialog.mDecor`, `android.app.Activity` owned by the `ActivityThread` that reads it
as one it's running (see below too), and the three Compose ones in the section on Compose below. After: **every one of
the 277 child views is under its parent**, the dialog's `DecorView` is dominated by the
`PartialModalDialog`, and `MainActivity` retains 18 MB under the thread running it, which is the second
largest rectangle under the GC roots.

**A rule is parked, not dropped, and that's the whole design.** The walk in
`HeapReachability.walkFromGcRoots` keeps a second queue per strength and only takes from it once the main
one is empty, so an owner gets every chance to reach the object first, wherever in the heap dump it is.
When no owner turns up, `markLastResortHeld` records it and the rivals count after all. Dropping a rival
outright would be a correctness bug, not a mis-attribution: a detached hierarchy leaked through a
mid-tree child would become unreachable, the explorer would call live objects garbage, and the same rule
in `PathFinder` would make LeakCanary report no leak. Measured proof that it costs nothing: after the
rule the 82 MB dump comes out at exactly the same numbers as before — 80 MB strong, 2.0 MB thread local,
28 B local, 2.6 KB finalizer, 186 KB unreachable.

**Parking is also why a rule needs no check on the state of the object**, which is the thing that looks
missing when you read `RULES`. "The parent owns an *attached* view" needs no attachment test, because a
detached hierarchy isn't held by whatever holds the window: if the parent isn't reachable, no owner
reference reaches the child and the fallback handles it. "Unless the activity is destroyed" needs no
`mDestroyed` test either — `ActivityThread.handleDestroyActivity` sets `mDecor = null` and takes the
`ActivityClientRecord` out of `mActivities`, so the framework has already removed both references the
rules are about. The state a rule seems to need is expressed by which references exist, and a leaked
destroyed activity therefore falls back on whatever is leaking it — which is the one thing you'd want to
read its bytes under.

Two things to know before adding a rule:

- **One owner per construct.** Two owner references are two ways of owning, so the object ends up
  dominated by whatever dominates both. `PhoneWindow.mDecor` was in the list at first and cost the
  activity all 18 MB of its hierarchy: a `JankStatsMonitor` held the window from a GC root of its own, so
  the decor view's dominator became the top of the tree. Pick the one reference you'd want to read the
  bytes under.
- **An owner has to be nameable.** A rule names a field on a class, or a class whose virtual references
  own. An *array* can only be named by its type, which is what the first version of the view rule did —
  every `android.view.View[]` element owned what it pointed at — and a type says nothing about whose
  children the array holds, so an app's own `View[]` of views it merely points at claimed them too. Hence
  the readers below.
- **Name the thing that holds it, not the slot it's in.** `ActivityClientRecord.activity` is perfectly
  nameable and was the activity rule for a while, and it still put a map's `ArrayMap`, `Object[]` and
  record between the thread running a screen and the screen. Nameable is the floor, not the bar: ask what
  you would say holds the object out loud, and if that isn't the reference, the reader has one to add.

Ownership is **not** a `ReachabilityStrength` and can't be: strength is a min over the references of a
path, while owning is a property of the last reference alone. It's a separate binary verdict per object,
gated in the same place — `WeakeningAwareReferenceReader`, which the dominator tree, the referrer index
and the path search all read through, so all three see one edge set.

### The virtual references the explorer adds

A rule can only claim ownership through something nameable, and the framework's own structure often has
nothing to name — so the reader below each rule adds the reference the rule is about, and the dominator
tree is what then collapses the levels the real structure went through. Both are **additive**, both are
read from `ReferenceStrengthReader.retainingReferencesOf`, and both are bounded by the count the framework
keeps rather than by the capacity of the array behind it.

A third reader adds virtual references for a different reason, and is not the explorer's own:
`DataStructureReferenceReader`, Shark's dozen `java.util` and framework structures. A leak through a
`HashMap` reads `HashMap[x]` rather than through its table, its node array and its entry, which is what
makes a chain here the chain a LeakCanary report shows (see `decisions.md`). All of them but
`AndroidReferenceReaders.ANIMATOR_WEAK_REF_SUCKS`, which reads an `ObjectAnimator`'s target through the
`WeakReference` holding it and presents it as a plain field: a useful guess in a leak trace, and here it
would make a weakly held object read as strongly held, which is the one thing the tree can't say.

#### A `ViewGroup` points at its children

`ViewChildReferenceReader` gives a `ViewGroup` one reference per child, named by index and marked virtual,
which is what the view `OwnerRule` claims ownership through. It reads `mChildren` bounded by
`mChildrenCount`, in the shape Shark gives the collections it flattens.

The framework stores children in a `View[]` it grows in chunks, so without this every parent to child link
in a heap dump goes through an array, and the array is the only thing a rule can point at. Measured on the
82 MB dump, the chain from the GC roots down to the bitmap of a list row: **37 levels with 16 `View[]`
among them, 21 levels without**, and the biggest `View[]` went from retaining 18.55 MB — the whole window
— to 411 B. All 96 `View[]` arrays together now retain 4,959 B against 4,859 B of their own bytes.

Three things make it safe, and each one is a decision:

- **Additive, not a swap.** The array is still reached through `mChildren` and is still a node of its own,
  which is what keeps every object of the dump a node exactly once. `ReferenceStrengthReader` appends
  these to what the matching reader returns, which is also why `HeapReachability`'s walk sees them — it
  reads `retainingReferencesOf` directly, and an owner reference the walk didn't follow would be an owner
  that never gets its chance.
- **The dominator tree takes the array out of the middle, not the reader.** Both ways to a child now start
  at the parent — straight there, and through `mChildren` — so the parent dominates it. Nothing had to
  prune an edge for the level to collapse.
- **The `View[]` element no longer owns**, so it's a rival like any other reference: a view in a slot past
  `mChildrenCount` — a dump caught inside `addViewInner`, or a fork that doesn't null the slot it gives up
  — falls back on the array holding it, rather than being attributed to a parent that doesn't hold it.
  That's what `a view in a slot its parent doesn't count is not one of its children` pins.

Byte counts are untouched by all of it, which is the check that no object moved out of the graph: 83.83 MB
strong, 2.05 MB thread local, 28 B local, 2.6 KB finalizer, 190 KB unreachable, 1,019,837 objects, before
and after.

#### An `ActivityThread` points at the activities it's running

`RunningActivityReferenceReader` gives the `ActivityThread` one reference per activity, named `activities`
and marked virtual, which is what the activity `OwnerRule` claims ownership through. It reads the values of
`mActivities` — an `ArrayMap` from an activity's token to its `ActivityClientRecord`, so a key at every even
slot of `mArray` and a record at the odd one after it — bounded by `mSize`, and takes each record's
`activity`.

The rule used to name `ActivityClientRecord.activity` instead, which is a slot of that map rather than the
thing running the activity, and it left the map, its `Object[]` and the record between the thread and every
screen. Measured on `large-dump.hprof`, which is running two activities:

| | Before | After |
| --- | --- | --- |
| `MainActivity` dominator | `ActivityClientRecord` | `ActivityThread` |
| GC root chain to `MainActivity` | 6 steps | **3 steps** |
| Record holding `MainActivity` retains | 2,125,170 B | **381 B** |
| Record holding `PaymentActivity` retains | 14,552 B | **361 B** |
| `mActivities` `ArrayMap` retains | 2,139,795 B | **815 B** |

The two activities' own retained sizes don't move — 2,124,789 B and 11,995 B either way — because a record
retained little beyond the activity in it. What moves is where those bytes are drawn: two screens side by
side under the thread, instead of two piles of map bookkeeping. Chains into an activity's internals get a
step shorter too, and a better first step: the shortest way to the `Bundle`s under `MainActivity` used to
run through a *leaked* `SquareActivity.foot → ArrayList → Object[]`, since that was fewer steps than the map.

Byte counts are again identical before and after, which is the check that no object left the graph:
30,090,032 B strong, 28,302 B thread local, 1,444 B soft, 261 B weak, 9,353 B finalizer, 631,761 B
unreachable, 387,971 objects. Opening the dump costs at most 2% more — median of five steady state opens
2.02 s with the reader against 1.98 s without, ranges overlapping — which is one more sequence
concatenation `retainingReferencesOf` does per object, since the reader itself is one class id
comparison for everything that isn't the activity thread.

`mActivities` has been an `ArrayMap` since Lollipop, seven releases before the oldest one LeakCanary
supports, so the `HashMap` it was before that is deliberately not read: a dump that doesn't have the
`ArrayMap` shape logs a line and reads as a thread running nothing, which leaves its activities held by
whatever points at them.

## A Compose UI is a hierarchy too, and needs more help to read as one

Everything above is about views, and none of it fires on a Compose screen: the tree is `LayoutNode`s, the
children are in a vector rather than a `View[]`, and there is no `mChildrenCount`. So the same two
mechanisms again, plus the ownership rules — measured on a dump of `leakcanary-android-sample` taken off
an API 36 emulator, since every heap dump in the repo predates Compose.

`LayoutNodeChildReferenceReader` is `ViewChildReferenceReader` for a node, and the array it collapses is
further down: `LayoutNode._foldedChildren` → `MutableVectorWithMutationTracking.vector` →
`MutableVector.content` → `LayoutNode[]` → the child. Four objects between two levels of UI, three of them
bookkeeping, bounded by the vector's `size` rather than by the array's length for the same reason the view
reader is bounded by `mChildrenCount`.

**Compose flattens harder than the framework does, in three places.** Each of these was found by asking
`independentPathsBelowDominator` why a `LayoutNode` was a root child:

- `AndroidComposeView.layoutNodes`, a `MutableIntObjectMap` registry of **every** node of the window by
  semantics id. Not a leak and not an accident — it's how a semantics id is resolved — but it means every
  node of a screen is one reference from the view, so the tree of a screen is a *list* until the rule says
  a node belongs to its parent.
- The modifier graph, which is bidirectional and therefore all one piece: a `Modifier$Node` points at its
  `NodeChain` and its `NodeCoordinator`, a coordinator at its `GraphicsLayer` and at the coordinators
  either side of it, a layer at the layers it depends on through `childDependenciesTracker`. One reference
  into any of it reaches the lot. On that dump there were three from outside, each a GC root away:
  `InputMethodManager` → `ViewRootImpl` → `ViewTreeObserver.mOnGlobalFocusListeners` →
  `FocusGroupPropertiesNode`, `SnapshotKt.applyObservers` → `Recomposer` → `CompositionImpl`, and the
  layer dependency graph. So every modifier of every screen was held from outside the UI, and with it
  everything the modifiers draw.
- The composition, which is the next section.

Hence the three rules: a node owned by the parent node's virtual reference, the node at the top of a
window owned by `AndroidComposeView.root`, and a `Modifier$Node` owned by `NodeChain.head` or by the
previous node's `child`. `child` rather than `parent` because a chain has to be owned in one direction, and
outermost first is the order the modifiers were written in.

**What is still flat under the root after all of it**: the `NodeCoordinator`s and `GraphicsLayer`s
themselves, a few hundred bytes each. Their graph has no one entry to name as the owner, and nothing bigger
hangs off them, so there's no rule here worth the risk of a wrong claim.

## What a composable remembers: reading a `SlotTable`

A composition keeps its state in one `Object[]` per window — every `remember` of every composable in it,
every composition local map, every lambda, every `LayoutNode`, side by side — plus an `int[]` describing
that array. So a bitmap one screen remembers is held by the same array as a bitmap five screens away, and
the dominator answer for either is the composition rather than a piece of UI. On the sample app's dump
that array was the single thing holding the images of every screen.

`SlotTableReferenceReader` reads the `int[]` and hands each element out from the node whose composable is
inside. The layout, read off Compose 1.11.4's bytecode and confirmed against a real dump: **five ints a
group** — key, group info, parent anchor, how many groups it contains, where its slots start — laid out in
the order the composables ran, depth first. **Bit 30 of the group info** says the group emitted a node,
and that node is in the group's first slot. A group's slots run to the *next* group's anchor, the last
group's to `slotsSize`, and the array past `slotsSize` is the gap the next write is made through.

Three things about it are decisions rather than mechanics:

- **It replaces the array's references instead of adding to them** — the only reader here that does, and
  the reason is the array's position. A `View[]` sits *under* the parent, so an added reference gives the
  child two paths that both start at the parent and the level collapses. A composition's array sits under
  the composition, nowhere near the UI, so an added reference would give a bitmap a second path from a
  different part of the heap, and its dominator would move *up* to whatever dominates both — the top of
  the tree. The array is still a node holding its own bytes, still reached through the table's own field,
  and every element of it is still reached exactly once.
- **All or nothing per table.** An array only partly handed out from its groups would leave the rest
  reachable through nothing, which the explorer would draw as uncollected garbage. So a table that doesn't
  validate keeps every reference of its own and reads the way it did before this existed, and says so
  through `SharkLog`.
- **What it validates is a dump caught mid-write**, not a corrupt file. A composition being written to has
  its two arrays half moved — the gap is wherever the writer left it and the anchors past it are stored
  negative — so monotonic anchors inside `[0, slotsSize]` and group sizes that stay inside the table are
  what tell a readable table from one caught in the middle. Compose's newer `linkbuffer.SlotTable` keeps
  its slots in chunks rather than one array and is refused for the same reason: it isn't decodable from
  these four fields.

Measured, the two commits together, on that API 36 sample app dump: `AndroidComposeView` retains
**10,368,941 B where it retained 2,619,473 B**, its root `LayoutNode` **7,744,454 B where it retained
8,895 B**, and the `BitmapPainter`s and `AndroidImageBitmap`s of a screen are dominated by a `LayoutNode`
under `AndroidComposeView` → `ComposeView` → … → `DecorView` → `MainActivity` →
`ActivityThread$ActivityClientRecord`. A second dump of the same app: 12,844,587 B and 5,139,099 B.

**An image often lands on an ancestor composable rather than the exact one**, and that is the honest
answer rather than a bug: a value the UI both remembers and draws occupies a slot in more than one group —
a `Box`'s slot 108 and a `Column`'s slot 170 in one case here — so the dominator is the composable
containing both. Read it as "this subtree is why the bytes are here", the same as any shared object.

## What holds an object: one chain, with the dominators on it marked

"What holds this" is answered as a single chain from a GC root down to the object, `rootPathTo`, drawn
like a LeakCanary leak trace. Which of its steps *dominate* the object is marked on it, and that marking
is what the reader gets two different things out of:

- **A step marked a dominator is one every way of holding the object goes through**, so it is what would
  free it. The lowest such step is `dominatorOf`, and it is a *group* rather than an object when nothing
  in particular holds it (`DominatorKind.WHOLE_HEAP_DUMP`, where the tree draws it directly under the root)
  or when nothing holds it at all (`UNCOLLECTED_GARBAGE`, the one pile the top of the tree has).
- **A stretch of unmarked steps between two marked ones is a stretch the chain didn't have to take**, since
  a step every way went through would have been marked too. So that is exactly where "held how else?" has
  an answer: `RootPath.detours()` finds those stretches on a chain, and
  `independentPathsBetween(above, below)` — or `independentPathsFromRoots(below)` for a stretch hanging off
  the head, where what is above is a set of GC roots rather than one object — finds the ways it could have
  run. `RootPath.drawnWith` substitutes a chosen one back in, so the drawing only ever sees one flat chain.

**The name for that path set is "internally vertex-disjoint paths"**, also called independent paths: two
of them share their endpoints and nothing else. The most there can be is the *local vertex connectivity*
of the two ends, by Menger's theorem, and for a stretch found this way there are always at least two — a
single interior vertex common to every path would be a dominator, which is what the stretch being unmarked
rules out. Not "semidominator", which Lengauer–Tarjan already uses for something else.

Two caveats, which `WAYS_HINT` states in the UI rather than leaving to be discovered:

- **A maximum set isn't unique, and finding one is a max flow problem.** `PathSearch` is greedy: it walks
  the referrers up from the object, blocks the middle of each path it finds and walks again. Blocking can
  cost a path further on, so `hasMore` means "the search stopped", not "there are more".
- **Two paths shown apart may still reference each other**, since a path is not told about the references
  leaving it. Parallel chains cross-linked at every layer come out as separate paths.

**The search walks backwards, from the object towards what holds it**, which is the direction a heap dump
can't answer in: it records a reference only in the direction it points. Hence `ReferrerIndex` — one pass
over the dump, kept as a linked list per object (an int per object, two per reference, ~30 MB on a million
object dump). That replaced a full pass per question plus a pass per fork level, which was 2.3 s per click
on the 82 MB dump and 3.4 s when a holder was shared, with a walk in memory. The pass is paid once per
session, lazily, on the first question about paths.

**A path that loops back through the object isn't a way of holding it.** An `AppCompatImageView` has seven
referrers, five of them helpers it created that point back at it. The walk never leaves the object it
started from, so those don't come out as five near-identical chains ending `.mView AppCompatImageView`.

**A direct edge has to be blocked as an edge, not as a vertex.** A source pointing straight at the object
makes a path with no middle to block, so `usedLastStep` blocks that one step: blocking the source instead
would hide the ways it holds the object round through other objects, and blocking nothing at all handed
back the same path until the limit.

**An object a GC root points at has no referrers to walk up from**, so the one path is the object itself,
labelled with the kind of root that reaches it. Nothing inside the heap dump points at it; that is the
whole answer, and a search up the references can't say so.

## Reachability strength

`HeapReachability` classifies **every** object of the heap dump as `STRONG`, `CACHE`, `THREAD_LOCAL`,
`LOCAL`, `SOFT`, `WEAK`, `FINALIZER`, `PHANTOM` or `UNREACHABLE` — `java.lang.ref`'s strengths, the three
above them, and garbage. Making
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

**The weakening rule is one rule, and it covers GC roots too.** `isHeldThrough(objectId, edgeStrength)`
is `edgeStrength == STRONG || strengthOf(objectId) >= edgeStrength`: an edge that weakens the path it's on
counts as a way of holding an object only when nothing holds it more firmly. That's the weak reference
rule, and everything that holds an object only until it lets go of it gets it — a cache that evicts, a
thread local, a stack frame, a finalizer queue. It applies to GC root edges through
`GcRoot.reachabilityStrength()`, which is why a `JavaFrame` doesn't flatten the tree: an app dump has tens
of thousands of stack frame roots, and a thread being inside a method that has an object in a local
variable says nothing about what keeps that object in memory. Nothing is lost by it — every object was
reached at exactly the strength this compares against, so the edges of its strongest path all survive, and
the tree still holds every object of the dump. Withholding a *root* edge is how the dominator tree is told,
since `HeapDominatorTree` is exact and has no notion of a low priority root.

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

## Checked against YourKit: two reasons we called live objects garbage

YourKit opens an Android hprof as it is, no conversion, and on the 82 MB dump it reports 1,863 unreachable
objects and 39,157 pending finalization. This reported 117,997 unreachable objects / 12.0 MB. Nearly all of
that gap was ours, from two causes:

**`HprofIndex.defaultIndexedGcRootTags()` drops most of a real dump's roots.** It leaves out
`ROOT_VM_INTERNAL`, `ROOT_INTERNED_STRING`, `ROOT_UNKNOWN`, `ROOT_FINALIZING`, `ROOT_DEBUGGER`,
`ROOT_REFERENCE_CLEANUP` and `ROOT_UNREACHABLE` — 180 K of this dump's 188 K roots, interned strings and
the runtime's internals, none of which can explain a leak, which is what those defaults are for. An
explorer has to say where every object is held, so `HeapExplorer` opens with `HprofRecordTag.rootTags`
instead. The interned strings alone are ~38 K objects, and they line up with YourKit's "pending
finalization" count.

**ART hangs `$classOverhead` and `$staticOverhead` byte arrays off class objects**, holding what it embeds
in a class — method tables and the like. Shark's `ClassReferenceReader` skips them, along with
`<resolved_references>`, so nothing in the dump pointed at them and they came out as garbage: 66,427
arrays, 10.7 MB, 88% of what was left. `ReferenceStrengthReader.classMetadataReferencesOf` puts them back
as static field references of the class holding them.

Together the garbage went from **117,997 objects / 12.0 MB to 6,029 / 190 KB**, the same order as YourKit's
1,863. What was left was reference queue plumbing — 2,403 `Cleaner`, 2,401 `CleanerThunk`, 763
`FinalizerReference`, 40 `NativeAllocationRegistry` — plus a small tail.

**Most of that plumbing was a third cause, found later**: it was garbage only because the list links its
runtime holds it by were ignored, see the matchers section above. That dump isn't in the repo, so measured
on `large-dump.hprof` instead — garbage from **38,219 objects / 1,020,454 B to 26,466 / 631,761 B**, and
`FINALIZER` from nothing to 72 objects / 9,353 B. On `leak_asynctask_o.hprof`, garbage from 10,771 objects
to 7,847 and `PHANTOM` from nothing to 1,266 objects / 163,917 B.

The String `value` arrays are *not* part of this, though they look like they should be: the unreachable
value arrays matched the unreachable Strings one for one, 19,278 each, so folding had been accounting for
them correctly all along.

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
| Indexing the hprof | 0.44 s |
| Working out what owns what — the two index scans in `OwnerReferences.computeFor` | 0.04 s |
| Working out what's reachable — the per strength walks, the garbage list, the garbage forest | 2.41 s |
| Working out what retains what — `HeapDominatorTree.buildFor` plus `buildNodes` | 2.48 s |
| First `children(root)` — the top level split and the grouping by class | 0.29 s |
| **To the first rectangle on screen** | **≈ 5.7 s** |

Where it went, measured before the two garbage fixes in the section above moved 112 K objects from
`UNREACHABLE` to `STRONG`: 74.0 MB `STRONG` over 901,734 objects, 2.6 KB `FINALIZER` over 106, 12.0 MB
`UNREACHABLE` over 117,997, 86.0 MB total.

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
