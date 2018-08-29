package vladsaif.syncedit.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import vladsaif.syncedit.plugin.SoundProvider
import vladsaif.syncedit.plugin.audioview.toolbar.AudioToolWindowManager
import vladsaif.syncedit.plugin.audioview.waveform.WaveformModel
import java.io.IOException
import javax.sound.sampled.UnsupportedAudioFileException

class OpenAudioAction : AnAction() {

  override fun actionPerformed(e: AnActionEvent?) {
    e ?: return
    val project = e.project ?: return
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
    FileChooser.chooseFile(descriptor, project, project.projectFile) {
      openAudio(project, it)
    }
  }

  companion object {

    fun openAudio(project: Project, file: VirtualFile): WaveformModel? {
      return try {
        file.inputStream.use { SoundProvider.getAudioFileFormat(it) }
        AudioToolWindowManager.openAudioFile(project, file)
      } catch (ex: UnsupportedAudioFileException) {
        errorUnsupportedAudioFile(project, file)
        null
      } catch (ex: IOException) {
        errorIO(project, ex)
        null
      }
    }
  }
}