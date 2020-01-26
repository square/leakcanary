package leakcanary.internal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import leakcanary.AppWatcher.Config
import leakcanary.ObjectWatcher
import leakcanary.internal.ViewModelClearedWatcher.Companion.install

/**
 * [AndroidXFragmentDestroyWatcher] calls [install] to add a spy [ViewModel] in every
 * [ViewModelStoreOwner] instance (i.e. FragmentActivity and Fragment). [ViewModelClearedWatcher]
 * holds on to the map of [ViewModel]s backing its store. When [ViewModelClearedWatcher] receives
 * the [onCleared] callback, it adds each live [ViewModel] from the store to the [ObjectWatcher].
 */
internal class ViewModelClearedWatcher(
  storeOwner: ViewModelStoreOwner,
  private val objectWatcher: ObjectWatcher,
  private val configProvider: () -> Config
) : ViewModel() {

  private val viewModelMap: Map<String, ViewModel>?

  init {
    // We could call ViewModelStore#keys with a package spy in androidx.lifecycle instead,
    // however that was added in 2.1.0 and we support AndroidX first stable release. viewmodel-2.0.0
    // does not have ViewModelStore#keys. All versions currently have the mMap field.
    viewModelMap = try {
      val mMapField = ViewModelStore::class.java.getDeclaredField("mMap")
      mMapField.isAccessible = true
      @Suppress("UNCHECKED_CAST")
      mMapField[storeOwner.viewModelStore] as Map<String, ViewModel>
    } catch (ignored: Exception) {
      null
    }
  }

  override fun onCleared() {
    if (viewModelMap != null && configProvider().watchViewModels) {
      viewModelMap.values.forEach { viewModel ->
        objectWatcher.watch(
            viewModel, "${viewModel::class.java.name} received ViewModel#onCleared() callback"
        )
      }
    }
  }

  companion object {
    fun install(
      storeOwner: ViewModelStoreOwner,
      objectWatcher: ObjectWatcher,
      configProvider: () -> Config
    ) {
      val provider = ViewModelProvider(storeOwner, object : Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T =
          ViewModelClearedWatcher(storeOwner, objectWatcher, configProvider) as T
      })
      provider.get(ViewModelClearedWatcher::class.java)
    }
  }
}