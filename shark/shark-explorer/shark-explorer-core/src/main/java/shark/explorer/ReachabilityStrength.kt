package shark.explorer

/**
 * How firmly an object is held on to, and so how likely its bytes are to be given back: the strengths
 * the `java.lang.ref` package defines, plus [CACHE] for the caches the explorer knows about.
 * **Declared strongest first**, which [HeapReachability] relies on to walk the heap one strength at a
 * time.
 *
 * An object's strength is the strongest one any path from a GC root gives it, and a path is only as
 * strong as its weakest reference: strong → weak → strong leaves its target weakly reachable. So an
 * object a weak and a phantom reference both point at is weakly reachable, and one a strong and a
 * weak reference both point at is strongly reachable.
 *
 * An object no path reaches at all is unreachable — garbage that hadn't been collected when the heap
 * dump was written. It has no strength, isn't in any dominator tree, and is only visible in
 * [HeapSizes.unreachableByteCount].
 */
enum class ReachabilityStrength {

  /** Reachable without going through a `java.lang.ref.Reference`. Never reclaimed. */
  STRONG,

  /**
   * Reachable only from a cache that gives its entries up on its own: an LRU that evicts as it fills,
   * and empties itself when Android says memory is short.
   *
   * The one strength here that isn't the garbage collector's. A cache holds its entries with ordinary
   * strong references, so as far as the GC is concerned this is [STRONG]; what makes it weaker is the
   * cache's own contract, which no heap dump records. So it comes from a curated list of the caches the
   * explorer recognizes — see [ReferenceStrengthReader].
   *
   * Ranked below [STRONG] so that an object a cache and something else both hold reads as the something
   * else's: an image the view showing it also holds is the view's, and the cache is why the dominator
   * tree couldn't say so. Ranked above [SOFT] because a cache decides for itself when to let go, where
   * a soft reference is at the mercy of the next collection that needs room.
   */
  CACHE,

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
  PHANTOM
}
