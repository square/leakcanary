//[shark-log](../../../index.md)/[shark](../index.md)/[SharkLog](index.md)

# SharkLog

[jvm]\
object [SharkLog](index.md)

Central Logger for all Shark artifacts. Set [logger](logger.md) to change where these logs go.

## Types

| Name | Summary |
|---|---|
| [Logger](-logger/index.md) | [jvm]<br>interface [Logger](-logger/index.md) |

## Functions

| Name | Summary |
|---|---|
| [d](d.md) | [jvm]<br>inline fun [d](d.md)(message: () -&gt; [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html))<br>inline fun [d](d.md)(throwable: [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html), message: () -&gt; [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [logger](logger.md) | [jvm]<br>@[Volatile](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-volatile/index.html)<br>var [logger](logger.md): [SharkLog.Logger](-logger/index.md)? = null |
