package leakcanary

import android.view.Menu
import android.view.Window
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.squareup.leakcanary.core.R
import java.io.File
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable.AllLeaksProjection
import leakcanary.internal.activity.db.ScopedLeaksDb
import leakcanary.internal.withOutOfMemoryGuidance
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalyzer
import shark.HprofWriterHelper
import shark.LeakTraceObject
import shark.OnAnalysisProgressListener
import shark.ValueHolder.IntHolder
import shark.dump

internal class LeakActivityTest {

  private val activityTestRule = ActivityTestRule(LeakActivity::class.java, false, false)

  @get:Rule
  var testFolder = TemporaryFolder()

  @get:Rule
  var rules: RuleChain = RuleChain.outerRule(DatabaseRule())
    .around(activityTestRule)

  @Test
  fun noLeakOnHome() {
    activityTestRule.launchActivity(null)
    onView(withText("0 Distinct Leaks")).check(matches(isDisplayed()))
  }

  /**
   * Regression test for the crash reported in
   * https://github.com/square/leakcanary/pull/2794: when the options menu is owned by the window
   * decor action bar, opening the overflow menu makes the framework call
   * `Window.Callback#onMenuOpened(FEATURE_ACTION_BAR, null)` from
   * `PhoneWindow$ActionMenuPresenterCallback#onOpenSubMenu()`. Apps and libraries that wrap the
   * window callback in Kotlin declare that [Menu] parameter as non null, matching how the framework
   * annotates it, so the null value crashes them.
   *
   * See https://issuetracker.google.com/issues/188568911
   */
  @Test
  fun openingOverflowMenuDoesNotCrashWrappedWindowCallback() {
    val activity = activityTestRule.launchActivity(null)
    // The Heap Dumps screen is the one with an overflow menu.
    onView(withId(R.id.leak_canary_navigation_button_heap_dumps)).perform(click())

    // Wrapping after the activity is created, which is when apps and libraries typically do it.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val window = activity.window
      window.callback = NonNullMenuWindowCallback(window.callback)
    }

    // Not Espresso.openActionBarOverflowOrOptionsMenu(): that presses the menu key on devices that
    // report a permanent one, which emulators still do, and the LeakCanary menu is hosted in a
    // Toolbar rather than in the activity options menu.
    onView(withClassName(endsWith("OverflowMenuButton"))).perform(click())

