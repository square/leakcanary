package leakcanary.lint

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import org.junit.Test

class ProductionUsageDetectorTest {

  @Test
  fun `implementation should be an issue with ProductionUsagePattern`() {
    TestLintTask.lint()
        .allowMissingSdk()
        .files(
            TestFiles.gradle(
                """
              dependencies {
                api project(':projectX')
                implementation 'androidx.core:core-ktx:1.2.0'
                implementation 'com.squareup.leakcanary:leakcanary-android:2.2'
                implementation 'androidx.constraintlayout:constraintlayout:1.1.3'
              }
              """.trimIndent()
            )
                .indented()
        )
        .issues(ISSUE_PROD_USAGE_PATTERN)
        .run()
        .expect(
            """
            build.gradle:4: Error: LeakCanary should not be used in production. Please use debugImplementation instead. [ProductionUsagePattern]
              implementation 'com.squareup.leakcanary:leakcanary-android:2.2'
              ~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """.trimIndent()
        )
  }

  @Test
  fun `api should be an issue with ProductionUsagePattern`() {
    TestLintTask.lint()
        .allowMissingSdk()
        .files(
            TestFiles.gradle(
                """
              dependencies {
                api project(':projectX')
                implementation 'androidx.core:core-ktx:1.2.0'
                api 'com.squareup.leakcanary:leakcanary-android:2.2'
                implementation 'androidx.constraintlayout:constraintlayout:1.1.3'
              }
              """.trimIndent()
            )
                .indented()
        )
        .issues(ISSUE_PROD_USAGE_PATTERN)
        .run()
        .expect(
            """
            build.gradle:4: Error: LeakCanary should not be used in production. Please use debugImplementation instead. [ProductionUsagePattern]
              api 'com.squareup.leakcanary:leakcanary-android:2.2'
              ~~~
            1 errors, 0 warnings
            """.trimIndent()
        )
  }

  @Test
  fun `debug usage should not be an issue`() {
    TestLintTask.lint()
        .allowMissingSdk()
        .files(
            TestFiles.gradle(
                """
              dependencies {
                api project(':projectX')
                implementation 'androidx.core:core-ktx:1.2.0'
                debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.2'
                implementation 'androidx.constraintlayout:constraintlayout:1.1.3'
              }
              """.trimIndent()
            )
                .indented()
        )
        .issues(ISSUE_PROD_USAGE_PATTERN)
        .run()
        .expectClean()
  }

}