package leakcanary

class DefaultOnRetainInstanceListener : OnRetainInstanceListener {

  override fun onCountChanged(retainedCount: Int) {
    //No-op for now
  }
}