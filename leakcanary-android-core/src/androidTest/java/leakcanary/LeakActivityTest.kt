package leakcanary

import android.os.SystemClock
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.HeapAnalysisTable.Projection
import leakcanary.internal.activity.db.LeakTable.AllLeaksProjection
import leakcanary.internal.activity.db.LeaksDbHelper
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HeapAnalyzer
import shark.HprofWriterHelper
import shark.LeakTraceObject
import shark.LeakTraceReference
import shark.OnAnalysisProgressListener
import shark.SharkLog
import shark.ValueHolder.IntHolder
import shark.dump
import kotlin.reflect.KClass

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

  private fun insertHeapDump(block: HprofWriterHelper.() -> Unit) {
    val hprofFile = testFolder.newFile("temp.hprof")
    hprofFile.dump {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("Samsing")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = IntHolder(47)
      }
      block()
    }

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
    LeaksDbHelper(context).writableDatabase.use { db ->
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