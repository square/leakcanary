import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.formats.DokkaFormatPlugin
import org.jetbrains.dokka.gradle.formats.DokkaFormatPlugin.DokkaFormatPluginContext
import org.jetbrains.dokka.gradle.internal.InternalDokkaGradlePluginApi
import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.w3c.dom.Element

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
    classpath(libs.gradlePlugin.composeMultiplatform)
    classpath(libs.kotlin.abi.tools)
  }
}

/**
 * The Dokka Gradle plugin only ships the `html` and `javadoc` output formats. The documentation
 * site is built from Github flavored Markdown, which Dokka still publishes as a pair of engine
 * plugins, so register it here as an extra format. This adds a `gfm` publication, i.e. the
 * `dokkaGeneratePublicationGfm` task.
 *
 * Subclassing [DokkaFormatPlugin] is how Dokka's own `javadoc` format is implemented, but it is
 * flagged as internal, so this may need updating when upgrading Dokka.
 */
@OptIn(InternalDokkaGradlePluginApi::class)
abstract class DokkaGfmPlugin : DokkaFormatPlugin(formatName = "gfm") {
  override fun DokkaFormatPluginContext.configure() {
    // Engine plugins have to match the engine version, so read it off the extension rather than
    // declaring these in the version catalog. This is how the built in html format does it too.
    fun enginePlugin(artifactId: String): Provider<Dependency> =
      dokkaExtension.dokkaEngineVersion.map {
        project.dependencies.create("org.jetbrains.dokka:$artifactId:$it")
      }

    project.dependencies.dokkaPlugin(enginePlugin("gfm-plugin"))
    // Merges the per module outputs into a single publication.
    formatDependencies.dokkaPublicationPluginClasspathApiOnly.dependencies
      .addLater(enginePlugin("gfm-template-processing-plugin"))
  }
}

// Applied to the root project so that it can aggregate the documentation of all subprojects.
apply<DokkaGfmPlugin>()

extensions.configure<DokkaExtension> {
  moduleName.set("LeakCanary")
}

// Dokka prefixes every declaration with the name of the source set it belongs to. Each module here
// documents exactly one source set, so name them all the same and strip that prefix in siteDokka.
val dokkaSourceSetDisplayName = "api"

repositories {
  mavenCentral()
}

// Config shared for all subprojects
subprojects {
  // Set on every module, not just the published ones: shark-cli isn't published but the zip it
  // distributes is named after the version.
  group = property("GROUP").toString()
  version = property("VERSION_NAME").toString()

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

// Config shared for subprojects except the Gradle plugin, which runs on Gradle's own JVM, and the
// Shark explorer desktop app, which needs a newer JVM target than the rest of the repo because
// Compose Multiplatform's artifacts do not support Java 8. Both set their own targets.
configure(subprojects.filter {
  it.name !in listOf("leakcanary-deobfuscation-gradle-plugin", "shark-explorer-app")
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

// Modules that don't ship an API meant to be consumed by others: the sample apps, the LeakCanary UI
// app and its internal plumbing, the Shark explorer desktop app and the heap model it renders, the
// test fixtures for Shark, and the Shark command line tool, which is distributed as a zip attached
// to the Github release rather than as a dependency. They are not published to Maven Central, their
// ABI isn't tracked and they're left out of the documentation site.
val modulesWithoutPublicApi = listOf(
  "leakcanary-android-process-sample",
  "leakcanary-android-sample",
  "leakcanary-app",
  "leakcanary-app-aidl",
  "leakcanary-app-db",
  "leakcanary-app-service",
  "shark-cli",
  "shark-explorer-app",
  "shark-explorer-core",
  "shark-explorer-jdwp",
  "shark-hprof-test",
  "shark-test",
)

// The parent projects of the module groups, e.g. :shark, hold no code of their own.
val publicApiProjects = subprojects.filter {
  it.subprojects.isEmpty() && it.name !in modulesWithoutPublicApi
}

configure(publicApiProjects) {
  pluginManager.withPlugin("com.vanniktech.maven.publish") {
    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral(automaticRelease = true)
      signAllPublications()
    }
  }

  // The Android Gradle Plugin only creates the kotlin extension that Dokka reads its source sets
  // from once the project build script has run, so hold off until the project is evaluated.
  afterEvaluate {
    apply<DokkaGfmPlugin>()
    // The javadoc jar the publish plugin attaches to each Kotlin JVM publication. The plugin looks
    // for this plugin by id and then builds the jar from `dokkaGeneratePublicationJavadoc`; without
    // it, it falls back to the javadoc tool, which has no Kotlin source to read. Applied here
    // rather than at the top of this block so that it lands before the publish plugin makes that
    // choice, which it does in its own `afterEvaluate` registered while the module is evaluated.
    // Android library modules never reach that code path: AGP builds their javadoc jar by running
    // its own bundled copy of Dokka over the release variant.
    apply(plugin = "org.jetbrains.dokka-javadoc")
    extensions.configure<DokkaExtension> {
      // Defaults to the Gradle project path, which would nest the output of e.g. shark-graph in an
      // extra shark directory. Module names are already unique.
      modulePath.set(project.name)
      dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        displayName.set(dokkaSourceSetDisplayName)
        analysisPlatform.set(KotlinPlatform.JVM)

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
        externalDocumentationLinks.register("okio") {
          url.set(URI("https://square.github.io/okio/2.x/okio/"))
        }
        externalDocumentationLinks.register("moshi") {
          url.set(URI("https://square.github.io/moshi/1.x/moshi/"))
        }
      }
    }
  }
}

// We use JetBrain's Kotlin Binary Compatibility Validator to track changes to our public binary
// APIs.
// When making a change that results in a public ABI change, the checkKotlinAbi task will fail. When
// this happens, run ./gradlew updateKotlinAbi to generate updated *.api files, and add those to
// your commit.
// See https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html
configure(publicApiProjects) {
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
    // A module that exposes no public API gets an empty dump file rather than no file at all, so
    // that gaining a public API is always a visible diff.
    actualDump.get().asFile.copyTo(referenceDump.get().asFile, overwrite = true)
  }
}

