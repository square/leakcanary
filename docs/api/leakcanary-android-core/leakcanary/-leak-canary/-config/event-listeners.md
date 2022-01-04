//[leakcanary-android-core](../../../../index.md)/[leakcanary](../../index.md)/[LeakCanary](../index.md)/[Config](index.md)/[eventListeners](event-listeners.md)

# eventListeners

[androidJvm]\
val [eventListeners](event-listeners.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[EventListener](../../-event-listener/index.md)&gt;

Listeners for LeakCanary events. See [EventListener.Event](../../-event-listener/-event/index.md) for the list of events and which thread they're sent from. You most likely want to keep this list and add to it, or remove a few entries but not all entries. Each listener is independent and provides additional behavior which you can disable by not excluding it:

// No cute canary toast (very sad!)\
LeakCanary.config = LeakCanary.config.run {\
  copy(\
    eventListeners = eventListeners.filter {\
      it !is ToastEventListener\
    }\
  )\
}
