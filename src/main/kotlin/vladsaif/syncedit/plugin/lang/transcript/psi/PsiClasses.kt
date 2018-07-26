package vladsaif.syncedit.plugin.lang.transcript.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import vladsaif.syncedit.plugin.lang.transcript.TranscriptModel

class TranscriptPsiFile(val model: TranscriptModel, viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TranscriptViewLanguage) {

    override fun getFileType() = TranscriptFileType

    override fun toString() = "Transcript file"

}

interface TranscriptWord : PsiNameIdentifierOwner {
    val number: Int
    val hidden: Boolean
}

internal class TranscriptWordImpl(node: ASTNode) : ASTWrapperPsiElement(node), TranscriptWord {
    override val hidden = false
    override val number: Int
        get() {
            var j = 0
            for (word in wordsBetween(parent.firstChild, parent.lastChild)) {
                if (word == this) return j
                ++j
            }
            return -1
        }

    override fun getNameIdentifier() = this

    override fun setName(name: String): PsiElement {
        println("name = [${name}]")
        containingFile
        return this
    }

    override fun toString(): String = text
}