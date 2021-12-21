package leakcanary.internal.activity.db

import android.view.View
import leakcanary.internal.activity.db.Io.OnIo
import leakcanary.internal.navigation.onScreenExiting
import leakcanary.internal.friendly.checkMainThread
import leakcanary.internal.friendly.mainHandler
import java.util.concurrent.Executors

internal object Io {

  private val serialExecutor = Executors.newSingleThreadExecutor()

  fun interface OnIo {
    fun updateUi(updateUi: View.() -> Unit)
  }

  private class IoContext : OnIo {
    var updateUi: (View.() -> Unit)? = null

    override fun updateUi(updateUi: View.() -> Unit) {
      this.updateUi = updateUi
    }
  }

  fun execute(block: () -> Unit) {
    serialExecutor.execute(block)
  }

  fun execute(
    view: View,
    block: OnIo.() -> Unit
  ) {
    checkMainThread()
    val viewWrapper: VolatileObjectRef<View> = VolatileObjectRef(view)
    view.onScreenExiting {
      viewWrapper.element = null
    }
    serialExecutor.execute backgroundExecute@{
      if (viewWrapper.element == null) {
        return@backgroundExecute
      }
      val context = IoContext()
      block(context)
      val updateUi = context.updateUi
      if (viewWrapper.element != null && updateUi != null) {
        mainHandler.post mainThreadPost@{
          val attachedView = viewWrapper.element ?: return@mainThreadPost
          updateUi(attachedView)
        }
      }
    }
  }

  /**
   * Similar to kotlin.jvm.internal.Ref.ObjectRef but volatile
   */
  private class VolatileObjectRef<T>(
    @Volatile
    var element: T? = null
  )
}

internal fun View.executeOnIo(block: OnIo.() -> Unit) {
  Io.execute(this, block)
}
