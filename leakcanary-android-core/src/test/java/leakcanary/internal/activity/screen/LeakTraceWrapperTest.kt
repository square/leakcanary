package leakcanary.internal.activity.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class LeakTraceWrapperTest {
  @Test
  fun `A simple example`() {
    val string = """
│  A word and a pk.g 
        """
    val wrappedString = LeakTraceWrapper.wrap(string, 10)

    assertEquals("""
│  A word
│  and a
│  pk.g
""", wrappedString)
  }

  @Test
  fun `Underline is positioned under a word on a line that will not be wrapped`() {
    val string = """
│  A word and a pk.g
│         ~~~      
        """
    val wrappedString = LeakTraceWrapper.wrap(string, 10)

    assertEquals("""
│  A word
│  and a
│  ~~~
│  pk.g
""", wrappedString)
  }

  @Test
  fun `Underline is positioned under a word on last line`() {
    val string = """
│  A word and a pk.g
│               ~~           
        """
    val wrappedString = LeakTraceWrapper.wrap(string, 10)

    assertEquals("""
│  A word
│  and a
│  pk.g
│  ~~
""", wrappedString)
  }

  @Test
  fun `A more complex example with underline`() {
    val string = """
├─ com.example.FooFooFooFooFooFooFoo instance
│    Leaking: UNKNOWN
│  ↓ FooFooFooFooFooFooFoo.barbarbarbarbarbarbarbar
│                          ~~~~~~~~~~~~~~~~~~~~~
"""

    val wrappedString = LeakTraceWrapper.wrap(string, 30)

    assertEquals(
        """
├─ com.example.
├─ FooFooFooFooFooFooFoo
├─ instance
│    Leaking: UNKNOWN
│  ↓ FooFooFooFooFooFooFoo.
│  barbarbarbarbarbarbarbar
│  ~~~~~~~~~~~~~~~~~~~~~
""", wrappedString
    )
  }

  @Test
  fun `A real leak trace is correctly wrapped`() {
    val string = """
┬───
│ GC Root: System class
│
├─ leakcanary.internal.InternalAppWatcher class
│    Leaking: NO (ExampleApplication↓ is not leaking and a class is never leaking)
│    ↓ static InternalAppWatcher.application
├─ com.example.leakcanary.ExampleApplication instance
│    Leaking: NO (Application is a singleton)
│    ExampleApplication does not wrap an activity context
│    ↓ ExampleApplication.leakedViews
│                         ~~~~~~~~~~~
├─ java.util.ArrayList instance
│    Leaking: UNKNOWN
│    ↓ ArrayList.array
│                ~~~~~
├─ java.lang.Object[] array
│    Leaking: UNKNOWN
│    ↓ Object[].[0]
│               ~~~
├─ android.widget.TextView instance
│    Leaking: YES (View.mContext references a destroyed activity)
│    mContext instance of com.example.leakcanary.MainActivity with mDestroyed = true
│    View#mParent is set
│    View#mAttachInfo is null (view detached)
│    View.mWindowAttachCount = 1
│    ↓ TextView.mContext
╰→ com.example.leakcanary.MainActivity instance
​     Leaking: YES (ObjectWatcher was watching this because com.example.leakcanary.MainActivity received Activity#onDestroy() callback and Activity#mDestroyed is true)
​     key = b3dd6589-560d-48dc-9fbb-ab8300e5752b
​     watchDurationMillis = 5117
​     retainedDurationMillis = 110
"""

    val wrappedString = LeakTraceWrapper.wrap(string, 80)

    assertEquals(
        """
┬───
│ GC Root: System class
│
├─ leakcanary.internal.InternalAppWatcher class
│    Leaking: NO (ExampleApplication↓ is not leaking and a class is never
│    leaking)
│    ↓ static InternalAppWatcher.application
├─ com.example.leakcanary.ExampleApplication instance
│    Leaking: NO (Application is a singleton)
│    ExampleApplication does not wrap an activity context
│    ↓ ExampleApplication.leakedViews
│                         ~~~~~~~~~~~
├─ java.util.ArrayList instance
│    Leaking: UNKNOWN
│    ↓ ArrayList.array
│                ~~~~~
├─ java.lang.Object[] array
│    Leaking: UNKNOWN
│    ↓ Object[].[0]
│               ~~~
├─ android.widget.TextView instance
│    Leaking: YES (View.mContext references a destroyed activity)
│    mContext instance of com.example.leakcanary.MainActivity with mDestroyed =
│    true
│    View#mParent is set
│    View#mAttachInfo is null (view detached)
│    View.mWindowAttachCount = 1
│    ↓ TextView.mContext
╰→ com.example.leakcanary.MainActivity instance
​     Leaking: YES (ObjectWatcher was watching this because com.example.
​     leakcanary.MainActivity received Activity#onDestroy() callback and
​     Activity#mDestroyed is true)
​     key = b3dd6589-560d-48dc-9fbb-ab8300e5752b
​     watchDurationMillis = 5117
​     retainedDurationMillis = 110
""", wrappedString
    )
  }

  @Test
  fun `Splitting by dot character and keeping it, then splitting by space, works as expected`() {
    val string = "com.package is an application"

    val splitted = string.splitAndKeep('.')

    assertEquals(listOf("com.", "package is an application"), splitted)

    val splitted2 = splitted.flatMap {
      it.split(' ')
    }

    assertEquals(listOf("com.", "package", "is", "an", "application"), splitted2)
  }

  @Test
  fun `Splitting by dot character and keeping it, works as expected`() {
    val string =
      "UserProfile_Fragment.transaction↓ UserProfile_Fragment.transaction↓ UserProfile_Fragment.transaction"

    val splitted = string.splitAndKeep('.')

    assertEquals(
        listOf(
            "UserProfile_Fragment.", "transaction↓ UserProfile_Fragment.",
            "transaction↓ UserProfile_Fragment.", "transaction"
        ), splitted
    )
  }
}