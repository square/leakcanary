## How firmly an object is held

Every object has one strength, the strongest way anything holds it, and the map is coloured by it.

Strongest first, which is also the order the legend above the map lists them in:

- **Strong** — reachable without going through a `java.lang.ref.Reference`. Never reclaimed while it stays
  that way, so this is most of a heap dump.
- **Cache** — reachable only from a cache that gives its entries up on its own: an LRU that evicts as it
  fills, and empties itself when Android says memory is short. The garbage collector sees an ordinary strong
  reference here; what makes it weaker is the cache's own contract, which no heap dump records, so this comes
  from a list of the caches Shark Dive recognizes.
- **Thread local** — reachable only from a thread's own storage, which is given up when the thread dies.
- **Local** — reachable only from a running method: a local variable, a JNI local reference, a native stack
  frame, or a monitor a thread holds. The most transient holder there is, and the least informative — a local
  variable is what a thread is doing right now rather than what keeps anything in memory.
- **Soft** — reclaimed when the virtual machine decides it wants the memory back, which is what makes a
  `SoftReference` a cache rather than a leak.
- **Weak** — reclaimed at the next collection, whether or not memory is short.
- **Finalizer** — reachable only from the queue of objects whose `finalize()` hasn't run yet, so it survives
  at least one more collection, and longer if finalization is backed up. Finalizing it can even make it
  reachable again.
- **Phantom** — already finalized and out of the program's reach, held only so that a `PhantomReference` or a
  `Cleaner` gets enqueued once it is gone.
- **Unreachable** — no path from any GC root reaches it: garbage that hadn't been collected when the dump was
  written, and that the next collection would take.

**A path is only as strong as its weakest reference**, and an object's strength is the strongest path there
is to it. So strong → weak → strong leaves its target weakly reachable, and an object a weak and a strong
reference both point at is strongly reachable.

Three of these are not the garbage collector's — Cache, Thread local and Local are strong references as far
as it is concerned. They are here because "what holds this?" is a question about the app rather than about
the collector: an image the view showing it also holds is the view's, and the cache is only why the dominator
tree couldn't say so.

Every object is in exactly one of the nine, so the bytes down the legend add up to the whole heap dump. Why
the weakest few usually read 0 B is its own topic, *Reachable only through a reference*.
