import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.net.URL
import kotlinx.validation.ApiValidationExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
    classpath(libs.gradlePlugin.binaryCompatibility)
    classpath(libs.gradlePlugin.keeper)
    classpath(libs.gradlePlugin.sqldelight)
    classpath("com.google.dagger:hilt-android-gradle-plugin:2.43.2")
  }
}

// We use JetBrain's Kotlin Binary Compatibility Validator to track changes to our public binary
// APIs.
// When making a change that results in a public ABI change, the apiCheck task will fail. When this
// happens, run ./gradlew apiDump to generate updated *.api files, and add those to your commit.
// See https://github.com/Kotlin/binary-compatibility-validator
apply(plugin = "binary-compatibility-validator")

extensions.configure<ApiValidationExtension> {
  // Ignore projects that are not uploaded to Maven Central
  ignoredProjects += listOf("leakcanary-app", "leakcanary-android-sample", "shark-test", "shark-hprof-test", "shark-cli")
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
    jcenter()
  }

  apply(plugin = "io.gitlab.arturbosch.detekt")

  tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(
      listOf(
        "-Xlint:all",
        "-Xlint:-serial",
        "-Xlint:-deprecation",
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
  tasks.withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = "1.8"
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
        url.set(URL("https://square.github.io/okio/2.x/okio/"))
      }
      externalDocumentationLink {
        url.set(URL("https://square.github.io/moshi/1.x/moshi/"))
      }
    }
  }

  pluginManager.withPlugin("com.vanniktech.maven.publish") {
    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral(SonatypeHost.S01)
      signAllPublications()
    }
  }
}

//Copies git hooks from /hooks folder into .git; currently used to run Detekt during push
//Git hook installation
tasks.register<Copy>("installGitHooks") {
  from(File(rootProject.rootDir, "config/hooks"))
  into({ File(rootProject.rootDir, ".git/hooks") })
  fileMode = "0777".toInt(8) // Make files executable
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