/**
 * Every Android component LeakCanary ships is declared in a manifest and instantiated by the system
 * from the class name in that manifest. `RemoteLeakCanaryWorkerService`, the service the heap
 * analysis runs in, is on top of that looked up by name twice from LeakCanary's own code:
 * `RemoteWorkManagerHeapAnalyzer` holds its name in a string constant to decide whether the analysis
 * can run in another process, and `LeakCanaryProcess.isInAnalyzerProcess` asks `PackageManager` for
 * the process that service is declared to run in. None of that is a reference R8 can see, so in a
 * minified app the only thing stopping R8 from renaming or deleting those classes is the keep rules
 * the Android Gradle plugin generates from the merged manifest.
 *
 * LeakCanary used to also write those rules by hand, in `consumer-proguard-rules.pro` files, and
 * they were deleted once it was clear AGP already generated every one of them. This holds that to
 * be true: it reads the merged manifest of an app built from LeakCanary, and fails if any component
 * that manifest declares came out of R8 renamed, deleted, or stripped of the no argument
 * constructor the system calls. That last one is not hypothetical: the rules WorkManager and Room
 * ship keep the class without the constructor, which R8 stopped treating as the same thing in full
 * mode, and the app then dies the first time something instantiates one of theirs.
 */
project(":samples:leakcanary-android-process-sample") {
  pluginManager.withPlugin("com.android.application") {
    registerShrunkManifestComponentsCheck()
  }
}

fun Project.registerShrunkManifestComponentsCheck() {
  val checkTask = tasks.register<ShrunkManifestComponentsTask>("checkShrunkManifestComponents") {
    description = "Checks that every component declared in the merged manifest came out of R8 " +
      "under its own name, with the no argument constructor the system instantiates it with."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    report.set(layout.buildDirectory.file("reports/shrunk-manifest-components.txt"))
    generatedKeepRules.set(layout.buildDirectory.dir("intermediates/aapt_proguard_file/release"))
  }
  tasks.named("check") {
    dependsOn(checkTask)
  }

  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
  androidComponents.onVariants(androidComponents.selector().withName("release")) { variant ->
    checkTask.configure {
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
      obfuscationMapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
    }
  }
}