    onView(withText(R.string.leak_canary_delete_all)).check(matches(isDisplayed()))
  }

  @Test
  fun oneLeakOnHome() {
    insertHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    activityTestRule.launchActivity(null)
    onView(withText("1 Distinct Leak")).check(matches(isDisplayed()))
  }

  @Test
  fun seeLeakOnLeakScreen() {
    insertHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    activityTestRule.launchActivity(null)

    onData(withItem<AllLeaksProjection> { it.shortDescription == "Holder.leak" })
      .perform(click())
    onData(withItem<LeakTraceObject> { it.className == "com.example.Leaking" })
      .inAdapterView(withId(R.id.leak_canary_list))
      .check(matches(isDisplayed()))
  }

  @Test
  fun leakWithEmptyReferencePath() {
    insertHeapDump {
      val leakingInstance = "com.example.Leaking" watchedInstance {}
      gcRoot(JniGlobal(id = leakingInstance.value, jniGlobalRefId = 42))
    }
    activityTestRule.launchActivity(null)

    onData(withItem<AllLeaksProjection> { it.shortDescription == "com.example.Leaking" })
      .perform(click())
    onData(withItem<LeakTraceObject> { it.className == "com.example.Leaking" })
      .inAdapterView(withId(R.id.leak_canary_list))
      .check(matches(isDisplayed()))
  }

  @Test
  fun outOfMemoryFailureDoesNotAskForABugReport() {
    // The shape Shark's hash maps throw when they can't grow their buffers, which is how the
    // analysis usually runs out of memory. See https://github.com/square/leakcanary/issues/2773
    val analysisId = insertFailure(
      RuntimeException(
        "Not enough memory to allocate buffers for rehashing: 4194304 -> 8388608",
        OutOfMemoryError("Failed to allocate a 67108888 byte allocation")
      )
    )
    activityTestRule.launchActivity(createFailureIntent(analysisId))

    onView(withText(containsString("The analysis ran out of memory")))
      .check(matches(isDisplayed()))
    onView(withText(containsString("Not enough memory to analyze heap")))
      .check(matches(isDisplayed()))
    onView(withText(containsString("file a bug report"))).check(doesNotExist())
  }

  @Test
  fun failureThatIsNotOutOfMemoryAsksForABugReport() {
    val analysisId = insertFailure(IllegalStateException("Analysis went wrong"))
    activityTestRule.launchActivity(createFailureIntent(analysisId))

    onView(withText(containsString("file a bug report")))
      .check(matches(isDisplayed()))
  }

  private fun createFailureIntent(analysisId: Long) = LeakActivity.createFailureIntent(
    InstrumentationRegistry.getInstrumentation().targetContext, analysisId
  )

  /**
   * Inserts the failure of an analysis that did run, so that its heap dump file exists and the
   * failure screen offers to share it, and returns its analysis id.
   */
  private fun insertFailure(cause: Throwable): Long {
    val hprofFile = writeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    val failure = HeapAnalysisFailure(
      heapDumpFile = hprofFile,
      createdAtTimeMillis = System.currentTimeMillis(),
      analysisDurationMillis = 0,
      exception = HeapAnalysisException(cause)
      // Applied by AndroidDebugHeapAnalyzer before the failure is stored, so the screen always
      // reads a failure that has already been through it.
    ).withOutOfMemoryGuidance()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return ScopedLeaksDb.writableDatabase(context) { db ->
      HeapAnalysisTable.insert(db, failure)
    }
  }

  private fun writeHeapDump(block: HprofWriterHelper.() -> Unit): File {
    val hprofFile = testFolder.newFile("temp.hprof")
    hprofFile.dump {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("Samsing")
        staticField["ID"] = string("M4-rc20")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = IntHolder(47)
      }
      block()
    }
    return hprofFile
  }

  private fun insertHeapDump(block: HprofWriterHelper.() -> Unit) {
    val hprofFile = writeHeapDump(block)
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val result = heapAnalyzer.analyze(
      heapDumpFile = hprofFile,
      leakingObjectFinder = LeakCanary.config.leakingObjectFinder,
      referenceMatchers = LeakCanary.config.referenceMatchers,
      computeRetainedHeapSize = LeakCanary.config.computeRetainedHeapSize,
      objectInspectors = LeakCanary.config.objectInspectors,
      metadataExtractor = LeakCanary.config.metadataExtractor,
      proguardMapping = null
    )
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    ScopedLeaksDb.writableDatabase(context) { db ->
      HeapAnalysisTable.insert(db, result)
    }
  }

  inline fun <reified T : Any> withItem(
    filterDescription: String? = null,
    crossinline filter: (T) -> Boolean
  ): Matcher<T> {
    return object : TypeSafeMatcher<T>(T::class.java) {
      override fun describeTo(description: Description) {
        if (filterDescription != null) {
          description.appendText("is $filterDescription")
        }
      }

      override fun matchesSafely(item: T): Boolean {
        return filter(item)
      }
    }
  }
}

/**
 * A [Window.Callback] wrapper written the way an app or a library such as Curtains would write it:
 * from Kotlin, with the [Menu] parameter declared as non null. Kotlin then adds a null check on that
 * parameter, which throws if the framework calls it with a null menu.
 */
private class NonNullMenuWindowCallback(
  private val delegate: Window.Callback
) : Window.Callback by delegate {
  override fun onMenuOpened(
    featureId: Int,
    menu: Menu
  ): Boolean = delegate.onMenuOpened(featureId, menu)
}

fun tryAndRestoreConfig(block: () -> Unit) {
  val original = LeakCanary.config
  try {
    block()
  } finally {
    LeakCanary.config = original
  }
}
