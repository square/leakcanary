package leakcanary

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.CountDownLatch

class TestService : Service() {

  override fun onCreate() {
    super.onCreate()
    created.countDown()
  }

  override fun onDestroy() {
    super.onDestroy()
    destroyed.countDown()
  }

  override fun onBind(intent: Intent): IBinder? = null

  companion object {
    var created = CountDownLatch(1)
      private set

    var destroyed = CountDownLatch(1)
      private set

    val isDestroyed: Boolean
      get() = destroyed.count == 0L

    fun reset() {
      created = CountDownLatch(1)
      destroyed = CountDownLatch(1)
    }
  }
}
