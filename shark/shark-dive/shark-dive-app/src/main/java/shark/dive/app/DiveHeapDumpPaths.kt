package shark.dive.app

import java.io.File
import shark.dive.HeapDumpPaths

/**
 * Where every run of this app writes down the heap dumps it opens, so that a link can find one afterwards.
 *
 * One directory for the whole machine rather than one per run, which is the point of it: the run that wrote a
 * link is usually not the run that follows it. Beside the notes and the verdicts, which are the other things
 * kept about a heap dump between runs.
 *
 * A function rather than an object held somewhere, because this keeps nothing in memory — it is a directory
 * and two ways of reading it — and the three callers are as far apart as a window, a run with no window, and
 * a socket answering another run.
 */
internal fun diveHeapDumpPaths(): HeapDumpPaths = HeapDumpPaths(HEAP_DUMP_PATHS_DIRECTORY)

private val HEAP_DUMP_PATHS_DIRECTORY = File(SHARK_DIVE_DIRECTORY, "heap-dump-paths")
