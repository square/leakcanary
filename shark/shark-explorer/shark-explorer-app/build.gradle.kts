import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

// Compose Multiplatform's artifacts are not built for Java 8, so this module opts out of the
// repo wide Java 8 target set in the root build script. Nothing here is meant to run on Android.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions.jvmTarget = JVM_17
}

/**
 * The app's own version, from `SHARK_EXPLORER_VERSION` rather than the repo wide `VERSION_NAME`: the
 * explorer is released on its own tags, and jpackage would reject `VERSION_NAME` anyway. See
 * `gradle.properties`.
 */
val explorerVersion = property("SHARK_EXPLORER_VERSION").toString()

// Overrides the repo wide version the root build script sets from VERSION_NAME, so that the jar inside a
// packaged app is named after the app's version rather than after LeakCanary's. Same reasoning the root
// script gives for shark-cli, whose zip is named after the version too.
version = explorerVersion

dependencies {
  implementation(projects.shark.sharkExplorer.sharkExplorerCore)
  // Reads the bitmaps of a live process off the Android versions whose heap dumps can't carry them.
  implementation(projects.shark.sharkExplorer.sharkExplorerJdwp)

  implementation(compose.desktop.currentOs)
  implementation(compose.material3)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
  // Drives composables headlessly on the JVM, so UI tests need no emulator or display.
  testImplementation(compose.desktop.uiTestJUnit4)
  // Builds the heap dumps the UI tests open, rather than checking binary fixtures in.
  testImplementation(projects.shark.sharkHprofTest)
}

// A heap dump path passed with --args is written relative to the repo, not to this module, which is
// where a JavaExec resolves it from by default. The Compose plugin registers `run` late, hence
// matching by name rather than tasks.named().
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
  workingDir = rootProject.projectDir
}

/**
 * Writes the version onto the classpath, which is how [shark.explorer.app.SharkExplorerVersion] reads it.
 *
 * A generated resource rather than a jar manifest attribute, because `run` and the tests put class
 * directories on the classpath rather than the jar, so `Package.getImplementationVersion()` is null for
 * every way this app is launched while being worked on — and the update check would then only be
 * exercisable from a packaged build.
 */
val writeVersionResource by tasks.registering(WriteProperties::class) {
  destinationFile = layout.buildDirectory.file("generated/version/shark-explorer-version.properties")
  property("version", explorerVersion)
}

sourceSets.main {
  resources.srcDir(writeVersionResource.map { it.destinationFile.get().asFile.parentFile })
}

/** Shared by the Compose plugin's `run` and by `runNamed`, which launches the same classes itself. */
val explorerMainClass = "shark.explorer.app.MainKt"

/** The dock icon of both tasks, each through `-Xdock:icon`: a bundle's own icon does not survive AWT. */
val macOsIconFile = project.file("icons/shark-explorer-icon.icns")

// Launching under a name the dock shows. `run` is the one to use while working; see AGENTS.md for why
// this one is for handing a window over, and for what the dock does and doesn't take a name from.
tasks.register<RunNamedExplorer>("runNamed") {
  description = "Runs the explorer from an .app bundle named after --title, so the macOS dock says " +
    "which run it is."
  group = ApplicationPlugin.APPLICATION_GROUP
  // The JVM Gradle itself is on, which is the one `run` would use: this module configures no toolchain.
  javaExecutable.set(providers.systemProperty("java.home").map { "$it/bin/java" })
  runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
  mainClass.set(explorerMainClass)
  iconFile.set(macOsIconFile)
  bundleDirectory.set(layout.buildDirectory.dir("named"))
  // Where `run` resolves a heap dump path from, so that the same command line means the same thing.
  workingDirectory.set(rootProject.layout.projectDirectory)
}

