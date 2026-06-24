package leakcanary.internal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import leakcanary.DeletableObjectReporter
import leakcanary.internal.ViewModelClearedWatcher.Companion.install
import shark.SharkLog

/**
 * [AndroidXFragmentDestroyWatcher] calls [install] to add a spy [ViewModel] in every
 * [ViewModelStoreOwner] instance (i.e. FragmentActivity and Fragment). [ViewModelClearedWatcher]
 * holds on to the map of [ViewModel]s backing its store. When [ViewModelClearedWatcher] receives
 * the [onCleared] callback, it adds each live [ViewModel] from the store to the [DeletableObjectReporter].
 */
internal class ViewModelClearedWatcher(
  storeOwner: ViewModelStoreOwner,
  private val deletableObjectReporter: DeletableObjectReporter
) : ViewModel() {

  // We could call ViewModelStore#keys with a package spy in androidx.lifecycle instead,
  // however that was added in 2.1.0 and we support AndroidX first stable release. viewmodel-2.0.0
  // does not have ViewModelStore#keys. All versions currently have the mMap field.
  private val viewModelMap: Map<String, ViewModel>? = try {
    val storeClass = ViewModelStore::class.java
    val mapField = try {
      storeClass.getDeclaredField("map")
    } catch (exception: NoSuchFieldException) {
      // Field name changed from mMap to map with Kotlin conversion
      // https://cs.android.com/androidx/platform/frameworks/support/+/8aa6ca1c924ab10d263b21b99b8790d5f0b50cc6
      storeClass.getDeclaredField("mMap")
    }
    mapField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    mapField[storeOwner.viewModelStore] as Map<String, ViewModel>
  } catch (ignored: Exception) {
    SharkLog.d(ignored) { "Could not find ViewModelStore map of view models" }
    null
  }

  override fun onCleared() {
    viewModelMap?.values?.forEach { viewModel ->
      deletableObjectReporter.expectDeletionFor(
        viewModel, "${viewModel::class.java.name} received ViewModel#onCleared() callback"
      )
    }
  }

  companion object {
    fun install(
      storeOwner: ViewModelStoreOwner,
      deletableObjectReporter: DeletableObjectReporter
    ) {
      val provider = ViewModelProvider(storeOwner, object : Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          ViewModelClearedWatcher(storeOwner, deletableObjectReporter) as T
      })
      provider.get(ViewModelClearedWatcher::class.java)
    }
  }
}
