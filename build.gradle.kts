import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.net.URI
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

buildscript {
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
  }
  dependencies {
    classpath(libs.gradlePlugin.android)
    classpath(libs.gradlePlugin.kotlin)
    classpath(libs.gradlePlugin.dokka)
    classpath(libs.gradlePlugin.mavenPublish)
    classpath(libs.gradlePlugin.detekt)
    classpath(libs.gradlePlugin.sqldelight)
    classpath(libs.gradlePlugin.ksp)
    classpath(libs.gradlePlugin.hilt)
    classpath(libs.gradlePlugin.composeCompiler)
    classpath(libs.kotlin.abi.tools)
  }
}

// This plugin needs to be applied to the root projects for the dokkaGfmCollector task we use to
// generate the documentation site.
apply(plugin = "org.jetbrains.dokka")

repositories {
  // Needed for the Dokka plugin.
  gradlePluginPortal()
}

// Config shared for all subprojects
subprojects {

  repositories {
    google()
    mavenCentral()
    //    maven {
    //      url 'https://oss.sonatype.org/content/repositories/snapshots/'
    //    }
    //    mavenLocal()
  }

  apply(plugin = "io.gitlab.arturbosch.detekt")

  tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(
      listOf(
        "-Xlint:all",
        "-Xlint:-serial",
        "-Xlint:-deprecation",
        "-Xlint:-options",     // Silences Java 8 obsolete warning
        // "-Xlint:-this-escape", // Silences Java 21+ leaking 'this' warning (Java 21+ only) (Currently we build with J17 so not needed)
        // espresso-core classes say they're compiled with 51.0 but contain 52.0 attributes.
        // warning: [classfile] MethodParameters attribute introduced in version 52.0 class files is ignored in version 51.0 class files
        // "-Werror"
      )
    )
  }

  tasks.withType<Test> {
    testLogging {
      exceptionFormat = TestExceptionFormat.FULL
      showCauses = true
      showExceptions = true
      showStackTraces = true
    }
  }

  dependencies {
    "detektPlugins"(rootProject.libs.detekt.formatting)
  }

  extensions.configure<DetektExtension> {
    config = rootProject.files("config/detekt-config.yml")
    parallel = true
    reports {
      xml.enabled = false
    }
  }

  pluginManager.withPlugin("java") {
    tasks.named("check") { dependsOn("detekt") }
    tasks.named("assemble") { dependsOn(rootProject.tasks.named("installGitHooks")) }
    tasks.named("clean") { dependsOn(rootProject.tasks.named("installGitHooks")) }
  }
}

// Config shared for subprojects except leakcanary-deobfuscation-gradle-plugin
configure(subprojects.filter {
  it.name !in listOf("leakcanary-deobfuscation-gradle-plugin")
}) {
  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }
  }
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
      }
    }
  }
  // Android modules don't apply the java or Kotlin JVM plugin, and the Android Gradle Plugin
  // defaults to Java 11. Setting compileOptions also aligns the Kotlin jvmTarget.
  plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
    }
  }
  plugins.withId("com.android.application") {
    extensions.configure<ApplicationExtension> {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
    }
  }
}

// Config shared for subprojects except apps
configure(subprojects.filter {
  it.name !in listOf("leakcanary-app", "leakcanary-android-sample")
}) {
  // Note: to skip Dokka on some projects we could add it individually to projects we actually
  // want.
  apply(plugin = "org.jetbrains.dokka")
  group = property("GROUP").toString()
  version = property("VERSION_NAME").toString()

  tasks.withType<DokkaTask> {
    dokkaSourceSets.configureEach {
      reportUndocumented.set(false)
      displayName.set(null as String?)
      platform.set(org.jetbrains.dokka.Platform.jvm)

      perPackageOption {
        // will match all .internal packages and sub-packages
        matchingRegex.set("(.*\\.internal.*)")
        suppress.set(true)
      }
      perPackageOption {
        // BuildConfig files
        matchingRegex.set("com.squareup.leakcanary\\..*")
        suppress.set(true)
      }
      skipDeprecated.set(true)
      externalDocumentationLink {
        url.set(URI("https://square.github.io/okio/2.x/okio/").toURL())
      }
      externalDocumentationLink {
        url.set(URI("https://square.github.io/moshi/1.x/moshi/").toURL())
      }
    }
  }

  pluginManager.withPlugin("com.vanniktech.maven.publish") {
    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
      signAllPublications()
    }
  }
}

// We use JetBrain's Kotlin Binary Compatibility Validator to track changes to our public binary
// APIs.
// When making a change that results in a public ABI change, the checkKotlinAbi task will fail. When
// this happens, run ./gradlew updateKotlinAbi to generate updated *.api files, and add those to
// your commit.
// See https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html
// Only modules that ship an API meant to be consumed by others are tracked. Apps, samples, command
// line tools and the internal plumbing of the LeakCanary UI app are not.
val modulesWithoutTrackedApi = listOf(
  "leakcanary-android-sample",
  "leakcanary-app",
  "leakcanary-app-aidl",
  "leakcanary-app-db",
  "leakcanary-app-service",
  "shark-cli",
  "shark-hprof-test",
  "shark-test",
)
configure(subprojects.filter {
  it.name !in modulesWithoutTrackedApi
}) {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      @OptIn(ExperimentalAbiValidation::class)
      abiValidation()
    }
  }
  // The Kotlin Gradle plugin only wires up ABI validation for the JVM plugin, so Android library
  // modules get an equivalent pair of tasks built on the same engine (org.jetbrains.kotlin:abi-tools),
  // producing the exact same dump format. Delete this once KT-83410 ships.
  // See https://youtrack.jetbrains.com/issue/KT-83410
  plugins.withId("com.android.library") {
    registerAndroidAbiValidationTasks()
  }
}

