package leakcanary.internal

import leakcanary.ExcludedRefs
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

val defaultExcludedRefs = ExcludedRefs.builder()
    .clazz(WeakReference::class.java.name)
    .alwaysExclude()
    .clazz(SoftReference::class.java.name)
    .alwaysExclude()
    .clazz(PhantomReference::class.java.name)
    .alwaysExclude()
    .clazz("java.lang.ref.Finalizer")
    .alwaysExclude()
    .clazz("java.lang.ref.FinalizerReference")
    .alwaysExclude()
    .thread("FinalizerWatchdogDaemon")
    .alwaysExclude()
    .thread("main")
    .alwaysExclude()