compose.desktop {
  application {
    mainClass = explorerMainClass

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      // One word, and not by preference: this is the name of the .app, so it is the name of the zip
      // Block's signing service is asked to sign, and a space in it makes that request fail. See
      // docs/releasing-shark-explorer.md. squareup/tf-mobuild-workers#1365 fixes it; when that has
      // shipped this becomes "Shark Explorer", which is a change to the app's visible name and so a
      // change worth making deliberately rather than the moment it's unblocked.
      packageName = "SharkExplorer"
      // Each format validates this against rules of its own, and `gradle.properties` records which ones
      // and what they leave possible. `3.0-alpha-10` satisfies none of them, which is the whole reason
      // this app has a version line of its own.
      packageVersion = explorerVersion

      // Each platform takes a different container, all three rendered from the one SVG by
      // icons/render-icons.sh.
      //
      // The macOS one also fixes the dock icon of a plain `run`, which is how this app is usually
      // launched: the Compose plugin turns this file into `-Xdock:icon` on the run task, and without
      // it the process shows the default Java icon.
      macOS {
        iconFile.set(macOsIconFile)
        // Set here rather than left to default, which is the main class's package. Notarization history
        // and the Managed Software Center entry are both keyed on this, so it has to be a name Square
        // owns, and changing it after the first release is a migration for everyone who installed one.
        bundleID = "com.squareup.leakcanary.shark-explorer"
      }
      windows {
        iconFile.set(project.file("icons/shark-explorer-icon.ico"))
      }
      // A .deb takes a PNG, so it reuses the one the window already loads off the classpath.
      linux {
        iconFile.set(project.file("src/main/resources/shark-explorer-icon.png"))
      }

      // The JDK modules jlink puts in the packaged runtime, which are only the ones listed here: the
      // plugin detects nothing by itself. `suggestRuntimeModules` is where this list came from and the
      // task to re-run when the dependencies change.
      //
      // A module missing here is a `NoClassDefFoundError` that only a packaged build hits, because `run`
      // has the whole JDK on hand — which is how `java.net.http` went missing for as long as it did. The
      // update check is the only thing that fetches anything, so a packaged app logged "Could not fetch
      // …/latest.properties" once at startup and then never mentioned a new version again. `jdk.jdi` is
      // what `shark-explorer-jdwp` attaches to a live app with.
      modules("java.instrument", "java.net.http", "jdk.jdi", "jdk.unsupported")
    }
  }
}

/*
 * Deletes the dylibs left inside the packaged app's own jars, which is what makes a macOS build
 * notarizable.
 *
 * `skiko-awt-runtime-macos-arm64` ships both architectures' dylibs, 21 and 22 MB. Compose extracts the one
 * it is packaging for into the app directory — where the launcher's `-Dskiko.library.path=$APPDIR` makes
 * it the copy that loads — and leaves the other architecture's dylib inside the jar. Nothing loads that
 * one. Nothing signing the bundle reaches it either: a signer walks files, and this is an entry in a zip.
 *
 * Apple's notary service does open jars, so it is the one Mach-O in the bundle that arrives unsigned, and
 * one is enough. It refused this app over
 * `skiko-awt-runtime-macos-arm64-*.jar/libskiko-macos-x64.dylib` — "The binary is not signed with a valid
 * Developer ID certificate" — while every file a signer can see was signed correctly, which is why no
 * local check found it. See docs/releasing-shark-explorer.md.
 *
 * Done to the app image and not to the jar this module resolves, because `run` and the tests load skiko
 * out of that jar, and the image is the first point where only one architecture is still in play. Every
 * package format is rendered from this image, so stripping it here covers the DMG.
 */
