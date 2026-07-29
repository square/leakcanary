package shark

import java.io.Closeable

/**
 * A [HeapGraph] that should be closed after being used.
 *
 * Reads from a [HeapGraph] can happen on several threads at the same time, but [close] should only
 * be called once no thread is reading from it anymore: reading from a closed graph fails.
 */
interface CloseableHeapGraph : HeapGraph, Closeable