/**
 * Registers `checkKotlinAbi` and `updateKotlinAbi` for an Android library module, mirroring the
 * tasks the Kotlin Gradle plugin registers for JVM modules. The ABI of the release variant is
 * dumped with the same engine the Kotlin Gradle plugin uses, so the dump format is identical.
 */
fun Project.registerAndroidAbiValidationTasks() {
  val dumpFileName = "$name.api"
  val referenceDumpFile = layout.projectDirectory.file("api/$dumpFileName")

  val dumpTask = tasks.register<AndroidAbiDumpTask>("internalDumpKotlinAbi") {
    description = "Dumps the public Application Binary Interface (ABI) into a file in the build directory."
    dumpFile.set(layout.buildDirectory.file("abi-validation/$dumpFileName"))
  }

  val checkTask = tasks.register<AndroidAbiCheckTask>("checkKotlinAbi") {
    description = "Checks that the public Application Binary Interface (ABI) of the current " +
      "project code matches the reference dump file"
    referenceDump.from(referenceDumpFile)
    actualDump.set(dumpTask.flatMap { it.dumpFile })
    projectPath.set(this@registerAndroidAbiValidationTasks.path)
  }
  tasks.named("check") {
    dependsOn(checkTask)
  }

  tasks.register<AndroidAbiUpdateTask>("updateKotlinAbi") {
    description = "Writes the public Application Binary Interface (ABI) of the current code to " +
      "the reference dump file."
    actualDump.set(dumpTask.flatMap { it.dumpFile })
    referenceDump.set(referenceDumpFile)
  }

  val androidComponents = extensions.getByType<LibraryAndroidComponentsExtension>()
  androidComponents.onVariants(androidComponents.selector().withName("release")) { variant ->
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
      .use(dumpTask)
      .toGet(ScopedArtifact.CLASSES, AndroidAbiDumpTask::classJars, AndroidAbiDumpTask::classDirs)
  }
}

@CacheableTask
abstract class AndroidAbiDumpTask : DefaultTask() {

  @get:Classpath
  abstract val classJars: ListProperty<RegularFile>

  @get:Classpath
  abstract val classDirs: ListProperty<Directory>

  @get:OutputFile
  abstract val dumpFile: RegularFileProperty

  @TaskAction
  fun dump() {
    // The ABI printer expects the individual class files, not the directories holding them.
    val classfiles = classJars.get().map { it.asFile } +
      classDirs.get().flatMap { dir ->
        dir.asFile.walkTopDown().filter { it.isFile && it.extension == "class" }
      }
    val file = dumpFile.get().asFile
    file.parentFile.mkdirs()
    file.bufferedWriter().use { writer ->
      AbiTools.getInstance().printJvmDump(writer, classfiles, AbiFilters.EMPTY)
    }
  }
}

@CacheableTask
abstract class AndroidAbiCheckTask : DefaultTask() {

  /**
   * Declared as a file collection rather than an input file so that a missing reference dump is
   * reported by the task action instead of failing while Gradle snapshots the inputs.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val referenceDump: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val actualDump: RegularFileProperty

  @get:Input
  abstract val projectPath: Property<String>

  @TaskAction
  fun check() {
    val reference = referenceDump.singleFile
    val actual = actualDump.get().asFile
    if (!reference.exists()) {
      // A module without any public API has no reference dump, same as for JVM modules.
      if (actual.length() == 0L) return
      throw GradleException(
        "Reference ABI dump file $reference does not exist. Run ./gradlew updateKotlinAbi to create it."
      )
    }
    val diff = AbiTools.getInstance().filesDiff(reference, actual)
    if (!diff.isNullOrEmpty()) {
      throw GradleException(
        "ABI check failed for project ${projectPath.get()}.\n" +
          "Run ./gradlew updateKotlinAbi to overwrite the reference ABI dump.\n$diff"
      )
    }
  }
}

abstract class AndroidAbiUpdateTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val actualDump: RegularFileProperty

  @get:OutputFile
  abstract val referenceDump: RegularFileProperty

  @TaskAction
  fun update() {
    val actual = actualDump.get().asFile
    val reference = referenceDump.get().asFile
    // A module without any public API has no reference dump, same as for JVM modules.
    if (actual.length() == 0L) {
      reference.delete()
    } else {
      actual.copyTo(reference, overwrite = true)
    }
  }
}

//Copies git hooks from /hooks folder into .git; currently used to run Detekt during push
//Git hook installation
tasks.register<Copy>("installGitHooks") {
  from(File(rootProject.rootDir, "config/hooks"))
  into({ File(rootProject.rootDir, ".git/hooks") })
  filePermissions {
    unix("rwxrwxrwx") // Make files executable
  }
}

tasks.register<Copy>("siteDokka") {
  description = "Generate dokka Github-flavored Markdown for the documentation site."
  group = "documentation"
  dependsOn(":dokkaGfmCollector")

  // Copy the files instead of configuring a different output directory on the dokka task itself
  // since the default output directories disambiguate between different types of outputs, and our
  // custom directory doesn't.
  from(layout.buildDirectory.dir("dokka/gfmCollector/leakcanary-repo"))
  // For whatever reason Dokka doesn't want to ignore the packages we told it to ignore.
  // Fine, we'll just ignore it here.
  exclude("**/com.example.leakcanary/**")
  into(rootProject.file("docs/api"))

  filter { line ->
    // Dokka adds [main]\ and [main]<br> everywhere, this just removes it.
    line.replace("\\[main\\]\\\\", "").replace("\\[main\\]<br>", "")
  }
}