// Matched by name rather than with tasks.named(), because the Compose plugin registers this one late too.
tasks.matching { it.name == "createDistributable" }.configureEach {
  // Read at configuration time: a task action reaching back into the project is what the configuration
  // cache forbids, and this needs nothing else from it.
  val appImage = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
  doLast {
    appImage.walkTopDown().filter { it.isFile && it.extension == "jar" }.forEach { jar ->
      // The `.sha256` beside each one goes too: skiko checks the hash of the copy it loads, which is the
      // extracted one, and a hash of a file that is gone is not worth carrying.
      val bundledDylibs = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
          .map { it.name }
          .filter { it.endsWith(".dylib") || it.endsWith(".dylib.sha256") }
          .toSet()
      }
      if (bundledDylibs.isEmpty()) return@forEach

      // The extracted copy, which has to be there for deleting the rest to be safe. `$APPDIR` is this
      // directory, so beside the jar is the only place it counts as extracted to.
      val extracted = jar.parentFile.walk().maxDepth(1).filter { it.extension == "dylib" }.toList()
      if (extracted.isEmpty()) {
        throw GradleException(
          "${jar.name} holds ${bundledDylibs.joinToString()} and no dylib sits beside it, so these " +
            "are the only copies and deleting them would leave nothing to load. Compose extracting " +
            "the packaged architecture's dylib into the app directory is what makes this safe, and it " +
            "has stopped doing that. Check what createDistributable produces before touching this."
        )
      }

      val stripped = File(jar.parentFile, "${jar.name}.stripped")
      ZipFile(jar).use { zip ->
        ZipOutputStream(stripped.outputStream().buffered()).use { out ->
          // In the order they were in, so the manifest stays the first entry.
          zip.entries().asSequence().filter { it.name !in bundledDylibs }.forEach { entry ->
            out.putNextEntry(ZipEntry(entry.name))
            zip.getInputStream(entry).use { it.copyTo(out) }
            out.closeEntry()
          }
        }
      }
      val freed = jar.length() - stripped.length()
      Files.move(stripped.toPath(), jar.toPath(), REPLACE_EXISTING)
      logger.lifecycle(
        "Stripped ${bundledDylibs.joinToString()} out of ${jar.name}, ${freed / 1024 / 1024} MB, " +
          "leaving ${extracted.joinToString { it.name }} to load."
      )
    }
  }
}

/**
 * Runs the explorer from a generated `.app` bundle whose file name is what `--title` calls the run,
 * which is the only thing the macOS dock will name it after.
 *
 * The dock takes a process's name from the bundle it was launched from and ignores both `-Xdock:name`
 * (JDK-8173753, open since macOS 10.9) and everything a process can set about itself. It ignores
 * `CFBundleName` here too: two bundles carrying the same one, differing only in file name, are two
 * differently named tiles.
 *
 * The bundle is a launcher script and an `Info.plist` around the classes `run` would have run, rather
 * than a `jpackage` distribution, because jlink is a minute per code change and therefore a minute per
 * look.
 */
abstract class RunNamedExplorer : DefaultTask() {

  @get:Input
  abstract val javaExecutable: Property<String>

  @get:Classpath
  abstract val runtimeClasspath: ConfigurableFileCollection

  @get:Input
  abstract val mainClass: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val iconFile: RegularFileProperty

  /** Holds one bundle per name, each rewritten by the next run that uses that name. */
  @get:Internal
  abstract val bundleDirectory: DirectoryProperty

  @get:Internal
  abstract val workingDirectory: DirectoryProperty

  /** The explorer's own command line, spelled the way `run` takes it. */
  @get:Input
  @get:Optional
  @get:Option(option = "args", description = "The explorer's command line, as `run` takes it.")
  abstract val appArgs: Property<String>

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun launch() {
    val osName = System.getProperty("os.name")
    if (!osName.startsWith("Mac")) {
      throw GradleException("runNamed builds a macOS .app bundle, and this is $osName. Use `run`.")
    }
    val args = appArgs.getOrElse("").trim()
    val name = bundleName(args)
    val bundle = File(bundleDirectory.get().asFile, "$name.app")
    // Rewritten rather than added to: the classpath and the command line are baked into it. Which is
    // also why relaunching a name while a window of that name is still open is the thing not to do —
    // that window is reading these files.
    bundle.deleteRecursively()
    val icon = File(bundle, "Contents/Resources/$ICON_FILE_NAME")
    icon.parentFile.mkdirs()
    iconFile.get().asFile.copyTo(icon)
    File(bundle, "Contents/Info.plist").writeText(infoPlist())
    // Everything the JVM says before the app can open a log file of its own, which is where a run that
    // died on its way up says why: `open` gives a bundle no terminal to say it on.
    val output = File(bundleDirectory.get().asFile, "$name.out")
    val launcher = File(bundle, "Contents/MacOS/$EXECUTABLE_NAME")
    launcher.parentFile.mkdirs()
    launcher.writeText(launcherScript(args, output))
    launcher.setExecutable(true)
    execOperations.exec { commandLine("open", "-n", bundle.path) }
    logger.lifecycle("Launched \"$name\". It logs to ~/.shark-explorer/logs, and to $output if it can't.")
  }

