package vladsaif.syncedit.plugin.audioview.waveform

interface SelectionModel : ChangeNotifier {
  val selectedRanges: List<IntRange>

  fun resetSelection()

  fun addSelection(range: IntRange)

  fun removeSelected(range: IntRange)
}