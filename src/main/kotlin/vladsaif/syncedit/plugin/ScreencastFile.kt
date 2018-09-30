package vladsaif.syncedit.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.exists
import com.intellij.util.io.isFile
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.withContext
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import vladsaif.syncedit.plugin.WordData.State.*
import vladsaif.syncedit.plugin.audioview.waveform.AudioDataModel
import vladsaif.syncedit.plugin.audioview.waveform.EditionModel
import vladsaif.syncedit.plugin.audioview.waveform.EditionModel.EditionType.*
import vladsaif.syncedit.plugin.audioview.waveform.impl.DefaultEditionModel
import vladsaif.syncedit.plugin.audioview.waveform.impl.SimpleAudioModel
import vladsaif.syncedit.plugin.format.ScreencastFileType
import vladsaif.syncedit.plugin.format.ScreencastZipper.EntryType
import vladsaif.syncedit.plugin.format.ScreencastZipper.EntryType.*
import vladsaif.syncedit.plugin.lang.script.psi.TimeOffsetParser
import vladsaif.syncedit.plugin.lang.transcript.fork.TranscriptFactoryListener
import vladsaif.syncedit.plugin.lang.transcript.psi.TranscriptFileType
import vladsaif.syncedit.plugin.lang.transcript.psi.TranscriptPsiFile
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import java.util.zip.ZipFile
import javax.swing.event.ChangeListener

/**
 * Main class that holds information about transcript data,
 * changes that were made of it or of audio data.
 *
 * @constructor
 * @throws java.io.IOException If I/O error occurs while reading xml
 */
