package vladsaif.syncedit.plugin.recognition

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.runBlocking
import vladsaif.syncedit.plugin.TranscriptModel
import vladsaif.syncedit.plugin.audioview.toolbar.OpenAudioAction
import vladsaif.syncedit.plugin.audioview.waveform.WaveformModel
import vladsaif.syncedit.plugin.lang.transcript.psi.InternalFileType
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.experimental.coroutineContext

class RecognizeAudioAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent?) {
        e ?: return
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        if (CredentialProvider.Instance.gSettings == null) {
            Messages.showWarningDialog(
                    e.project,
                    "Credentials for cloud service account should be set before recognition is used",
                    "Credentials not found"
            )
            return
        }
        descriptor.title = "Choose audio file"
        descriptor.description = "Choose audio file for cloud recognition"
        FileChooser.chooseFile(descriptor, e.project, e.project?.projectFile) { file: VirtualFile ->
            val model = OpenAudioAction.openAudio(project, File(file.path).toPath()) ?: return@chooseFile
            try {
                val recognizeTask = RecognizeTask(
                        e.project,
                        "Getting transcript for $file",
                        File(file.path).toPath(),
                        model
                )
                ProgressManager.getInstance().run(recognizeTask)
            } catch (ex: IOException) {
                Messages.showErrorDialog(e.project, ex.message, "I/O error occurred")
            }
        }
    }

    private class RecognizeTask(
            project: Project?,
            title: String,
            private val path: Path,
            private val waveformModel: WaveformModel
    ) : Task.Backgroundable(project, title, true) {

        private var job: Job? = null

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            runBlocking {
                job = coroutineContext[Job]
                val data = Files.newInputStream(path).use {
                    SpeechRecognizer.getDefault().recognize(it)
                }
                ApplicationManager.getApplication().invokeAndWait {
                    ApplicationManager.getApplication().runWriteAction {
                        val xml = PsiFileFactory.getInstance(project).createFileFromText(
                                "${FileUtil.getNameWithoutExtension(path.toFile())}.${InternalFileType.defaultExtension}",
                                InternalFileType,
                                data.toXml(),
                                0L,
                                true
                        )
                        indicator.stop()
                        FileEditorManager.getInstance(project).openFile(xml.virtualFile, true)
                        val model = TranscriptModel.fileModelMap[xml.virtualFile]!!
                        model.data = data
                        waveformModel.transcriptModel = model
                    }
                }
            }
        }

        override fun onCancel() {
            job?.cancel()
            super.onCancel()
        }
    }
}
