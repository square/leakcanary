package shark

import java.io.File

fun String.classpathFile(): File {
  val classLoader = Thread.currentThread()
      .contextClassLoader
  val url = classLoader.getResource(this)!!
  return File(url.path)
}