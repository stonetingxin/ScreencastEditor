package vladsaif.syncedit.plugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import kotlinx.coroutines.*
import vladsaif.syncedit.plugin.actions.tools.toPath
import vladsaif.syncedit.plugin.model.Screencast
import vladsaif.syncedit.plugin.sound.SoundProvider
import vladsaif.syncedit.plugin.util.ExEDT
import java.io.File
import java.io.IOException
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.coroutines.CoroutineContext

class ImportAudioAction(val screencast: Screencast) :
  AnAction("Import audio", "Import audio", AllIcons.ToolbarDecorator.Import),
  CoroutineScope {
  private var myIsInProgress = false

  override val coroutineContext: CoroutineContext
    get() = Job()

  override fun actionPerformed(e: AnActionEvent) {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
    FileChooser.chooseFile(descriptor, screencast.project, null) { file ->
      myIsInProgress = true
      launch {
        withContext(ExEDT) {
          try {
            try {
              with(SoundProvider) {
                withContext(Dispatchers.IO) {
                  val format = getAudioFileFormat(File(file.path))
                  if (!isConversionSupported(getWaveformPcmFormat(format.format), format.format)) {
                    throw UnsupportedAudioFileException()
                  }
                }
                withContext(ExEDT) {
                  screencast.performModification {
                    importAudio(file.toPath())
                  }
                }
              }
            } catch (ex: IOException) {
              errorIO(screencast.project, ex.message)
            } catch (ex: UnsupportedAudioFileException) {
              errorUnsupportedAudioFile(screencast.project, file.toPath())
            }
          } finally {
            myIsInProgress = false
          }
        }
      }
    }
  }

  override fun update(e: AnActionEvent) {
    with(e.presentation) {
      isEnabled = true // TODO
    }
  }
}