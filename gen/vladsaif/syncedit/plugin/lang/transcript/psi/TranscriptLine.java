// This is a generated file. Not intended for manual editing.
package vladsaif.syncedit.plugin.lang.transcript.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface TranscriptLine extends PsiElement {

  @NotNull
  TranscriptId getId();

  @NotNull
  List<TranscriptTimeOffset> getTimeOffsetList();

}
