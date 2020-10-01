package leakcanary.internal.activity.screen

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class LeakTraceWrapperTest {

  @Test fun `short string stays identical`() {
    val string = "12\n"

    val wrapped = LeakTraceWrapper.wrap(string, 4)

    assertThat(wrapped).isEqualTo(string)
  }

  @Test fun `string at width stays identical`() {
    val string = "1234\n"

    val wrapped = LeakTraceWrapper.wrap(string, 4)

    assertThat(wrapped).isEqualTo(string)
  }

  @Test fun `string at width no newline stays identical`() {
    val string = "1234"

    val wrapped = LeakTraceWrapper.wrap(string, 4)

    assertThat(wrapped).isEqualTo(string)
  }

  @Test fun `string wrapped without newline stays has no trailing newline`() {
    val string = "12 34"

    val wrapped = LeakTraceWrapper.wrap(string, 4)

    assertThat(wrapped).isEqualTo("12\n34")
  }

  @Test fun `wrap line at space removing space`() {
    val string = "12 34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 4)

    assertThat(wrapped).isEqualTo("12\n34\n")
  }

  @Test fun `wrap line at space keeps prefix`() {
    val prefix = "│   "
    val string = "${prefix}12 34\n"

    val wrapped = LeakTraceWrapper.wrap(string, prefix.length + 4)

    assertThat(wrapped).isEqualTo("${prefix}12\n${prefix}34\n")
  }

  @Test fun `wrap line at space keeps non breaking space`() {
    val string = "\u200B 12 34\n"
    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("\u200B 12\n\u200B 34\n")
  }

  @Test fun `wrap line at period keeping period`() {
    val string = "12.34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 4)

    assertThat(wrapped).isEqualTo("12.\n34\n")
  }

  @Test fun `two periods wraps at last period`() {
    val string = "1.2.34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("1.2.\n34\n")
  }

  @Test fun `no space or period is wrapped at max width`() {
    val string = "1234\n"

    val wrapped = LeakTraceWrapper.wrap(string, 2)

    assertThat(wrapped).isEqualTo("12\n34\n")
  }

  @Test fun `two consecutive periods wraps at last period`() {
    val string = "12..34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("12..\n34\n")
  }

  @Test fun `period and space wraps at last`() {
    val string = "12. 34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("12.\n34\n")
  }

  @Test fun `space and period wraps at last`() {
    val string = "12 .34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("12 .\n34\n")
  }

  @Test fun `period and separated space wraps at last`() {
    val string = "1.2 34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("1.2\n34\n")
  }

  @Test fun `several spaces are all removed`() {
    val string = "12  34\n"

    val wrapped = LeakTraceWrapper.wrap(string, 5)

    assertThat(wrapped).isEqualTo("12\n34\n")
  }

  @Test fun `several periods all keeping period`() {
    val string = "12...34\n"
    val wrapped = LeakTraceWrapper.wrap(string, 4)
    assertThat(wrapped).isEqualTo("12..\n.34\n")
  }

  @Test fun `prefix applied to all lines`() {
    val prefix = "│  "
    val part1 = "A word"
    val part2 = "and a"
    val part3 = "pk.g"
    val string = "\n${prefix}$part1 $part2 $part3"
    val wrappedString = LeakTraceWrapper.wrap(string, prefix.length + part1.length + 1)

    assertThat(wrappedString).isEqualTo(
        """
${prefix}$part1
${prefix}$part2
${prefix}$part3"""
    )
  }

  @Test fun `underline is positioned under a word on a line that will not be wrapped`() {
    val string = """
│  A word and a pk.g
│         ~~~      
        """
    val wrappedString = LeakTraceWrapper.wrap(string, 10)

    assertThat(wrappedString).isEqualTo(
        """
│  A word
│  and a
│  ~~~
│  pk.g
"""
    )
  }

  @Test fun `underline is positioned under a word on last line`() {
    val string = """
│  A word and a pk.g
│                  ~           
        """
    val wrappedString = LeakTraceWrapper.wrap(string, 10)

    assertThat(wrappedString).isEqualTo(
        """
│  A word
│  and a
│  pk.g
│     ~
"""
    )
  }

  @Test fun `underline within multiline string`() {
    val string = """
├─ com.example.FooFooFooFooFooFooFoo instance
│    Leaking: UNKNOWN
│    ↓ FooFooFooFooFooFooFoo.barbarbarbarbarbarbarbar
│                            ~~~~~~~~~~~~~~~~~~~~~~~~
"""

    val wrappedString = LeakTraceWrapper.wrap(string, 30)

    assertThat(wrappedString).isEqualTo(
        """
├─ com.example.
│  FooFooFooFooFooFooFoo
│  instance
│    Leaking: UNKNOWN
│    ↓ FooFooFooFooFooFooFoo.
│    barbarbarbarbarbarbarbar
│    ~~~~~~~~~~~~~~~~~~~~~~~~
"""
    )
  }

  @Test fun `a real leak trace is correctly wrapped`() {
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

    assertThat(wrappedString).isEqualTo(
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
"""
    )
  }
}