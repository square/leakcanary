package shark.explorer

/**
 * How strongly the garbage collector holds on to an object, as the `java.lang.ref` package defines
 * it. **Declared strongest first**, which [HeapReachability] relies on to walk the heap one strength at
 * a time.
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
