//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[LogcatEventListener](index.md)/[onEvent](on-event.md)

# onEvent

[androidJvm]\
open override fun [onEvent](on-event.md)(event: [EventListener.Event](../-event-listener/-event/index.md))

[onEvent](on-event.md) is always called from the thread the events are emitted from, which is documented for each event. This enables you to potentially block a chain of events, waiting for some pre work to be done.
