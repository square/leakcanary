# The referrer index

`ReferrerIndex` answers "what points at this object", for every object of a heap dump, from memory. It is
built once per open dump and held until the window closes, so **what it holds is what matters** — the peak
while it is being built is a fraction of the dominator tree standing beside it.

It used to be one linked list per object: an int per object for the head, and two ints per reference. It is
now one delta encoded byte slice per object. Everything below was measured on the ten real Android heap
dumps in this repo, with a copy of the linked list version alongside the new one in one JVM, checking that
the two hand back the same referrers before timing either.

## What it holds

| dump | objects | references | linked | encoded | linked B/ref | encoded B/ref | smaller by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `large-dump.hprof` | 387 971 | 514 515 | 5 668 004 | 1 932 355 | 11.02 | 3.76 | 2.93x |
| `safe_iterable_map.hprof` | 333 566 | 564 595 | 5 851 024 | 1 839 201 | 10.36 | 3.26 | 3.18x |
| `gcroot_unknown_object.hprof` | 424 418 | 531 264 | 5 947 784 | 2 085 570 | 11.20 | 3.93 | 2.85x |
| `compose_leak.hprof` | 258 993 | 410 447 | 4 319 548 | 1 447 172 | 10.52 | 3.53 | 2.98x |
| `unloaded_classes-stripped.hprof` | 332 905 | 861 174 | 8 221 012 | 2 014 588 | 9.55 | 2.34 | 4.08x |
| `hashmap_api_25.hprof` | 149 742 | 118 402 | 1 546 184 | 569 903 | 13.06 | 4.81 | 2.71x |
| `leak_asynctask_m.hprof` | 139 818 | 132 083 | 1 615 936 | 608 214 | 12.23 | 4.60 | 2.66x |
| `gc_root_in_non_primary_heap.hprof` | 133 177 | 88 854 | 1 243 540 | 467 028 | 14.00 | 5.26 | 2.66x |
| `leak_asynctask_o.hprof` | 129 757 | 94 145 | 1 272 188 | 473 415 | 13.51 | 5.03 | 2.69x |
| `leak_asynctask_pre_m.hprof` | 45 385 | 57 337 | 640 236 | 220 773 | 11.17 | 3.85 | 2.90x |

Array payload only, both sides, which is all either holds.

**Bytes per reference is the wrong unit for these dumps, and it is worth knowing why.** The encoding
`parttimenerd/hprof-analyzer` uses reaches 1.29 bytes an edge at 1.65 G edges; here the same encoding lands
between 2.3 and 5.3. Nothing is wrong: **these dumps have between 0.67 and 2.59 references per object**, so
the per-object cost dominates. Every object needs a byte saying how long its slice is even when it is empty,
and 14% to 65% of the objects in these dumps have nothing pointing at them at all. Divide the same numbers by
objects rather than references and the spread collapses to 3.5 to 6.1 bytes an object, against the 12 to 15
the linked list held. So **the saving scales with objects, not with references**, and a dump with a denser
graph gets closer to their figure — `unloaded_classes-stripped.hprof`, the densest here at 2.59 references
an object, is the best of the ten at 2.34.

## The smallest heap a session runs in

A minimum-heap ladder over `large-dump.hprof` — open the dump the way a window does, find the leaks, then ask
for the chain from a GC root to 4 000 objects, and take the smallest `-Xmx` the run still completes in:

| | linked | encoded |
| --- | --- | --- |
| Building the index alone | 41 MB | 41 MB |
| A session holding the tree and the index | 106 MB | 98 MB |

Two things follow. **The build peak does not move**, which it shouldn't: the new form is compacted out of the
same linked lists, so both runs peak on the same two `MutableIntList`s and the encoded bytes growing beside
them are lost in the slack those lists already carry. **The session floor drops by 8 MB**, more than the
3.7 MB of arrays, because a collector needs headroom in proportion to what is live.

## Why the offsets are per four objects and not per sixteen

A slice is self delimiting, so the index into `referrers` can be sampled rather than complete: one byte
offset per block of objects, and a lookup steps over the slices of its block to reach its own. The block size
trades bytes against those steps, and it is the one number here that had to be measured rather than reasoned
about. Bytes are exact — the offsets are 4 × ⌈objects / block⌉ — and the time is 200 breadth first walks up
the referrers on `large-dump.hprof`, reaching 1.93 M objects between them:

| objects per block | encoded bytes | walks, linked | walks, encoded |
| --- | --- | --- | --- |
| 16 | 1 641 379 | 59–68 ms | 80–88 ms |
| 8 | 1 738 371 | 51–56 ms | 51–57 ms |
| **4** | **1 932 355** | **55–61 ms** | **46–47 ms** |
| 2 | 2 320 327 | 52–56 ms | 36–39 ms |
| 1 (an offset per object) | 3 096 267 | 60–64 ms | 32–39 ms |

