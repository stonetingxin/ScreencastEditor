package vladsaif.syncedit.plugin.editor.toolbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import icons.ScreencastEditorIcons.*
import org.jetbrains.kotlin.idea.KotlinIcons
import vladsaif.syncedit.plugin.actions.openScript
import vladsaif.syncedit.plugin.actions.openTranscript
import vladsaif.syncedit.plugin.actions.saveChanges
import vladsaif.syncedit.plugin.editor.EditorPane
import vladsaif.syncedit.plugin.editor.audioview.waveform.WaveformController
import vladsaif.syncedit.plugin.model.ScreencastFile
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.event.MouseInputAdapter

object ScreencastToolWindow {
  private const val myToolWindowId = "Screencast Editor"

  private fun getToolWindow(project: Project): ToolWindow {
    val manager = ToolWindowManager.getInstance(project)
    val toolWindow = manager.getToolWindow(myToolWindowId)
      ?: manager.registerToolWindow(myToolWindowId, true, ToolWindowAnchor.BOTTOM).also {
        it.contentManager.addContentManagerListener(object : ContentManagerListener {
          override fun contentAdded(event: ContentManagerEvent) = Unit

          override fun contentRemoveQuery(event: ContentManagerEvent) = Unit

          override fun selectionChanged(event: ContentManagerEvent) = Unit

          override fun contentRemoved(event: ContentManagerEvent) {
            it.setAvailable(false, null)
          }
        })
      }
    toolWindow.icon = SCREENCAST_TOOL_WINDOW
    return toolWindow
  }

  fun openScreencastFile(screencast: ScreencastFile) {
    val editorPane = EditorPane(screencast)
    val audioPanel = ActionPanel(editorPane)
    audioPanel.disposeAction = { editorPane.waveformController!!.stopImmediately() }
    val controlPanel = ActionPanel(audioPanel)
    controlPanel.addActionGroups(createMainActionGroup(screencast, controlPanel, editorPane))
    audioPanel.addActionGroups(createAudioRelatedActionGroup(editorPane, controlPanel))
    val content = ContentFactory.SERVICE.getInstance().createContent(controlPanel, screencast.name, false)
    val toolWindow = getToolWindow(screencast.project)
    addForAllLeaves(FocusRequestor(controlPanel, screencast.project), controlPanel)
    content.setPreferredFocusedComponent { controlPanel }
    Disposer.register(controlPanel, audioPanel)
    Disposer.register(content, controlPanel)
    Disposer.register(content, screencast)
    Disposer.register(content, Disposable {
      with(screencast) {
        for (file in listOfNotNull(scriptViewFile, transcriptFile)) {
          FileEditorManager.getInstance(screencast.project).closeFile(file)
        }
      }
    })
    if (editorPane.waveformView != null) {
      Disposer.register(content, editorPane.waveformView.model)
    }
    toolWindow.contentManager.removeAllContents(true)
    toolWindow.contentManager.addContent(content)
    toolWindow.setAvailable(true, null)
    toolWindow.activate {
      IdeFocusManager.getInstance(screencast.project).requestFocus(controlPanel, true)
    }
  }

  private fun addForAllLeaves(focus: FocusRequestor, component: JComponent) {
    val components = component.components
    if (components.isEmpty()) {
      component.addMouseMotionListener(focus)
      component.addMouseListener(focus)
    } else {
      for (x in components) {
        addForAllLeaves(focus, x as JComponent)
      }
    }
  }

  private fun createMainActionGroup(
    screencast: ScreencastFile,
    parent: JComponent,
    parentDisposable: Disposable
  ): ActionGroup {
    with(ActionGroupBuilder(parent, parentDisposable)) {
      add("Undo", "Undo last change", AllIcons.Actions.Undo, screencast::undo, screencast::isUndoAvailable)
      add("Redo", "Redo last undo", AllIcons.Actions.Redo, screencast::redo, screencast::isRedoAvailable)
//      add("Reproduce screencast", "Reproduce screencast", AllIcons.Ide.Macro.Recording_1, {
//        ApplicationManager.getApplication().invokeLater {
          //          KotlinCompileUtil.compileAndRun(screencast.getPlayScript())
//        }
//      })
      separator()
      add("Open transcript", "Open transcript in editor", TRANSCRIPT, {
        openTranscript(screencast)
      })
      add("Open GUI script", "Open GUI script in editor", KotlinIcons.SCRIPT, {
        openScript(screencast)
      })
      separator()
      add("Save changes", "Save edited screencast", AllIcons.Actions.Menu_saveall, {
        saveChanges(screencast)
      })
      return done()
    }
  }

