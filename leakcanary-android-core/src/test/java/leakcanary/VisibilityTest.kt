package leakcanary

import org.assertj.core.api.Assertions.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility.PUBLIC
import kotlin.reflect.jvm.jvmName

class VisibilityTest {

  private val publicClasses = listOf(
      "leakcanary.OnHeapAnalyzedListener",
      "leakcanary.LeakCanary",
      "leakcanary.DefaultOnHeapAnalyzedListener",
      "leakcanary.AppWatcher",
      "leakcanary.Clock",
      "leakcanary.ObjectWatcher",
      "leakcanary.OnObjectRetainedListener",
      "leakcanary.KeyedWeakReference",
      "leakcanary.GcTrigger",
      "leakcanary.AndroidLeakFixes",
      "shark.SharkLog",
      "shark.HprofWriterHelper",
      "shark.HprofWriter",
      "shark.HprofRecord",
      "shark.Hprof",
      "shark.HprofPrimitiveArrayStripper",
      "shark.PrimitiveType",
      "shark.ValueHolder",
      "shark.OnHprofRecordListener",
      "shark.GcRoot",
      "shark.HprofReader",
      "shark.HeapDumpRule",
      "shark.JvmTestHeapDumper",
      "shark.AndroidBuildMirror",
      "shark.AndroidObjectInspectors",
      "shark.AndroidMetadataExtractor",
      "shark.AndroidReferenceMatchers",
      "shark.AndroidResourceIdNames",
      "shark.Leak",
      "shark.FilteringLeakingObjectFinder",
      "shark.HeapAnalysisFailure",
      "shark.LeakTraceReference",
      "shark.HeapAnalysisException",
      "shark.OnAnalysisProgressListener",
      "shark.ApplicationLeak",
      "shark.MetadataExtractor",
      "shark.ObjectInspector",
      "shark.IgnoredReferenceMatcher",
      "shark.HeapAnalysisSuccess",
      "shark.LibraryLeakReferenceMatcher",
      "shark.ObjectReporter",
      "shark.LibraryLeak",
      "shark.LeakTrace",
      "shark.HeapAnalysis",
      "shark.LeakTraceObject",
      "shark.ReferencePattern",
      "shark.HeapAnalyzer",
      "shark.ObjectInspectors",
      "shark.KeyedWeakReferenceFinder",
      "shark.ReferenceMatcher",
      "shark.AppSingletonInspector",
      "shark.LeakingObjectFinder",
      "shark.HeapField",
      "shark.HprofHeapGraph",
      "shark.internal.hppc.LongLongScatterMap",
      "shark.internal.hppc.LongScatterSet",
      "shark.GraphContext",
      "shark.HeapValue",
      "shark.HeapObject",
      "shark.ProguardMapping",
      "shark.ProguardMappingReader",
      "shark.HeapGraph"
  )


  /**
   * Validates that each field in [LeakCanary.Config] has a matching builder function
   * in [LeakCanary.Config.Builder]
   */
  @Test fun `Everything is internal by default unless explicitly public`() {
    (getClasses("leakcanary") + getClasses("shark"))
        .asSequence()
        .filter { !it.jvmName.contains("\\$".toRegex()) }               // Any nested classes
        .filter { !it.jvmName.contains("\\\$WhenMappings".toRegex()) }  // When mappings
        .filter { !it.jvmName.endsWith("Kt") }                    // ClassNameKt classes
        .filter { !it.jvmName.endsWith("Test") }                  // Test classes
        .filter { it.visibility == PUBLIC }
        .forEach {
          if (!publicClasses.contains(it.jvmName)) {
            fail("Class ${it.jvmName} was declared as PUBLIC, but verification rules " +
                "don't include this class. Update VisibilityTest if you want to keep " +
                "this new class PUBLIC, or convert it to be INTERNAL.")
          }
        }
  }



  /**
   * Scans all classes accessible from the context class loader which belong to the given package and subpackages.
   *
   * @param packageName The base package
   * @return The classes
   * @throws ClassNotFoundException
   * @throws IOException
   */
  private fun getClasses(packageName: String): List<KClass<*>> {
    val classLoader = javaClass.classLoader!!
    val path = packageName.replace('.', '/')
    val resources = classLoader.getResources(path)
    val dirs = mutableListOf<File>()
    while (resources.hasMoreElements()) {
      dirs.add(File(resources.nextElement().file))
    }
    return dirs.map { findClasses(it, packageName) }.flatten()
  }

  /**
   * Recursive method used to find all classes in a given directory and subdirs.
   *
   * @param directory   The base directory
   * @param packageName The package name for classes found inside the base directory
   * @return The classes
   * @throws ClassNotFoundException
   */
  private fun findClasses(
    directory: File,
    packageName: String
  ): List<KClass<*>> {
    val classes = mutableListOf<KClass<*>>()
    if (!directory.exists()) {
      return classes
    }
    val files = directory.listFiles() ?: return classes
    files.forEach { file ->
      if (file.isDirectory) {
        classes.addAll(findClasses(file, packageName + "." + file.name))
      } else if (file.name.endsWith(".class")) {
        // TODO add filtering for $1 in class name here?
        classes.add(
            Class.forName(
                "$packageName.${file.name.substring(0, file.name.length - 6)}",
                false,
                javaClass.classLoader!!).kotlin
        )
      }
    }
    return classes
  }
}