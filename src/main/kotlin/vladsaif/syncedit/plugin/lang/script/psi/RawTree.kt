package vladsaif.syncedit.plugin.lang.script.psi

import com.github.tmatek.zhangshasha.EditableTreeNode
import com.github.tmatek.zhangshasha.TreeNode
import com.github.tmatek.zhangshasha.TreeOperation
import org.jetbrains.kotlin.psi.KtFile
import vladsaif.syncedit.plugin.lang.script.psi.RawTreeNode.IndexedEntry.CodeEntry
import vladsaif.syncedit.plugin.lang.script.psi.RawTreeNode.IndexedEntry.Offset
import vladsaif.syncedit.plugin.util.end
import vladsaif.syncedit.plugin.util.length

sealed class RawTreeData {
  val data
    get() = when (this) {
      is Label -> label
      is CodeData -> code.code
      is Root -> ""
    }

  class Label(val label: String, val isBlock: Boolean) : RawTreeData() {
    override fun toString(): String {
      return "Label:'$label'"
    }
  }

  class CodeData(val code: Code) : RawTreeData() {
    override fun toString(): String {
      return "Code:'${code.code}'"
    }
  }

  object Root : RawTreeData()
}

class RawTreeNode(var data: RawTreeData) : EditableTreeNode {
  private var myParent: TreeNode? = null
  private val myChildren: MutableList<RawTreeNode> = mutableListOf()

  fun add(node: RawTreeNode) {
    myChildren.add(node)
  }

  fun addAll(nodes: Collection<RawTreeNode>) {
    myChildren.addAll(nodes)
  }

  override fun setParent(newParent: TreeNode?) {
    myParent = newParent
  }

  override fun getParent(): TreeNode? = myParent

  override fun positionOfChild(child: TreeNode?): Int = myChildren.indexOf(child)

  override fun getChildren(): MutableList<out TreeNode> = myChildren

  override fun addChildAt(child: TreeNode, position: Int) {
    myChildren.add(position, child as RawTreeNode)
  }

  override fun renameNodeTo(other: TreeNode) {
    val current = data
    val newString = (other as RawTreeNode).data.data
    if (current is RawTreeData.CodeData && newString != current.data) {
      val newCode = when (current.code) {
        is Statement -> Statement(newString, current.code.timeOffset)
        is Block -> Block(newString, current.code.timeRange, current.code.innerBlocks)
      }
      data = RawTreeData.CodeData(newCode)
    }
  }

  override fun deleteChild(child: TreeNode) {
    myChildren.remove(child)
  }

  override fun toString(): String {
    return "RawTreeNode{$data}"
  }

  override fun getTransformationCost(operation: TreeOperation, other: TreeNode?): Int {
    if (data == RawTreeData.Root && other != null && (other as RawTreeNode).data == RawTreeData.Root) {
      return 0
    }
    if (data == RawTreeData.Root) {
      return 100000
    }
    return when (operation) {
      TreeOperation.OP_DELETE_NODE -> 10
      TreeOperation.OP_INSERT_NODE -> 10
      TreeOperation.OP_RENAME_NODE -> if (data.data == (other as? RawTreeNode)?.data?.data) 0 else 10
    }
  }

  override fun cloneNode(): TreeNode {
    return RawTreeNode(data)
  }

  sealed class IndexedEntry(var indexRange: IntRange) {
    class Offset(index: Int, val value: Int) : IndexedEntry(index..index) {
      override fun toString(): String {
        return "${indexRange.start}: Offset($value)"
      }
    }

    class CodeEntry(startIndex: Int, val value: String, val isBlock: Boolean) : IndexedEntry(startIndex..startIndex) {
      override fun toString(): String {
        return if (!isBlock) "${indexRange.start}: Code('$value')"
        else "$indexRange: Code('$value')"
      }
    }
  }