class ScreencastFile(
    val project: Project,
    val file: Path
) : Disposable {
  private val myListeners: MutableSet<Listener> = ContainerUtil.newConcurrentSet()
  private var myEditionListenerEnabled = true
  private var myTranscriptListenerEnabled = true
  private val transcriptInputStream: InputStream?
    get() = getInputStreamByType(TRANSCRIPT_DATA)
  private val scriptInputStream: InputStream?
    get() = getInputStreamByType(SCRIPT)
  val name: String
    get() = file.fileName.toString().substringBefore(ScreencastFileType.defaultExtension)
  var audioDataModel: AudioDataModel? = null
    private set
  val audioInputStream: InputStream?
    get() = getInputStreamByType(AUDIO)
  val bindings: MutableMap<Int, RangeMarker> = mutableMapOf()
  val transcriptPsi: TranscriptPsiFile?
    get() = getPsi(transcriptFile)
  var transcriptFile: VirtualFile? = null
    private set
  var scriptFile: VirtualFile? = null
    private set
  val scriptDocument: Document?
    get() = scriptPsi?.viewProvider?.document
  val scriptPsi: KtFile?
    get() = getPsi(scriptFile)
  val editionModel: EditionModel = DefaultEditionModel()
  var data: TranscriptData? = null
    set(value) {
      ApplicationManager.getApplication().assertIsDispatchThread()
      if (value != field) {
        field = value
        fireTranscriptDataChanged()
      }
    }

  init {
    if (!file.exists()) {
      throw IOException("File ($file) does not exist.")
    }
    if (!file.isFile() || !file.toString().endsWith(ScreencastFileType.defaultExtension)) {
      throw IOException("Supplied file ($file) is not screencast.")
    }
  }

  suspend fun initialize() {
    withContext(CommonPool) {
      audioDataModel = audioInputStream?.let { SimpleAudioModel { audioInputStream!! } }
    }
    withContext(ExEDT) {
      if (!TranscriptFactoryListener.isInitialized) {
        TranscriptFactoryListener.initialize(project)
      }
      scriptFile = scriptInputStream?.let {
        createVirtualFile(
            "$name.kts",
            readContents(it),
            KotlinFileType.INSTANCE
        )
      }
      scriptFile?.putUserData(KEY, this)
      data = transcriptInputStream?.let { TranscriptData.createFrom(it) }
      synchronizeTranscriptWithEditionModel()
      addTranscriptDataListener(object : ScreencastFile.Listener {
        override fun onTranscriptDataChanged() {
          val files = listOfNotNull(transcriptFile, scriptFile)
          PsiDocumentManager.getInstance(project).reparseFiles(files, true)
        }
      })
    }
  }

  init {
    editionModel.addChangeListener(ChangeListener {
      if (myEditionListenerEnabled) {
        onEditionModelChanged()
      }
    })
  }

  fun addTranscriptDataListener(listener: Listener) {
    myListeners += listener
  }

  fun removeTranscriptDataListener(listener: Listener) {
    myListeners -= listener
  }

  fun replaceWords(replacements: List<Pair<Int, WordData>>) {
    if (replacements.isEmpty()) return
    data = data?.replaceWords(replacements)
  }

  fun renameWord(index: Int, text: String) {
    data = data?.renameWord(index, text)
  }

  fun changeRange(index: Int, newRange: IRange) {
    val word = data?.get(index) ?: return
    data = data?.replaceWords(listOf(index to word.copy(range = newRange)))
  }

  fun concatenateWords(indexRange: IRange) {
    data = data?.concatenateWords(indexRange)
  }

  fun excludeWords(indices: IntArray) {
    data = data?.excludeWords(indices)
  }

  fun excludeWord(index: Int) {
    data = data?.excludeWord(index)
  }

  fun showWords(indices: IntArray) {
    data = data?.showWords(indices)
  }

  fun muteWords(indices: IntArray) {
    data = data?.muteWords(indices)
  }

  fun createDefaultBinding() {
    val timedLines = TimeOffsetParser.parse(scriptPsi!!)
    val doc = scriptDocument!!
    val oldWords = data!!.words
    val sorted = timedLines.sortedBy { it.time.length }
    for ((index, word) in oldWords.withIndex()) {
      var intersection = IRange.EMPTY_RANGE
      var timeExtent = IRange.EMPTY_RANGE
      if (!word.range.empty) {
        for (x in sorted) {
          if (word.range.intersects(x.time)) {
            intersection += x.lines
            timeExtent += x.time
          }
          if (word.range in timeExtent) break
        }
      }
      val marker = if (word.range !in timeExtent || intersection.empty) {
        null
      } else {
        doc.createRangeMarker(doc.getLineStartOffset(intersection.start), doc.getLineEndOffset(intersection.end))
      }
      if (marker != null) {
        bindings[index] = marker
      }
    }
  }

  private fun onEditionModelChanged() {
    val preparedEditions = data?.let { getWordReplacements(it) } ?: return
    myTranscriptListenerEnabled = false
    try {
      replaceWords(preparedEditions)
    } finally {
      myTranscriptListenerEnabled = true
    }
  }

  private fun getWordReplacements(data: TranscriptData): List<Pair<Int, WordData>> {
    val audio = audioDataModel ?: return listOf()
    val editions = editionModel.editions.map { audio.frameRangeToMsRange(it.first) to it.second.toWordDataState() }
    val preparedEditions = mutableListOf<Pair<Int, WordData>>()
    for (edition in editions) {
      for ((i, word) in data.words.withIndex()) {
        if (word.range in edition.first && word.state != edition.second) {
          preparedEditions.add(i to word.copy(state = edition.second))
        }
      }
    }
    return preparedEditions.toList()
  }

  private fun EditionModel.EditionType.toWordDataState() = when (this) {
    CUT -> EXCLUDED
    MUTE -> MUTED
    NO_CHANGES -> PRESENTED
  }

  private fun synchronizeTranscriptWithEditionModel() {
    val words = data?.words ?: return
    val audio = audioDataModel ?: return
    editionModel.reset()
    for (word in words) {
      when (word.state) {
        EXCLUDED -> editionModel.cut(audio.msRangeToFrameRange(word.range))
        MUTED -> editionModel.mute(audio.msRangeToFrameRange(word.range))
        PRESENTED -> editionModel.undo(audio.msRangeToFrameRange(word.range))
      }
    }
  }

  private fun updateTranscript() {
    val nonNullData = data ?: return
    transcriptPsi?.virtualFile?.updateDoc { doc ->
      with(PsiDocumentManager.getInstance(project)) {
        doPostponedOperationsAndUnblockDocument(doc)
        doc.setText(nonNullData.text)
        commitDocument(doc)
      }
    }
  }

  private fun VirtualFile.updateDoc(action: (Document) -> Unit) {
    FileDocumentManager.getInstance().getDocument(this)?.let { doc ->
      ApplicationManager.getApplication().invokeAndWait {
        ApplicationManager.getApplication().runWriteAction {
          action(doc)
        }
      }
    }
  }


  private fun fireTranscriptDataChanged() {
    // Synchronize edition model with transcript data if it was changed in editor.
    // Also do not forger to reset coordinates cache.
    if (myTranscriptListenerEnabled) {
      if (transcriptPsi == null && data != null) {
        createTranscriptPsi(data!!)
        createDefaultBinding()
      }
      with(UndoManager.getInstance(project)) {
        if (!isRedoInProgress && !isUndoInProgress) {
          // But transcript should be updated always, otherwise it will cause errors.
          updateTranscript()
        }
      }
      editionModel.isNotificationSuppressed = true
      synchronizeTranscriptWithEditionModel()
      editionModel.isNotificationSuppressed = false
      try {
        myEditionListenerEnabled = false
        editionModel.fireStateChanged()
      } finally {
        myEditionListenerEnabled = true
      }
    }
    for (x in myListeners) {
      x.onTranscriptDataChanged()
    }
  }

  override fun dispose() {
    FILES.remove(file)
    myListeners.clear()
    data = null
  }

  override fun toString(): String {
    return "ScreencastFile(file=$file, myListeners=$myListeners, data=$data, scriptPsi=$scriptPsi, " +
        "transcriptPsi=$transcriptPsi, audioDataModel=$audioDataModel, editionModel=$editionModel)"
  }

  private inline fun <reified T> getPsi(virtualFile: VirtualFile?): T? {
    val file = virtualFile ?: return null
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
    return PsiDocumentManager.getInstance(project).getPsiFile(doc) as? T
  }

  private fun createTranscriptPsi(data: TranscriptData) {
    transcriptFile = createVirtualFile(
        "$name.transcript",
        data.text,
        TranscriptFileType
    )
    transcriptFile!!.putUserData(KEY, this)
  }

  private fun readContents(stream: InputStream): String {
    return stream.bufferedReader(Charset.forName("UTF-8")).use {
      it.lines().collect(Collectors.joining("\n"))
    }
  }

  private fun createVirtualFile(name: String, text: String, type: FileType): VirtualFile {
    return PsiFileFactory.getInstance(project).createFileFromText(
        name,
        type,
        text,
        0,
        true,
        false
    ).virtualFile
  }

  private fun getInputStreamByType(type: EntryType): InputStream? {
    val exists = ZipFile(file.toFile()).use { file ->
      file.entries()
          .asSequence()
          .any { it.comment == type.name }
    }
    return if (exists) ZipEntryInputStream(ZipFile(file.toFile()), type.name) else null
  }


  interface Listener {
    fun onTranscriptDataChanged()
  }


  private class ZipEntryInputStream(private val file: ZipFile, comment: String) : InputStream() {
    private val stream: InputStream = file.getInputStream(file.entries().asSequence().first { it.comment == comment })
    override fun read() = stream.read()
    override fun read(b: ByteArray?) = stream.read(b)
    override fun read(b: ByteArray?, off: Int, len: Int) = stream.read(b, off, len)
    override fun skip(n: Long) = stream.skip(n)
    override fun available() = stream.available()
    override fun reset() = stream.reset()
    override fun mark(readlimit: Int) = stream.mark(readlimit)
    override fun markSupported() = stream.markSupported()
    override fun close() {
      stream.close()
      file.close()
    }
  }


  companion object {
    private val FILES: MutableMap<Path, ScreencastFile> = ConcurrentHashMap()
    val KEY = Key.create<ScreencastFile>("SCREENCAST_FILE_KEY")

    fun get(file: Path): ScreencastFile? = FILES[file]

    suspend fun create(project: Project, file: Path): ScreencastFile {
      if (FILES[file] != null) {
        throw IllegalStateException("Object associated with this file has been already created.")
      }
      val model = ScreencastFile(project, file)
      model.initialize()
      FILES[file] = model
      return model
    }
  }
}
