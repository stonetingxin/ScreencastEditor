package vladsaif.syncedit.plugin.lang.transcript.refactoring

import vladsaif.syncedit.plugin.TranscriptModel
import vladsaif.syncedit.plugin.lang.transcript.psi.TranscriptWord

/**
 * Includes previously excluded or muted words back to transcript.
 * @see ExcludeAction
 */
class IncludeAction : IncludeExcludeActionBase() {

    override fun doAction(model: TranscriptModel, words: List<TranscriptWord>) {
        model.showWords(words.map { it.number }.toIntArray())
    }
}
