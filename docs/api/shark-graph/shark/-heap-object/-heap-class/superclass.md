[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapClass](index.md) / [superclass](./superclass.md)

# superclass

`val superclass: `[`HeapObject.HeapClass`](index.md)`?`

The [HeapClass](index.md) representing the superclass of this [HeapClass](index.md). If this [HeapClass](index.md)
represents either the [Object](https://docs.oracle.com/javase/6/docs/api/java/lang/Object.html) class or a primitive type, then
null is returned. If this [HeapClass](index.md) represents an array class then the
[HeapClass](index.md) object representing the [Object](https://docs.oracle.com/javase/6/docs/api/java/lang/Object.html) class is returned.

