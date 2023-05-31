package org.leakcanary.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.sqrt
import org.leakcanary.screens.TreemapLayout.NodeLayout

/**
 * Based on https://github.com/d3/d3-hierarchy Treemap implementation.
 *
 */
class TreemapLayout<T>(
  private val paddingInner: (NodeLayout<T>) -> Float = { 0f },
  private val paddingLeft: (NodeLayout<T>) -> Float = { 0f },
  private val paddingTop: (NodeLayout<T>) -> Float = { 0f },
  private val paddingRight: (NodeLayout<T>) -> Float = { 0f },
  private val paddingBottom: (NodeLayout<T>) -> Float = { 0f }
) {

  data class NodeValue<T>(
    // TODO Float
    val value: Int,
    val content: T,
    val children: List<NodeValue<T>>
  )

  interface NodeLayout<T> {
    val value: Int
    val content: T
    val depth: Int
    val children: List<NodeLayout<T>>
    val topLeft: Offset
    val size: Size
  }

  fun layout(
    root: NodeValue<T>,
    size: Size
  ): NodeLayout<T> {
    val rootLayout = root.mapNode()
    rootLayout.x0 = 0f
    rootLayout.y0 = 0f
    rootLayout.x1 = size.width
    rootLayout.y1 = size.height
    val paddingStack = mutableListOf(0f)
    rootLayout.depthFirstTraversal { node ->
      positionNode(node, paddingStack)
    }
    return rootLayout
  }

  private fun <T> NodeValue<T>.mapNode(depth: Int = 0): InternalNodeLayout<T> {
    return InternalNodeLayout(
      value = value,
      content = content,
      depth = depth,
      children = children.map { it.mapNode(depth + 1) }
    )
  }

  private fun positionNode(
    node: InternalNodeLayout<T>,
    paddingStack: MutableList<Float>
  ) {
    var p = paddingStack[node.depth]
    var x0 = node.x0 + p
    var y0 = node.y0 + p
    var x1 = node.x1 - p
    var y1 = node.y1 - p
    if (x1 < x0) {
      x1 = (x0 + x1) / 2
      x0 = x1
    }
    if (y1 < y0) {
      y1 = (y0 + y1) / 2
      y0 = y1
    }
    if (node.children.isNotEmpty()) {
      // TODO Debug with examples to check that padding is right.
      val halfPaddingInner = paddingInner(node) / 2
      val childDepth = node.depth + 1
      if (childDepth < paddingStack.size) {
        paddingStack[childDepth] = halfPaddingInner
      } else {
        paddingStack += halfPaddingInner
      }
      p = halfPaddingInner
      x0 += paddingLeft(node) - p
      y0 += paddingTop(node) - p
      x1 -= paddingRight(node) - p
      y1 -= paddingBottom(node) - p
      if (x1 < x0) {
        x1 = (x0 + x1) / 2
        x0 = x1
      }
      if (y1 < y0) {
        y1 = (y0 + y1) / 2
        y0 = y1
      }
      squarifyRatio(phi, node, x0, y0, x1, y1)
    }
  }

  private data class InternalNodeLayout<T>(
    override val value: Int,
    override val content: T,
    override val depth: Int,
    override val children: List<InternalNodeLayout<T>>
  ) : NodeLayout<T> {

    var x0 = 0f
    var y0 = 0f
    var x1 = 0f
    var y1 = 0f

    override val topLeft: Offset
      get() = Offset(x0, y0)
    override val size: Size
      get() = Size(x1 - x0, y1 - y0)
  }

  private class Row(
    val value: Int,
    val children: List<InternalNodeLayout<*>>
  )

  private fun squarifyRatio(
    ratio: Float,
    parent: InternalNodeLayout<*>,
    x0Start: Float,
    y0Start: Float,
    x1Start: Float,
    y1Start: Float
  ) {
    // TODO Check out resquarity and try that?
    val nodes = parent.children

    var value = parent.value

    var x0 = x0Start
    var y0 = y0Start
    var x1 = x1Start
    var y1 = y1Start

    var i0 = 0
    var i1 = 0
    val n = nodes.size
    while (i0 < n) {
      val dx = x1 - x0
      val dy = y1 - y0

      // Find the next non-empty node.
      var sumValue: Int
      do {
        sumValue = nodes[i1].value
        i1++
      } while (sumValue == 0 && i1 < n)
      var minValue = sumValue
      var maxValue = sumValue
      val alpha = max(dy / dx, dx / dy) / (value * ratio)
      var beta = sumValue * sumValue * alpha
      var minRatio = max(maxValue / beta, beta / minValue)

      // Keep adding nodes while the aspect ratio maintains or improves.
      while (i1 < n) {
        val nodeValue = nodes[i1].value
        sumValue += nodeValue
        if (nodeValue < minValue) minValue = nodeValue
        if (nodeValue > maxValue) maxValue = nodeValue
        beta = sumValue * sumValue * alpha
        val newRatio = max(maxValue / beta, beta / minValue)
        if (newRatio > minRatio) {
          sumValue -= nodeValue
          break
        }
        minRatio = newRatio
        i1++
      }

      // Position and record the row orientation.
      val row = Row(
        value = sumValue,
        children = nodes.slice(i0 until i1)
      )

      if (dx < dy) {
        val initialY0 = y0
        val lastY = if (value > 0) {
          y0 += dy * sumValue / value
          y0
        } else {
          y1
        }
        treemapDice(row, x0, initialY0, x1, lastY)
      } else {
        val initialX0 = x0
        val lastX = if (value > 0) {
          x0 += dx * sumValue / value
          x0
        } else {
          x1
        }
        treemapSlice(row, initialX0, y0, lastX, y1)
      }
      value -= sumValue
      i0 = i1
    }
  }

  private fun treemapSlice(
    parent: Row,
    x0Start: Float,
    y0Start: Float,
    x1Start: Float,
    y1Start: Float
  ) {
    val nodes = parent.children

    val k = if (parent.value > 0) {
      (y1Start - y0Start) / parent.value
    } else {
      0f
    }

    var y0 = y0Start

    var i = -1
    val n = nodes.size
    while (++i < n) {
      val node = nodes[i]
      node.x0 = x0Start
      node.x1 = x1Start
      node.y0 = y0
      y0 += node.value.toFloat() * k
      node.y1 = y0
    }
  }

  private fun treemapDice(
    parent: Row,
    x0Start: Float,
    y0Start: Float,
    x1Start: Float,
    y1Start: Float
  ) {
    val nodes = parent.children

    val n = nodes.size
    val k = if (parent.value > 0) {
      (x1Start - x0Start) / parent.value
    } else {
      0f
    }

    var i = -1
    var x0 = x0Start
    while (++i < n) {
      val node = nodes[i]
      node.y0 = y0Start
      node.y1 = y1Start
      node.x0 = x0
      x0 += node.value.toFloat() * k
      node.x1 = x0
    }
  }

  companion object {
    // Golden ratio
    val phi = (1 + sqrt(5f)) / 2
  }
}

/**
 * Invokes [callback] for node and each descendant in pre-order traversal, such that a given node
 * is only visited after all of its ancestors have already been visited. [callback] is passed the
 * current descendant, the zero-based traversal index, and this node.
 */
inline fun <T, N : NodeLayout<T>> N.depthFirstTraversal(callback: (N) -> Unit) {
  var node = this
  val nodes = ArrayDeque<N>()
  nodes += node
  while (nodes.isNotEmpty()) {
    node = nodes.removeLast()
    callback(node)
    val children = node.children
    if (children.isNotEmpty()) {
      for (child in children.reversed()) {
        @Suppress("UNCHECKED_CAST")
        nodes.addLast(child as N)
      }
    }
  }
}