  /**
   * What the bundle file is called, and therefore what the dock says: the run's title, or the app's own
   * name for a command line that gave none.
   *
   * Read here with a regex rather than by the parser the app uses, which is in the app: a title the two
   * read differently costs the bundle its name and nothing else, since every window title comes from
   * the app's own reading of the same string.
   */
  private fun bundleName(args: String): String {
    val title = TITLE.find(args)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    // The dock is reading a file name, which can hold neither of the characters a path is built from.
    return title.orEmpty().replace(PATH_CHARACTERS, " ").trim().ifEmpty { DEFAULT_NAME }
  }

  private fun infoPlist(): String =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
      <key>CFBundleExecutable</key><string>$EXECUTABLE_NAME</string>
      <key>CFBundleIconFile</key><string>$ICON_FILE_NAME</string>
      <key>CFBundleIdentifier</key><string>$BUNDLE_IDENTIFIER</string>
      <key>CFBundlePackageType</key><string>APPL</string>
    </dict>
    </plist>
    """.trimIndent() + "\n"

  /**
   * A script rather than a launcher binary, and `exec` rather than a child process: the JVM has to end
   * up being the process macOS launched from the bundle, or it is a process of its own again and the
   * dock is back to calling it java.
   *
   * It passes `-Xdock:icon` even though the bundle already declares `CFBundleIconFile`, because that
   * key only reaches LaunchServices: AWT overwrites the tile with its own Java icon as it starts, and
   * without the flag the dock shows that instead. See AGENTS.md.
   */
  private fun launcherScript(
    args: String,
    output: File
  ): String {
    val classpath = runtimeClasspath.files.joinToString(":") { it.path }
    return """
      #!/bin/sh
      # Written by the runNamed Gradle task, and rewritten by the next run of it.
      cd ${workingDirectory.get().asFile.path.shellQuoted()} || exit 1
      exec ${javaExecutable.get().shellQuoted()} \
        -Dcompose.application.configure.swing.globals=true \
        -Xdock:icon=${iconFile.get().asFile.path.shellQuoted()} \
        -cp ${classpath.shellQuoted()} \
        ${mainClass.get()} $args >>${output.path.shellQuoted()} 2>&1
    """.trimIndent() + "\n"
  }

  /**
   * A path a shell reads as one word whatever is in it. The command line above is deliberately not put
   * through this: it is split by the shell exactly as the shell that typed it would have split it.
   */
  private fun String.shellQuoted() = "'" + replace("'", "'\\''") + "'"

  private companion object {
    const val EXECUTABLE_NAME = "shark-explorer"
    const val ICON_FILE_NAME = "shark-explorer-icon.icns"

    /** Not the packaged app's, which names a real installed app rather than a bundle in a build folder. */
    const val BUNDLE_IDENTIFIER = "shark.explorer.app.named"

    /** What a run with no title is called, matching the window title of a window with no heap dump. */
    const val DEFAULT_NAME = "Shark Explorer"

    /** Both spellings of the option, since the app takes both. Quoted first: a title has spaces in it. */
    val TITLE = Regex("""--title[=\s]+(?:"([^"]*)"|(\S+))""")

    val PATH_CHARACTERS = Regex("[/:]")
  }
}