@CacheableTask
abstract class ShrunkManifestComponentsTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val mergedManifest: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val obfuscationMapping: RegularFileProperty

  @get:OutputFile
  abstract val report: RegularFileProperty

  /** Where the Android Gradle plugin wrote the keep rules it derived from the merged manifest. */
  @get:Internal
  abstract val generatedKeepRules: DirectoryProperty

  @TaskAction
  fun check() {
    val kept = keptClasses()
    val outcomes = declaredComponents().associateWith { componentClassName ->
      val keptClass = kept[componentClassName]
      when {
        keptClass == null -> "R8 deleted it"
        keptClass.newName != componentClassName -> "R8 renamed it to ${keptClass.newName}"
        !keptClass.hasNoArgConstructor -> "R8 removed its no argument constructor"
        else -> KEPT
      }
    }

    val reportFile = report.get().asFile
    reportFile.parentFile.mkdirs()
    reportFile.writeText(outcomes.entries.joinToString("\n") { "${it.value}: ${it.key}" })

    val failures = outcomes.filterValues { it != KEPT }
    if (failures.isNotEmpty()) {
      throw GradleException(
        """
        The merged manifest declares components that the system will fail to instantiate, because
        R8 did not leave them callable under the name the manifest gives:

        ${failures.entries.joinToString("\n        ") { "${it.key}: ${it.value}" }}

        The Android Gradle plugin writes `-keep class <name> { <init>(); }` for every component in
        the merged manifest, so either the component is no longer declared there or the plugin
        stopped generating the rule.

        Rules the plugin generated: ${generatedKeepRules.get().asFile}
        Manifest read: ${mergedManifest.get().asFile}
        Mapping read: ${obfuscationMapping.get().asFile}
        """.trimIndent()
      )
    }
  }

  /** The class behind every component the merged manifest declares. */
  private fun declaredComponents(): List<String> {
    val document = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(mergedManifest.get().asFile)
    // An activity-alias has no class of its own: its android:name is just the name the alias is
    // published under, and the class is the activity it points at.
    val classNameAttributes = mapOf(
      "activity" to "android:name",
      "activity-alias" to "android:targetActivity",
      "service" to "android:name",
      "receiver" to "android:name",
      "provider" to "android:name",
    )
    return classNameAttributes
      .flatMap { (tagName, attributeName) ->
        val elements = document.getElementsByTagName(tagName)
        (0 until elements.length).mapNotNull { index ->
          (elements.item(index) as Element)
            .getAttribute(attributeName)
            .takeIf { it.isNotEmpty() }
        }
      }
      .distinct()
      .sorted()
  }

  private class KeptClass(
    val newName: String,
    val hasNoArgConstructor: Boolean
  )

  /**
   * What R8 did to each class it kept, read off the mapping file, keyed by the name the class had
   * before R8 ran. R8 writes one `<original> -> <new name>:` line per class it kept, at the start of
   * a line, then a line per member it kept, indented under it. A class it deleted entirely gets no
   * line at all.
   */
  private fun keptClasses(): Map<String, KeptClass> {
    val classes = mutableMapOf<String, KeptClass>()
    var currentName: String? = null
    obfuscationMapping.get().asFile.forEachLine { line ->
      if (line.isEmpty() || line.startsWith("#")) return@forEachLine
      if (line.first().isWhitespace()) {
        // A member of the class the previous unindented line named. Constructors are never renamed,
        // so the line to look for ends in `void <init>() -> <init>`, after the source line numbers
        // R8 records in front of it.
        val name = currentName ?: return@forEachLine
        if (line.trimEnd().endsWith("void <init>() -> <init>")) {
          classes[name] = KeptClass(classes.getValue(name).newName, hasNoArgConstructor = true)
        }
      } else {
        val names = line.removeSuffix(":").split(" -> ")
        currentName = names.first()
        classes[names.first()] = KeptClass(names.last(), hasNoArgConstructor = false)
      }
    }
    return classes
  }

  companion object {
    private const val KEPT = "kept"
  }
}

//Copies git hooks from /hooks folder into .git; currently used to run Detekt during push
//Git hook installation
tasks.register<Copy>("installGitHooks") {
  from(File(rootProject.rootDir, "config/hooks"))
  // Not rootDir/.git/hooks: in a git worktree .git is a file, not a directory, and the hooks live
  // in the main checkout. git rev-parse --git-common-dir resolves to the right place in both cases.
  into({
    val gitCommonDir = providers.exec {
      commandLine("git", "rev-parse", "--git-common-dir")
      workingDir = rootProject.rootDir
    }.standardOutput.asText.get().trim()
    // The path is relative to rootDir in a normal checkout and absolute in a worktree. resolve()
    // handles both: it returns the argument as is when it's already rooted.
    rootProject.rootDir.resolve(gitCommonDir).resolve("hooks")
  })
  filePermissions {
    unix("rwxrwxrwx") // Make files executable
  }
}

dependencies {
  // Aggregates every documented module into the root project's Dokka publication.
  publicApiProjects.forEach { add("dokka", project(it.path)) }
}

tasks.register<Copy>("siteDokka") {
  description = "Generate dokka Github-flavored Markdown for the documentation site."
  group = "documentation"

  // Copy the files instead of configuring a different output directory on the dokka task itself
  // since the default output directories disambiguate between different types of outputs, and our
  // custom directory doesn't.
  from(tasks.named("dokkaGeneratePublicationGfm"))
  // For whatever reason Dokka doesn't want to ignore the packages we told it to ignore.
  // Fine, we'll just ignore it here.
  exclude("**/com.example.leakcanary/**")
  into(rootProject.file("docs/api"))

  filter { line ->
    line
      .replace("[$dokkaSourceSetDisplayName]\\", "")
      .replace("[$dokkaSourceSetDisplayName]<br>", "")
  }
}
