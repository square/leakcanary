import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import io.gitlab.arturbosch.detekt.Detekt
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
    classpath(libs.kotlinPlugin)
    classpath(libs.androidPlugin)
    classpath(libs.dokkaPlugin)
    classpath(libs.mavenPublishPlugin)
    classpath(libs.detektPlugin)
    classpath(libs.binaryCompatibilityValidatorPlugin)
    classpath(libs.keeperPlugin)
  }
}

// We use JetBrain's Kotlin Binary Compatibility Validator to track changes to our public binary
// APIs.
// When making a change that results in a public ABI change, the apiCheck task will fail. When this
// happens, run ./gradlew apiDump to generate updated *.api files, and add those to your commit.
// See https://github.com/Kotlin/binary-compatibility-validator
apply(plugin = "binary-compatibility-validator")

extensions.configure<ApiValidationExtension> {
  // Ignore all sample projects, since they're not part of our API.
  ignoredProjects += setOf("leakcanary-android-sample")
}

allprojects {
  group = property("GROUP").toString()
  version = property("VERSION_NAME").toString()

  repositories {
    google()
    jcenter()
  }
}

subprojects {
  pluginManager.withPlugin("com.vanniktech.maven.publish") {
    configure<MavenPublishBaseExtension> {
      publishToMavenCentral(SonatypeHost.S01)
    }
    apply(plugin = "org.jetbrains.dokka")
    tasks.named<DokkaTask>("dokkaGfm") {
      outputDirectory.set(file("$rootDir/docs/api"))
      dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        perPackageOption {
          // will match all .internal packages and sub-packages
          matchingRegex.set(".*\\.internal.*")
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
  }

  tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs = options.compilerArgs + listOf(
      "-Xlint:all",
      "-Xlint:-serial",
      "-Xlint:-deprecation",
      // espresso-core classes say they're compiled with 51.0 but contain 52.0 attributes.
      // warning: [classfile] MethodParameters attribute introduced in version 52.0 class files is ignored in version 51.0 class files
      // "-Werror"
    )
  }

  tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
      // Avoid warnings of using older stdlib version 1.3 than compiler version 1.4
      apiVersion = "1.3"
    }
  }

  tasks.withType<Test>().configureEach {
    testLogging {
      exceptionFormat = TestExceptionFormat.FULL
      showCauses = true
      showExceptions = true
      showStackTraces = true
    }
  }

  apply(plugin = "io.gitlab.arturbosch.detekt")
  tasks.withType<Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
    config.setFrom(file("$rootDir/detekt-config.yml"))
    parallel = true
    reports.xml.enabled = false
  }

  pluginManager.withPlugin("java") {
    tasks.named("check") { dependsOn("detekt") }
    tasks.named("assemble") { dependsOn(rootProject.tasks["installGitHooks"]) }
    tasks.named("clean") { dependsOn(rootProject.tasks["installGitHooks"]) }
  }
}

//Copies git hooks from /hooks folder into .git; currently used to run Detekt during push
//Git hook installation
tasks.register<Copy>("installGitHooks") {
  from(File(rootDir, "hooks"))
  into(File(rootDir, ".git/hooks"))
  fileMode = 777
}
