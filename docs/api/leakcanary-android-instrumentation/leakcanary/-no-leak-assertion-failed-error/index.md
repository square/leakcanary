//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[NoLeakAssertionFailedError](index.md)

# NoLeakAssertionFailedError

[androidJvm]\
class [NoLeakAssertionFailedError](index.md)(heapAnalysis: HeapAnalysisSuccess) : [AssertionError](https://developer.android.com/reference/kotlin/java/lang/AssertionError.html)

Thrown when using the [NoLeakAssertionFailedError.throwOnApplicationLeaks](-companion/throw-on-application-leaks.md) HeapAnalysisReporter

## Constructors

| | |
|---|---|
| [NoLeakAssertionFailedError](-no-leak-assertion-failed-error.md) | [androidJvm]<br>fun [NoLeakAssertionFailedError](-no-leak-assertion-failed-error.md)(heapAnalysis: HeapAnalysisSuccess) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [addSuppressed](index.md#282858770%2FFunctions%2F1786365805) | [androidJvm]<br>fun [addSuppressed](index.md#282858770%2FFunctions%2F1786365805)(p0: [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html)) |
| [fillInStackTrace](index.md#-1102069925%2FFunctions%2F1786365805) | [androidJvm]<br>open fun [fillInStackTrace](index.md#-1102069925%2FFunctions%2F1786365805)(): [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html) |
| [getLocalizedMessage](index.md#1043865560%2FFunctions%2F1786365805) | [androidJvm]<br>open fun [getLocalizedMessage](index.md#1043865560%2FFunctions%2F1786365805)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [getStackTrace](index.md#2050903719%2FFunctions%2F1786365805) | [androidJvm]<br>open fun [getStackTrace](index.md#2050903719%2FFunctions%2F1786365805)(): [Array](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-array/index.html)&lt;[StackTraceElement](https://developer.android.com/reference/kotlin/java/lang/StackTraceElement.html)&gt; |
| [getSuppressed](index.md#672492560%2FFunctions%2F1786365805) | [androidJvm]<br>fun [getSuppressed](index.md#672492560%2FFunctions%2F1786365805)(): [Array](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-array/index.html)&lt;[Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html)&gt; |
| [initCause](index.md#-418225042%2FFunctions%2F1786365805) | [androidJvm]<br>open fun [initCause](index.md#-418225042%2FFunctions%2F1786365805)(p0: [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html)): [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html) |
| [printStackTrace](index.md#-1769529168%2FFunctions%2F1786365805) | [androidJvm]<br>open fun [printStackTrace](index.md#-1769529168%2FFunctions%2F1786365805)()<br>open fun [printStackTrace](index.md#1841853697%2FFunctions%2F1786365805)(p0: [PrintStream](https://developer.android.com/reference/kotlin/java/io/PrintStream.html))<br>open fun [printStackTrace](index.md#1175535278%2FFunctions%2F1786365805)(p0: [PrintWriter](https://developer.android.com/reference/kotlin/java/io/PrintWriter.html)) |
| [setStackTrace](index.md#2135801318%2FFunctions%2F1786365805) | [androidJvm]<br>open fun [setStackTrace](index.md#2135801318%2FFunctions%2F1786365805)(p0: [Array](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-array/index.html)&lt;[StackTraceElement](https://developer.android.com/reference/kotlin/java/lang/StackTraceElement.html)&gt;) |

## Properties

| Name | Summary |
|---|---|
| [cause](index.md#-654012527%2FProperties%2F1786365805) | [androidJvm]<br>open val [cause](index.md#-654012527%2FProperties%2F1786365805): [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html)? |
| [heapAnalysis](heap-analysis.md) | [androidJvm]<br>val [heapAnalysis](heap-analysis.md): HeapAnalysisSuccess |
| [message](index.md#1824300659%2FProperties%2F1786365805) | [androidJvm]<br>open val [message](index.md#1824300659%2FProperties%2F1786365805): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)? |
