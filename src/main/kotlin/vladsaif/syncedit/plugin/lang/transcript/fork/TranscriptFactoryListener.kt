package vladsaif.syncedit.plugin.lang.transcript.fork

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import vladsaif.syncedit.plugin.ScreencastFile
import vladsaif.syncedit.plugin.lang.transcript.psi.TranscriptFileType
import vladsaif.syncedit.plugin.lang.transcript.refactoring.InplaceRenamer

object TranscriptFactoryListener : EditorFactoryListener {
  lateinit var project: Project
  var isInitialized = false
    private set

  @Synchronized
  fun initialize(project: Project) {
    if (isInitialized) return
    this.project = project
    EditorFactory.getInstance().addEditorFactoryListener(this, ApplicationManager.getApplication())
    isInitialized = true
  }

  override fun editorCreated(event: EditorFactoryEvent) {
    val psi = PsiDocumentManager.getInstance(project).getPsiFile(event.editor.document) ?: return
    if (psi.virtualFile?.fileType != TranscriptFileType) return
    val editor = event.editor as EditorEx
    if (editor.document.getUserData(InplaceRenamer.GUARDED_BLOCKS) == null) {
      val marker = editor.document.createGuardedBlock(0, editor.document.textLength).apply {
        isGreedyToLeft = true
        isGreedyToRight = true
      }
      editor.putUserData(ScreencastFile.KEY, psi.virtualFile.getUserData(ScreencastFile.KEY))
      editor.document.putUserData(InplaceRenamer.GUARDED_BLOCKS, listOf(marker))
    }
    editor.colorsScheme.setColor(EditorColors.READONLY_FRAGMENT_BACKGROUND_COLOR, null)
    EditorActionManager.getInstance().setReadonlyFragmentModificationHandler(editor.document) { }
  }
}