  private fun createAudioRelatedActionGroup(
    editorPane: EditorPane,
    focusComponent: JComponent
  ): ActionGroup {
    with(ActionGroupBuilder(focusComponent, editorPane)) group@{
      val zoomController = editorPane.zoomController
      with(editorPane.waveformController!!) {
        val playPauseAction = object : AnAction() {

          override fun actionPerformed(e: AnActionEvent) {
            if (playState !is WaveformController.PlayState.Playing) {
              play()
            } else {
              pause()
            }
            update(e)
          }

          override fun update(e: AnActionEvent) {
            e.presentation.icon = if (playState is WaveformController.PlayState.Playing) PAUSE else PLAY
          }
        }
        add(playPauseAction)
        add("Stop", "Stop audio", STOP, this::stopImmediately) {
          playState !is WaveformController.PlayState.Stopped
        }
        separator()
        add("Clip", "Clip audio", DELETE, this::cutSelected, this::hasSelection)
        add("Mute", "Mute selected", VOLUME_OFF, this::muteSelected, this::hasSelection)
        separator()
        add("Zoom in", "Zoom in", AllIcons.Graph.ZoomIn, zoomController::zoomIn)
        add("Zoom out", "Zoom out", AllIcons.Graph.ZoomOut, zoomController::zoomOut)
        return done()
      }
    }
  }

  private class FocusRequestor(val parent: JComponent, val project: Project) : MouseInputAdapter() {
    override fun mouseClicked(e: MouseEvent?) {
      requestFocus()
    }

    override fun mousePressed(e: MouseEvent?) {
      requestFocus()
    }

    private fun requestFocus() {
      IdeFocusManager.getInstance(project).requestFocus(parent, true)
    }
  }

  private class ActionGroupBuilder(val parent: JComponent, val parentDisposable: Disposable) {
    private val myActionGroup = DefaultActionGroup()

    fun add(
      what: String,
      desc: String?,
      icon: Icon?,
      action: () -> Unit,
      checkAvailable: () -> Boolean = { true }
    ) {
      val anAction = object : AnAction(what, desc, icon) {
        override fun actionPerformed(event: AnActionEvent) {
          action()
        }

        override fun update(e: AnActionEvent) {
          e.presentation.isEnabled = checkAvailable()
        }
      }
      // TODO
      getShortcutSet(what)?.let { anAction.registerCustomShortcutSet(it, parent, parentDisposable) }
      myActionGroup.add(anAction)
    }

    fun separator() {
      myActionGroup.add(Separator.getInstance())
    }

    fun add(action: AnAction) {
      getShortcutSet("Play/Pause")?.let { action.registerCustomShortcutSet(it, parent, parentDisposable) }
      myActionGroup.add(action)
    }

    fun done() = myActionGroup

    private fun getShortcutSet(action: String): ShortcutSet? = when (action) {
      "Play/Pause" -> CustomShortcutSet.fromString("ctrl P")
      "Stop" -> CustomShortcutSet.fromString("ctrl alt P")
      "Undo" -> KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_UNDO)
      "Redo" -> KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_REDO)
      "Clip" -> KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_CUT)
      "Mute" -> CustomShortcutSet.fromString("ctrl M")
      "Zoom in" -> CustomShortcutSet.fromString("ctrl EQUALS")
      "Zoom out" -> CustomShortcutSet.fromString("ctrl MINUS")
      "Save changes" -> CustomShortcutSet.fromString("ctrl S")
      else -> null
    }
  }
}