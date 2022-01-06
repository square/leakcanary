package leakcanary

import leakcanary.EventListener.Event

/**
 * Forwards events to the [EventListener] provided by lazyEventListener which
 * is evaluated lazily, when the first comes in.
 */
class LazyForwardingEventListener(
  lazyEventListener: () -> EventListener
) : EventListener {

  private val eventListenerDelegate by lazy(lazyEventListener)

  override fun onEvent(event: Event) {
    eventListenerDelegate.onEvent(event)
  }
}
