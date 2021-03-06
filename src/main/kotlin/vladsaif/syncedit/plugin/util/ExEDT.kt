package vladsaif.syncedit.plugin.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import kotlin.coroutines.CoroutineContext

/**
 * EDT dispatcher.
 * I need it because currently 1.3 kotlin is not yet released, but IdeaIC-EAP already moved to it.
 */
object ExEDT : kotlinx.coroutines.CoroutineDispatcher() {

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    ApplicationManager.getApplication().invokeLater(block, ModalityState.defaultModalityState())
  }

  override fun isDispatchNeeded(context: CoroutineContext): Boolean {
    return !ApplicationManager.getApplication().isDispatchThread
  }
}