  companion object {

    fun buildFromPsi(ktFile: KtFile): RawTreeNode {
      val nodes = BlockVisitor.fold<RawTreeNode>(ktFile) { element, list, isBlock ->
        if (isBlock) {
          RawTreeNode(
            RawTreeData.Label(element.text.substringBefore("{").trim { it.isWhitespace() }, true)
          ).also {
            it.myChildren.addAll(list)
            for (node in list) {
              node.myParent = it
            }
          }
        } else {
          RawTreeNode(RawTreeData.Label(element.text, false))
        }
      }
      val root = RawTreeNode(RawTreeData.Root)
      root.myChildren.addAll(nodes)
      for (node in nodes) {
        node.myParent = root
      }
      return root
    }

    fun buildPlainTree(root: RawTreeNode, oldRange: IntRange? = null): List<IndexedEntry> {
      val list = mutableListOf<IndexedEntry>()
      var index = 0
      var lastOffset = 0
      if (oldRange != null) {
        list.add(Offset(index++, oldRange.start))
        lastOffset = oldRange.start
      }
      for (child in root.myChildren) {
        val (newIndex, newLastOffset) = processNode(child, list, index, lastOffset)
        index = newIndex
        lastOffset = newLastOffset
      }
      if (oldRange != null) {
        if (lastOffset < oldRange.end) {
          list.add(Offset(index, oldRange.end))
        }
      }
      return list
    }

    fun toCodeModel(root: RawTreeNode, oldRange: IntRange? = null): CodeModel {
      val list = buildPlainTree(root, oldRange)
      val code = list.filterIsInstance(IndexedEntry.CodeEntry::class.java)
      val output = markRawTreeOutput(
        code,
        listOf(Offset(-1, 0)) + list.filterIsInstance(Offset::class.java)
      )
      val (codes, _) = fold<Code>(code, 0) { entry, inner ->
        val (k, total) = output.fraction[entry] ?: 1 to 1
        val range = output.time[entry]!!
        val correctedRange = (range.start + range.length / total * (k - 1))..range.end
        if (entry.isBlock) {
          Block(entry.value, correctedRange, inner)
        } else {
          Statement(entry.value, correctedRange.start)
        }
      }
      return CodeModel(codes)
    }

    private fun <T> fold(
      code: List<IndexedEntry.CodeEntry>,
      startIndex: Int,
      until: Int = Int.MAX_VALUE,
      operation: (IndexedEntry.CodeEntry, List<T>) -> T
    ): Pair<List<T>, Int> {
      val result = mutableListOf<T>()
      var index = startIndex
      while (index < code.size) {
        val x = code[index++]
        if (x.indexRange.end > until) {
          return result to index - 1
        }
        if (!x.isBlock) {
          result.add(operation(x, listOf()))
        } else {
          val (res, newIndex) = fold(code, index, x.indexRange.end, operation)
          index = newIndex
          result.add(operation(x, res))
        }
      }
      return result to index
    }

    private fun processNode(
      node: RawTreeNode,
      list: MutableList<IndexedEntry>,
      startIndex: Int,
      offset: Int
    ): Pair<Int, Int> {
      val data = node.data
      var index = startIndex
      var lastOffset = offset
      when (data) {
        is RawTreeData.CodeData -> {
          when (data.code) {
            is Block -> {
              if (lastOffset <= data.code.startTime) {
                list.add(Offset(index++, data.code.startTime))
                lastOffset = data.code.startTime
              }
              val block = CodeEntry(index++, data.data, true)
              list.add(block)
              for (child in node.myChildren) {
                val (newIndex, newLastOffset) = processNode(child, list, index, lastOffset)
                index = newIndex
                lastOffset = newLastOffset
              }
              block.indexRange = block.indexRange.start..index++
              if (lastOffset <= data.code.endTime) {
                list.add(Offset(index++, data.code.endTime))
                lastOffset = data.code.endTime
              }
            }
            is Statement -> {
              if (lastOffset <= data.code.timeOffset) {
                list.add(Offset(index++, data.code.timeOffset))
              }
              list.add(CodeEntry(index++, data.data, false))
            }
          }
        }
        is RawTreeData.Label -> {
          list.add(CodeEntry(index++, data.data, data.isBlock))
          for (child in node.myChildren) {
            val (newIndex, newLastOffset) = processNode(child, list, index, lastOffset)
            index = newIndex
            lastOffset = newLastOffset
          }
        }
      }
      return index to lastOffset
    }

    private data class Output(
      val time: Map<IndexedEntry.CodeEntry, IntRange>,
      val fraction: Map<IndexedEntry.CodeEntry, Pair<Int, Int>>
    )

    private fun markRawTreeOutput(
      expressions: List<IndexedEntry.CodeEntry>,
      offsets: List<IndexedEntry.Offset>
    ): Output {
      val intervals = offsets.sortedBy { it.indexRange.start }
      var j = 0
      val time = mutableMapOf<IndexedEntry.CodeEntry, IntRange>()
      val fraction = mutableMapOf<IndexedEntry.CodeEntry, Pair<Int, Int>>()
      val sameRangeElements = mutableMapOf<IntRange, MutableList<IndexedEntry.CodeEntry>>()
      out@ for (expr in expressions) {
        while (j < intervals.size) {
          val interval = intervals[j]
          if (interval.indexRange.end <= expr.indexRange.start) {
            while (j < intervals.size && intervals[j].indexRange.end <= expr.indexRange.start) j++
            j--
            var i = j + 1
            while (i < intervals.size) {
              if (expr.indexRange.end <= intervals[i].indexRange.start) {
                val range = intervals[j].value..intervals[i].value
                time[expr] = range
                sameRangeElements.computeIfAbsent(range) { mutableListOf() }.add(expr)
                continue@out
              }
              i++
            }
            break
          }
          j++
        }
        time[expr] = IntRange.EMPTY
      }
      for (list in sameRangeElements.values) {
        for ((index, expr) in list.withIndex()) {
          fraction[expr] = index + 1 to list.size
        }
      }
      return Output(time, fraction)
    }
  }
}