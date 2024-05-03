package leakcanary.internal

import android.content.Intent
import java.io.Serializable

/**
 * Wraps an Intent to serialize it as its URI string.
 */
internal class SerializableIntent(intent: Intent) : Serializable {

  private val uri = intent.toUri(0)

  // Intent is not Serializable
  @Transient
  private var _intent: Intent? = intent

  val intent: Intent
    get() = _intent.run {
      this ?: Intent.parseUri(uri, 0)
        .apply { _intent = this }
    }
}
