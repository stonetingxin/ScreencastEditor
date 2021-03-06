package vladsaif.syncedit.plugin.lang.transcript.annotators

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement
import vladsaif.syncedit.plugin.lang.transcript.psi.TranscriptWord
import vladsaif.syncedit.plugin.model.WordData.State.MUTED

class TranscriptAnnotator : Annotator {

  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (element is TranscriptWord && element.isValid) {
      when (element.data?.state) {
        MUTED -> {
          val annotation = holder.createInfoAnnotation(element, "Word is muted")
          annotation.textAttributes = Highlighters.MUTED_WORD
        }
        else -> Unit
      }
    }
  }
}