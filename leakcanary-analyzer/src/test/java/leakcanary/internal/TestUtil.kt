package leakcanary.internal

import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.ClassExclusion
import leakcanary.Exclusion.ExclusionType.ThreadExclusion
import leakcanary.HprofParser
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

internal enum class HeapDumpFile constructor(
  val filename: String
) {
  MULTIPLE_LEAKS("multiple_leaks.hprof"),
}

internal fun fileFromName(filename: String): File {
  val classLoader = Thread.currentThread()
      .contextClassLoader
  val url = classLoader.getResource(filename)
  return File(url.path)
}

val defaultExclusionFactory: (HprofParser) -> List<Exclusion> = {
  listOf(
      Exclusion(
          type = ClassExclusion(WeakReference::class.java.name),
          alwaysExclude = true
      ),
      Exclusion(
          type = ClassExclusion(SoftReference::class.java.name),
          alwaysExclude = true
      ),
      Exclusion(
          type = ClassExclusion(PhantomReference::class.java.name),
          alwaysExclude = true
      ),
      Exclusion(
          type = ClassExclusion("java.lang.ref.Finalizer"),
          alwaysExclude = true
      ),
      Exclusion(
          type = ClassExclusion("java.lang.ref.FinalizerReference"),
          alwaysExclude = true
      ),
      Exclusion(
          type = ThreadExclusion("FinalizerWatchdogDaemon"),
          alwaysExclude = true
      ),
      Exclusion(
          type = ThreadExclusion("main"),
          alwaysExclude = true
      )
  )
}