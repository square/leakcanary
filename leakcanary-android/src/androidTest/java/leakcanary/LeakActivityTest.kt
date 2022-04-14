package leakcanary

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.rule.ActivityTestRule
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import leakcanary.EventListener.Event.HeapAnalysisDone
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofWriterHelper
import shark.ValueHolder.IntHolder
import shark.dumpToBytes

internal class LeakActivityTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Suppress("UNCHECKED_CAST")
  // This class is internal but ActivityTestRule requires being passed in the real class.
  private val leakActivityClass = Class.forName("leakcanary.internal.activity.LeakActivity")
    as Class<Activity>

  @get:Rule
  val activityTestRule = object : ActivityTestRule<Activity>(leakActivityClass, false, false) {
    override fun getActivityIntent(): Intent {
      return LeakCanary.newLeakDisplayActivityIntent()
    }
  }

  @Test
  fun importHeapDumpFile() = tryAndRestoreConfig {
    val latch = CountDownLatch(1)
    LeakCanary.config = LeakCanary.config.run {
      copy(eventListeners = eventListeners + EventListener { event ->
        if (event is HeapAnalysisDone<*>) {
          latch.countDown()
        }
      })
    }
    val hprof = writeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(hprof))
    activityTestRule.launchActivity(intent)
    require(latch.await(5, SECONDS)) {
      "Heap analysis not done within 5 seconds of starting import"
    }
    onView(withText("1 Heap Dump")).check(matches(isDisplayed()))
    onView(withText("1 Distinct Leak")).perform(click())
    onView(withText("Holder.leak")).check(matches(isDisplayed()))
  }

  private fun writeHeapDump(block: HprofWriterHelper.() -> Unit): File {
    val hprofBytes = dumpToBytes {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("Samsing")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = IntHolder(47)
      }
      block()
    }
    return testFolder.newFile("temp.hprof").apply {
      writeBytes(hprofBytes)
      require(exists()) {
        "$this does not exist"
      }
      require(length().toInt() == hprofBytes.size) {
        "$this has size ${length()} instead of expected ${hprofBytes.size}"
      }
    }
  }

  private fun tryAndRestoreConfig(block: () -> Unit) {
    val original = LeakCanary.config
    try {
      block()
    } finally {
      LeakCanary.config = original
    }
  }

}
