package shark

import shark.AndroidXFragmentLifecycleStatus.Alive
import shark.AndroidXFragmentLifecycleStatus.Destroyed
import shark.AndroidXFragmentLifecycleStatus.Unknown
import shark.AndroidXFragmentVersion.ONE_ZERO
import shark.HeapObject.HeapInstance

internal const val ANDROIDX_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"

/**
 * The `androidx.fragment` behaviors that LeakCanary needs to tell apart, detected from the symbols
 * available in a heap dump.
 *
 * There is an equivalent runtime detection in
 * `leakcanary.internal.AndroidXFragmentDestroyWatcher`, keep the two in sync.
 */
internal enum class AndroidXFragmentVersion {

  /**
   * `androidx.fragment` 1.0.0, where `Fragment#initState()` leaves `Fragment.mLifecycleRegistry`
   * alone. A fragment that went through `Fragment#onDestroy()` keeps a `DESTROYED` lifecycle state
   * for as long as it stays in memory.
   */
  ONE_ZERO,

  /**
   * `androidx.fragment` 1.1.0 and higher, where `Fragment#initState()` starts by calling
   * `initLifecycle()`, which replaces `Fragment.mLifecycleRegistry` with a brand new
   * `LifecycleRegistry`. A fragment that was removed and destroyed is therefore reset back to an
   * `INITIALIZED` lifecycle state, which is what makes it legal to add that very same instance to a
   * `FragmentManager` again.
   *
   * https://cs.android.com/androidx/platform/frameworks/support/+/b651a62816e8005a0e1c7fbd3435c01820f4c015
   */
  ONE_ONE_OR_HIGHER,
  ;

  companion object {
    /**
     * `Fragment.mMaxState` was added by the same release that introduced the lifecycle reset.
     *
     * We look for a field declared by `Fragment` rather than for a class added by that release
     * (such as `androidx.fragment.app.FragmentViewLifecycleOwner`) because a heap dump only
     * contains the classes that were loaded, and `FragmentViewLifecycleOwner` is only loaded once a
     * fragment creates a view. A field declaration on the other hand is always part of the dump of
     * the `Fragment` class, which we know is loaded since we are looking at one of its instances.
     */
    private const val MAX_STATE_FIELD_NAME = "mMaxState"

    fun of(fragment: HeapInstance): AndroidXFragmentVersion =
      if (fragment[ANDROIDX_FRAGMENT_CLASS_NAME, MAX_STATE_FIELD_NAME] != null) {
        ONE_ONE_OR_HIGHER
      } else {
        ONE_ZERO
      }
  }
}

/**
 * Where an `androidx.fragment.app.Fragment` instance sits in its lifecycle, as far as we can tell
 * from a heap dump.
 */
internal sealed class AndroidXFragmentLifecycleStatus {

  /**
   * The fragment went through `Fragment#onDestroy()` and is not managed by a `FragmentManager`
   * anymore, so it should no longer be reachable.
   */
  class Destroyed(val reason: String) : AndroidXFragmentLifecycleStatus()

  /**
   * The fragment is either still in use, or was never added to a `FragmentManager`. Either way it
   * is expected to be reachable.
   */
  class Alive(val reason: String) : AndroidXFragmentLifecycleStatus()

  /**
   * We could not determine the status, so we should stay out of the way of the leak status
   * computation and only report what we found.
   */
  class Unknown(val label: String) : AndroidXFragmentLifecycleStatus()
}

internal fun HeapInstance.androidXFragmentLifecycleStatus(): AndroidXFragmentLifecycleStatus {
  val lifecycleRegistry = this[ANDROIDX_FRAGMENT_CLASS_NAME, "mLifecycleRegistry"]
    ?.valueAsInstance
    ?: return Unknown("Fragment.mLifecycleRegistry = null")

  val state = lifecycleRegistry.lifecycleRegistryState

  if (state == "DESTROYED") {
    return Destroyed("Fragment.mLifecycleRegistry.state is DESTROYED")
  }

  if (state != "INITIALIZED" || AndroidXFragmentVersion.of(this) == ONE_ZERO) {
    // Up to androidx.fragment 1.0.0, INITIALIZED could only ever mean "never created", and from
    // 1.1.0 on every state but INITIALIZED still means the fragment is in use.
    return Alive("Fragment.mLifecycleRegistry.state is $state")
  }

  // From androidx.fragment 1.1.0 on, INITIALIZED is ambiguous: it is both the state of a fragment
  // that was never added to a FragmentManager, and the state that Fragment#initState() resets a
  // destroyed fragment to. The fields that initState() does *not* reset tell the two apart.

  val fragmentManager = this[ANDROIDX_FRAGMENT_CLASS_NAME, "mFragmentManager"]?.value
  if (fragmentManager != null && fragmentManager.isNonNullReference) {
    // initState() sets mFragmentManager to null, so a fragment that still has one is on its way in
    // rather than on its way out: it has been attached but not created yet.
    return Alive("Fragment.mLifecycleRegistry.state is INITIALIZED and Fragment.mFragmentManager is set")
  }

  // Fragment.mCalled is set to true by every lifecycle callback dispatch, starting with
  // Fragment#onAttach(), and initState() does not reset it. A fragment that is back to INITIALIZED
  // with mCalled set to true therefore went through the whole attach / detach cycle.
  // Every release of androidx.fragment declares mCalled, so we should always find it.
  val called = this[ANDROIDX_FRAGMENT_CLASS_NAME, "mCalled"]?.value?.asBoolean
    ?: return Unknown(
      "Fragment.mLifecycleRegistry.state is INITIALIZED and the Fragment.mCalled field could not" +
        " be found"
    )

  return if (called) {
    Destroyed(
      "Fragment.mLifecycleRegistry.state was reset to INITIALIZED by Fragment#initState() after" +
        " Fragment#onDestroy()"
    )
  } else {
    Alive("Fragment.mLifecycleRegistry.state is INITIALIZED and the fragment was never attached")
  }
}