**Sixteen, which is what the Rust analyzer uses, makes a walk a third slower than the linked list it
replaced.** Eight breaks even. Four is 20% *faster* than the linked list while holding a third of what it
held, and that is where this stops — not because the curve stops there, but because it is the first rung
where nothing has been given up. Two and one are faster still, and buying that would mean giving back 0.4 MB
and 1.2 MB of the 3.7 MB this change is *for*, to save fractions of a millisecond per chain on a question
whose budget is a hundred of them.

Their sixteen is the right answer for their problem and not for this one: their offsets are gigabytes at
514 M objects, and their walk is a one-off inside a batch analysis, where this one is what a pointer moving
over a treemap asks for.

The reason four is faster than a linked list at all is that a slice is a run of adjacent bytes where the
linked list was a pointer chase through two int arrays the size of the whole dump — sequential reads against
a cache miss per reference.

Build time is unchanged, 610–646 ms against 611–634 ms on `large-dump.hprof` over four alternating rounds:
the compaction is a sort of a handful of ints per object, and the pass over the dump that dominates it is the
same pass.

**A sweep of every object's referrers is still slower** — 5.6 ms against 3.9 ms sequentially, 11.7 against
8.0 in a shuffled order — because a sweep asks about the third to two thirds of objects nothing points at,
where the linked list reads one int and this steps over a block. Walks are what the app does; sweeps are not.

## The order the referrers come back in did not change, and could not

The linked list handed back **the highest object index first**, because it was built by prepending while
`graph.objects` ran in index order. Sorted ascending is the natural thing to store, and it is what the Rust
analyzer stores, so the first version of this stored it that way — a variable length int can only be read
forwards, so what is stored is what a lookup hands back.

**That reversal is not a cosmetic tie-break.** A breadth first walk up the referrers takes whichever of two
equally distant referrers it sees first, and the search for every way an object is held is greedy — it blocks
the middle of each path it finds so the next path has to go another way. On the heap dump
`HeapExplorerTest.cachedPayloadHeapDump` builds, where a tile holds an image both through its view and
through the request that loaded it, and a cache holds the same image through that request's wrapper:

- highest index first: two chains, `Tile → view → image` and `Cache → wrapper → image`.
- lowest index first: the first walk claims the wrapper, so the second cannot use it — two chains, both from
  the tile, and **the cache never appears as a holder at all**.

So the slices are stored counting **down** from the last object of the heap dump instead, which costs 0.7%
more bytes than counting up (the one absolute value in a slice becomes the distance to the end of the dump
rather than the distance from its start) and hands back exactly the old order. Verified per object rather
than argued: over all ten dumps every object's referrers came back in the same order and with the same
`isLowPriority` bits as the linked list gave, 68 515 of 68 515 multi-referrer objects on `large-dump.hprof`
and every one of the others. The session ladder above is the same check end to end — both implementations
report 2 leak groups, 4 000 chains and 8 664 steps on `large-dump.hprof`.

Which is why there is no leak-fingerprint sweep to go with this change. The sweep over the ten dumps that
`decisions.md` records at 8 of 10 dumps and 12 of 15 leaks is a function of which referrers the index hands
back in which order, and that is byte for byte what it was.

## Two places this deliberately differs from `parttimenerd/hprof-analyzer`

Read `src/pass2/model.rs` (`encode_phase4` and the `INB_BLOCK` comment), `src/vbyte.rs` and
`src/chunkvec.rs` for theirs. Besides the block size above:

**A slice is prefixed with its byte length, where theirs is prefixed with the count of referrers.** A count
makes stepping over a slice a walk of every byte of it, because only the continuation bits say where each
value ends. Nearly every dump here has an object twenty thousand others point at — 23 871 on
`unloaded_classes-stripped.hprof`, 20 533 on `safe_iterable_map.hprof` — and a lookup of anything sharing
that object's block would have had 50 KB to read past. With a length it is a read and an add.

**Nothing is freed as the compaction advances**, where their `chunkvec.rs` hands each 256 MB chunk of the
source back to the allocator as the read cursor passes it. It cannot be done from a linked list: the
references pointing at one object are spread over the whole of it, so the compaction reads the source in
scattered order, not left to right. Making it consumable in order means a first pass over the heap dump to
count what points at each object before anything can be stored — about a quarter added to the time it takes
to open a dump, to lower a peak the ladder above shows is not the binding one. If the peak ever becomes the
constraint, that is the change to make.

**And referrers are not translated into another numbering.** Theirs are turned into dominator pre-order
numbers, which is both what their algorithm needs and why their deltas are so small: pre-order puts a node's
predecessors near each other. Here they stay `HeapObject.objectIndex`, which is the order the dump was
written in, so the deltas are only as small as the dump's own locality makes them. Renumbering would mean a
translation table the size of the dump and a second numbering for every caller to hold, which is a larger
change than this one and worth measuring separately if the bytes ever matter more than they do now.
