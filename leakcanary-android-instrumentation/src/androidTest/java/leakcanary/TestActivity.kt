package leakcanary

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.squareup.leakcanary.instrumentation.test.R

class TestActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_test)
  }
}
