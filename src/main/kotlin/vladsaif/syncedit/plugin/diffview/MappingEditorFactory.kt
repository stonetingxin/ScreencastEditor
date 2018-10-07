package vladsaif.syncedit.plugin.diffview

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.codeInsight.daemon.impl.analysis.HighlightLevelUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorMarkupModel
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.ui.WindowWrapperBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.addToStdlib.cast
import vladsaif.syncedit.plugin.IRange
import vladsaif.syncedit.plugin.ScreencastFile
import vladsaif.syncedit.plugin.WordData
import vladsaif.syncedit.plugin.audioview.toolbar.addAction
import vladsaif.syncedit.plugin.audioview.waveform.impl.MouseDragListener
import vladsaif.syncedit.plugin.lang.script.psi.BlockVisitor
import vladsaif.syncedit.plugin.lang.script.psi.TimeOffsetParser
import vladsaif.syncedit.plugin.lang.transcript.psi.TranscriptPsiFile
import java.awt.*
import java.awt.GridBagConstraints.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ChangeListener
import javax.swing.event.MouseInputAdapter
import kotlin.math.max
import kotlin.math.min

object MappingEditorFactory {

  fun showWindow(model: ScreencastFile) {
    val holder = JPanel(GridBagLayout())
    holder.border = BorderFactory.createEmptyBorder()
    val (splitter, diffViewModel) = createSplitter(model)
    val vertical = JPanel(GridBagLayout())
    vertical.add(
        createTitle(),
        GridBagBuilder().fill(HORIZONTAL).gridx(0).gridy(0).weightx(1.0).weighty(0.0).done()
    )
    vertical.add(
        splitter,
        GridBagBuilder().fill(BOTH).gridx(0).gridy(1).weightx(1.0).weighty(1.0).done()
    )
    holder.add(
        createToolbar(diffViewModel).component,
        GridBagBuilder().anchor(WEST).fill(HORIZONTAL).gridx(0).gridy(0).weighty(0.0).weightx(1.0).done()
    )
    holder.add(vertical, GridBagBuilder().fill(BOTH).gridx(0).gridy(1).weightx(1.0).weighty(1.0).done())
    val wrapper = WindowWrapperBuilder(WindowWrapper.Mode.MODAL, holder)
        .setTitle("Script (${model.file})")
        .build()
    wrapper.component.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent?) {
        e ?: return
        if (e.keyCode == KeyEvent.VK_Z && (SystemInfo.isMac && e.isMetaDown || e.isControlDown)) {
          diffViewModel.undo()
        }
        if (e.keyCode == KeyEvent.VK_Z && e.isShiftDown && (SystemInfo.isMac && e.isMetaDown || e.isControlDown)) {
          diffViewModel.redo()
        }
      }
    })
    Disposer.register(wrapper, diffViewModel)
    wrapper.show()
  }

  private fun createBoxedPanel(isVertical: Boolean = true): JPanel {
    val panel = JPanel()
    val box = BoxLayout(panel, if (isVertical) BoxLayout.Y_AXIS else BoxLayout.X_AXIS)
    panel.layout = box
    panel.border = BorderFactory.createEmptyBorder()
    return panel
  }

  private fun createTitle(): JComponent {
    val panel = createBoxedPanel(false)
    panel.add(TitledSeparator("Transcript"))
    val right = TitledSeparator("Script")
    right.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
    panel.add(right)
    panel.border = BorderFactory.createEmptyBorder(0, JBUI.scale(3), 0, JBUI.scale(3))
    return panel
  }

  private fun createToolbar(mappingViewModel: MappingViewModel): ActionToolbar {
    val group = DefaultActionGroup()
    group.addAction(
        "Bind",
        "Associate selected",
        AllIcons.General.Add,
        { mappingViewModel.bindSelected() },
        { !mappingViewModel.selectedItems.empty && !mappingViewModel.editorSelectionRange.empty })
    group.addAction(
        "Unbind",
        "Remove associations",
        AllIcons.General.Remove,
        { mappingViewModel.unbindSelected() },
        { !mappingViewModel.selectedItems.empty })
    group.addAction(
        "Undo",
        "Undo last action",
        AllIcons.Actions.Undo,
        { mappingViewModel.undo() },
        { mappingViewModel.isUndoAvailable })
    group.addAction(
        "Redo",
        "",
        AllIcons.Actions.Redo,
        { mappingViewModel.redo() },
        { mappingViewModel.isRedoAvailable })
    group.addAction(
        "Reset",
        "Reset all changes",
        AllIcons.Actions.Rollback,
        { mappingViewModel.resetChanges() },
        { mappingViewModel.isResetAvailable })
    return ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true)
  }

  private fun createSplitter(model: ScreencastFile): Pair<Splitter, MappingViewModel> {
    val pane = createTranscriptView(model.transcriptPsi!!)
    val editorView = createEditorPanel(model.project, model.scriptPsi!!)
    val textPanel = pane.viewport.view as TextItemPanel
    val diffViewModel = MappingViewModel(MappingEditorModel(model), editorView.editor as EditorEx, textPanel.cast())
    val leftDragListener = object : MouseDragListener() {
      override fun onDrag(point: Point) {
        diffViewModel.selectHeightRange(IRange(min(dragStartEvent!!.y, point.y), max(dragStartEvent!!.y, point.y)))
      }
    }
    textPanel.addMouseListener(leftDragListener)
    textPanel.addMouseMotionListener(leftDragListener)
    val clickListener = object : MouseInputAdapter() {
      override fun mouseClicked(e: MouseEvent?) {
        e ?: return
        val number = textPanel.findItemNumber(e.point)
        if (number < 0 || !SwingUtilities.isLeftMouseButton(e)) {
          diffViewModel.selectedItems = IRange.EMPTY_RANGE
        } else {
          diffViewModel.selectedItems = IRange(number, number)
        }
      }
    }
    textPanel.addMouseListener(clickListener)
    textPanel.addMouseMotionListener(object : MouseInputAdapter() {
      override fun mouseExited(e: MouseEvent?) {
        diffViewModel.hoveredItem = -1
      }

      override fun mouseMoved(e: MouseEvent?) {
        e ?: return
        val number = textPanel.findItemNumber(e.point)
        diffViewModel.hoveredItem = if (number < 0) -1 else number
      }
    })
    val painter = SplitterPainter(
        diffViewModel,
        createTranscriptLocator(pane.viewport),
        createScriptLocator(editorView.editor)
    )
    val splitter = Splitter(
        leftComponent = pane,
        rightComponent = editorView,
        painter = painter
    )
    diffViewModel.addChangeListener(ChangeListener {
      pane.revalidate()
      pane.repaint()
      splitter.repaint()
    })
    editorView.editor.scrollPane.verticalScrollBar.addAdjustmentListener {
      splitter.divider.repaint()
    }
    pane.verticalScrollBar.addAdjustmentListener {
      splitter.divider.repaint()
    }
    Disposer.register(diffViewModel, editorView)
    return splitter to diffViewModel
  }

  private fun createTranscriptLocator(viewport: JViewport): Locator {
    return object : Locator {
      override fun locate(item: Int): Pair<Int, Int> {
        val panel = viewport.view as TextItemPanel
        val offset = viewport.viewPosition.y
        val (top, bottom) = panel.getCoordinates(item)
        return (top - offset) to (bottom - offset)
      }
    }
  }

  private fun createScriptLocator(editor: EditorEx): Locator {
    return object : Locator {
      override fun locate(item: Int): Pair<Int, Int> {
        val offset = editor.scrollPane.viewport.viewPosition.y
        val y = editor.logicalPositionToXY(LogicalPosition(item, 0)).y - offset
        return y to (y + editor.lineHeight)
      }
    }
  }

  private fun createTranscriptView(psi: TranscriptPsiFile): JBScrollPane {
    val panel = TextItemPanel()
    panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
    val words = psi.model!!.data!!.words
    var needAddSeparator = false
    for (word in words) {
      if (word.state == WordData.State.EXCLUDED) continue
      if (needAddSeparator) {
        panel.add(Box.createRigidArea(Dimension(0, JBUI.scale(1))))
      }
      panel.add(TextItem(word.filteredText))
      needAddSeparator = true
    }
    val pane = JBScrollPane(panel)
    pane.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
    pane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_ALWAYS
    pane.border = BorderFactory.createEmptyBorder()
    return pane
  }

  private fun createEditorPanel(project: Project, psi: PsiFile): MyEditorWrapper {
    val transformed = transformFile(project, psi)
    val factory = EditorFactory.getInstance()
    val kind = EditorKind.DIFF
    val editor = factory.createViewer(transformed.viewProvider.document!!, project, kind) as EditorEx
    configureEditor(project, editor, transformed)
    return MyEditorWrapper(editor)
  }

  private fun transformFile(project: Project, psi: PsiFile): PsiFile {
    val text = transformScript(psi as KtFile)
    return PsiFileFactory.getInstance(project).createFileFromText(
        psi.name,
        psi.fileType,
        text,
        0,
        true,
        false
    )
  }

  private fun transformTranscript(project: Project, psi: PsiFile): String {
    return PsiDocumentManager.getInstance(project).getDocument(psi)!!.text
  }

  /**
   * Remove timeOffset statements from document.
   *
   * Generally, this function should use psi structure of the file
   * and delete [org.jetbrains.kotlin.psi.KtCallExpression]'s which correspond to timeOffset(Long).
   * But for now, it is an over-complicated way of reaching the goal.
   */
  private fun transformScript(psi: KtFile): String {
    val document = psi.viewProvider.document!!
    val linesToDelete = mutableListOf<Int>()
    BlockVisitor.visit(psi) {
      if (TimeOffsetParser.isTimeOffset(it)) {
        linesToDelete.add(document.getLineNumber(it.textOffset))
      }
    }
    return document.text.split("\n")
        .withIndex()
        .filter { (line, _) -> line !in linesToDelete }
        .joinToString(separator = "\n") { it.value }
  }


  private fun configureEditor(project: Project, editor: EditorEx, psi: PsiFile) {
    with(editor) {
      setFile(psi.virtualFile)
      highlighter = createEditorHighlighter(project, psi)
      verticalScrollbarOrientation = EditorEx.VERTICAL_SCROLLBAR_RIGHT
      if (!project.isDisposed) {
        settings.setTabSize(CodeStyle.getIndentOptions(psi).TAB_SIZE)
        settings.setUseTabCharacter(CodeStyle.getIndentOptions(psi).USE_TAB_CHARACTER)
      }
      settings.isCaretRowShown = false
      settings.isShowIntentionBulb = false
      settings.isFoldingOutlineShown = false
      (markupModel as EditorMarkupModel).isErrorStripeVisible = false
      gutterComponentEx.setShowDefaultGutterPopup(false)
      gutterComponentEx.revalidateMarkup()
      foldingModel.isFoldingEnabled = false
      UIUtil.removeScrollBorder(component)
      HighlightLevelUtil.forceRootHighlighting(psi, FileHighlightingSetting.SKIP_HIGHLIGHTING)
    }
  }

  private fun createEditorHighlighter(project: Project, psi: PsiFile): EditorHighlighter {
    val language = psi.language
    val file = psi.viewProvider.virtualFile
    val highlighterFactory = EditorHighlighterFactory.getInstance()
    val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file)
    return highlighterFactory.createEditorHighlighter(syntaxHighlighter, EditorColorsManager.getInstance().globalScheme)
  }

  private class MyEditorWrapper(val editor: Editor) : JPanel(BorderLayout()), Disposable {

    init {
      add(editor.component)
    }

    override fun dispose() {
      if (!editor.isDisposed) {
        EditorFactory.getInstance().releaseEditor(editor)
      }
    }
  }
}