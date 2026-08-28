package shark.dive

/**
 * How firmly an object is held on to, and so how likely its bytes are to be given back: the strengths
 * the `java.lang.ref` package defines, plus [CACHE], [THREAD_LOCAL] and [LOCAL] for the holders that
 * let go on their own. **Declared strongest first**, which [HeapReachability] relies on to walk the heap
 * one strength at a time.
 *
 * An object's strength is the strongest one any path from a GC root gives it, and a path is only as
 * strong as its weakest reference: strong → weak → strong leaves its target weakly reachable. So an
 * object a weak and a phantom reference both point at is weakly reachable, and one a strong and a
 * weak reference both point at is strongly reachable.
 *
 * Every object of a heap dump has one of these, [UNREACHABLE] included, so the strengths partition the
 * dump: the bytes and the object counts of [HeapSizes] add up to the whole thing.
 */
enum class ReachabilityStrength {

  /** Reachable without going through a `java.lang.ref.Reference`. Never reclaimed. */
  STRONG,

  /**
   * Reachable only from a cache that gives its entries up on its own: an LRU that evicts as it fills,
   * and empties itself when Android says memory is short.
   *
   * The first of the three strengths here that aren't the garbage collector's, [THREAD_LOCAL] and
   * [LOCAL] being the others. A cache holds its entries with ordinary strong references, so as far as the
   * GC is concerned this is [STRONG]; what makes it weaker is the cache's own contract, which no heap dump
   * records. So it comes from a curated list of the caches Shark Dive recognizes — see
   * [ReferenceStrengthReader].
   *
   * Ranked below [STRONG] so that an object a cache and something else both hold reads as the something
   * else's: an image the view showing it also holds is the view's, and the cache is why the dominator
   * tree couldn't say so. Ranked above [SOFT] because a cache decides for itself when to let go, where
   * a soft reference is at the mercy of the next collection that needs room.
   */
  CACHE,

  /**
   * Reachable only from a thread's own storage, which is given up when the thread dies.
   *
   * Like [CACHE], a strong reference as far as the garbage collector is concerned, and like a cache, a
   * poor answer to what holds an object that something else holds too: a value in a `ThreadLocal` map is
   * there because a thread put it there, and whatever else points at it is what's using it. Weaker than
   * [CACHE] because a cache lets go when memory runs short and a thread local doesn't, and stronger than
   * [SOFT] because nothing collects it while the thread lives.
   */
  THREAD_LOCAL,

  /**
   * Reachable only from a running method: a local variable, a JNI local reference, a native stack frame,
   * or a monitor a thread is holding.
   *
   * The most transient holder there is — the frame is gone when the method returns — and the least
   * informative, because a local variable is what a thread is doing right now rather than what keeps
   * anything in memory. So an object a field also holds is the field's, and this is what's left for the
   * objects nothing else points at, which are usually the ones being built.
   */
  LOCAL,

  /**
   * Reclaimed when the VM decides it wants the memory back, which is what makes a `SoftReference` a
   * cache rather than a leak.
   */
  SOFT,

  /** Reclaimed at the next collection, whether or not memory is short. */
  WEAK,

  /**
   * Reachable only from the queue of objects whose `finalize()` hasn't run yet, so it survives at
   * least one more collection, and longer if finalization is backed up. Finalizing it can even make
   * it reachable again.
   */
  FINALIZER,

  /**
   * Already finalized and unreachable to the program, held only so that a `PhantomReference` or a
   * `Cleaner` gets enqueued once it's gone.
   */
  PHANTOM,

  /**
   * No path from any GC root reaches it: garbage that hadn't been collected when the heap dump was
   * written, and that the next collection would take.
   *
   * Last, so that it sorts as the weakest of the lot, and so that [HeapReachability]'s walk per strength
   * never queues anything into it — nothing reaches an unreachable object by definition. What's
   * unreachable is what the walk didn't reach.
   */
  UNREACHABLE
}
