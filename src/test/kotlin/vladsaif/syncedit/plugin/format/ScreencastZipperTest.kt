package vladsaif.syncedit.plugin.format

import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import com.intellij.util.io.inputStream
import kotlinx.coroutines.experimental.runBlocking
import org.junit.After
import vladsaif.syncedit.plugin.*
import java.nio.file.Files

class ScreencastZipperTest : LightCodeInsightFixtureTestCase() {

  private val myTempFile = Files.createTempFile(this.javaClass.name.replace('\\', '.'), ".scs")
  private val myScreencast by lazy {
    ScreencastZipper(myTempFile).use {
      it.addScript(SCRIPT_TEXT)
      it.addTranscriptData(TRANSCRIPT_DATA)
      it.addAudio(AUDIO_PATH)
    }
    val screencast = ScreencastFile(project, myTempFile)
    runBlocking {
      screencast.initialize()
    }
    screencast
  }

  fun `test script preserved`() {
    assertEquals(SCRIPT_TEXT, myScreencast.scriptDocument!!.text)
  }

  fun `test transcript preserved`() {
    assertEquals(TRANSCRIPT_DATA, myScreencast.data!!)
  }

  fun `test audio preserved`() {
    assertEquals(AUDIO_PATH.inputStream().sha1sum(), myScreencast.audioInputStream!!.sha1sum())
  }

  @After
  fun after() {
    Files.delete(myTempFile)
  }
}