package shark

import java.io.Closeable

/**
 * A [HeapGraph] that should be closed after being used.
 */
interface CloseableHeapGraph : HeapGraph, Closeable