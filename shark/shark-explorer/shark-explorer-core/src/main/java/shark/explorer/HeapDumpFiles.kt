package shark.explorer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * How this app names what it keeps about one heap dump, and how it writes it.
 *
 * **Not beside the heap dump**, which is the tempting place and the wrong one. A dump is opened from
 * wherever it came from — a directory pulled off a device, a temporary file, a read only mount, a checkout
 * of this repository — and writing into all of those means littering some of them and failing on the rest. A
 * directory of this app's own always works and is always found again. See [NoteDirectory] and
 * [LeakStatusFile], which are the two things kept that way.
 */

/**
 * The dump's file name, and the directory it is in as a hash: `large-dump.hprof-1f3a9c0b`.
 *
 * The name first because these are read by people and listed by name, and a hash of the directory after it
 * because two dumps called `large-dump.hprof` from two runs of the same app are two investigations. Which way
 * round to solve that is the whole choice here — a path flattened into a file name would be unreadable and
 * would hit the 255 character limit, and the name alone would silently merge what was written about every
 * dump ever called `heap.hprof`.
 *
 * [normalizedHeapDumpPath] rather than the path as given, because `./heap.hprof` is how a heap dump gets
 * typed on a command line and it is the same dump as `heap.hprof`.
 */
internal fun heapDumpFileKey(heapDumpFile: File): String {
  val heapDump = normalizedHeapDumpPath(heapDumpFile)
  return "${heapDump.name}-${Integer.toHexString(heapDump.parent.orEmpty().hashCode())}"
}

/**
 * One spelling of a heap dump's path, so that two ways of naming one file are one set of notes, one set of
 * statuses, and one dump for a [DeepLink] to be about.
 *
 * Absolute, since what is written about a dump outlives the working directory the app was started in, and
 * with the `.` and `..` steps taken out, since `./heap.hprof` and `heap.hprof` are what the same dump gets
 * called on a command line. Not the canonical path: that resolves symlinks, which means asking the
 * filesystem and getting a different answer once the dump has been deleted.
 */
internal fun normalizedHeapDumpPath(heapDumpFile: File): File = heapDumpFile.absoluteFile.normalize()

/**
 * Puts [text] in [file], through a file of its own and a rename, so that a run killed halfway through a
 * save leaves what was last written rather than half of it.
 *
 * Nothing written is nothing kept: a file whose content has been cleared out is deleted rather than left
 * empty for the next run to find.
 */
internal fun writeWholeFile(
  file: File,
  text: String
) {
  if (text.isEmpty()) {
    if (file.isFile && !file.delete()) {
      throw IOException("Could not delete $file, which is what writing nothing to it is")
    }
    return
  }
  val directory = file.parentFile
  if (!directory.isDirectory && !directory.mkdirs()) {
    throw IOException("Could not create $directory to write $file in")
  }
  val partial = File(directory, "${file.name}$PARTIAL_SUFFIX")
  partial.writeText(text)
  Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

/**
 * A save in flight, which is never a file anything reads.
 *
 * Named here rather than hidden in [writeWholeFile] because a directory these are written into is also a
 * directory something lists — and one that took a save in flight for a file of its own would delete it out
 * from under the run writing it. See [HeapDumpPaths].
 */
internal const val PARTIAL_SUFFIX = ".partial"
