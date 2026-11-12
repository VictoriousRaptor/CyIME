package com.kingzcheung.xime.ui.keyboard
import org.junit.Assert.assertEquals
import org.junit.Test
class CandidateStripGeometryTest {
 @Test fun clippingTransparentPaddingKeepsBothEdgeWordsReadable() {
  assertEquals(true, candidateLabelFits(-8, 36, 8, 0, 100))
  assertEquals(true, candidateLabelFits(72, 36, 8, 0, 100))
  assertEquals(false, candidateLabelFits(-9, 36, 8, 0, 100))
  assertEquals(false, candidateLabelFits(73, 36, 8, 0, 100))
 }
 @Test fun onlyTheLeftEdgeHidesACutWord() {
  assertEquals(false, candidateLabelCutAtStart(-8, 8, 0))
  assertEquals(true, candidateLabelCutAtStart(-9, 8, 0))
  // 右边放不下的不算，照常显示、被栏边裁掉（用户 11-11）。
  assertEquals(false, candidateLabelCutAtStart(73, 8, 0))
 }
 @Test fun focusWindowKeepsOriginalIndicesAndFitsTheSelectedItem() {
  val widths=listOf(40,70,80,30,50)
  fun window(selected:Int, available:Int=114)=candidateWindow(widths.size,selected,available,4) { widths[it] }
  assertEquals(0..1,window(0))
  assertEquals(0..1,window(1))
  assertEquals(2..2,window(2))
  assertEquals(3..4,window(4))
  assertEquals(2..2,window(2,30))
  assertEquals(IntRange.EMPTY,window(2,0))
  assertEquals(0..1,window(-1))
 }
 @Test fun farAwayHighlightDoesNotMeasureAllEarlierCandidates() {
  val measured=mutableListOf<Int>()
  assertEquals(399..400,candidateWindow(500,400,100,4) { measured+=it;40 })
  assertEquals(listOf(0,1,2,400,399,398),measured)
 }
 @Test fun movingPastEitherEdgeKeepsFocusAtThatEdgeAndReversingDoesNotJump() {
  var visible=0..2
  fun move(index:Int):IntRange {
   visible=candidateWindow(10,index,128,4,windowStart=visible.first) { 40 }
   return visible
  }
  assertEquals(0..2,move(1))
  assertEquals(0..2,move(2))
  assertEquals(1..3,move(3))
  assertEquals(2..4,move(4))
  assertEquals(2..4,move(3))
  assertEquals(2..4,move(2))
  assertEquals(1..3,move(1))
  assertEquals(0..2,move(0))
 }
 @Test fun changingWidthsOrPanelSizeKeepsTheFocusedWordVisibleWithoutMeasuringTheWholeList() {
  val widths=listOf(40,70,80,30,50)
  assertEquals(2..3,candidateWindow(5,3,114,4,windowStart=1) { widths[it] })
  assertEquals(1..1,candidateWindow(5,1,30,4,windowStart=2) { widths[it] })
  assertEquals(0..1,candidateWindow(5,0,114,4,windowStart=4) { widths[it] })
  assertEquals(IntRange.EMPTY,candidateWindow(5,3,0,4,windowStart=2) { error("no width") })
 }
 @Test fun expandedStartsAfterHeaderAndPreservesFilteredIndices() {
  val entries=listOf(CandidateEntry("PS", "", 2),CandidateEntry("输入", "", 5),CandidateEntry("剩余", "", 9))
  assertEquals(listOf(9),remainingCandidates(entries,listOf("PS","输入")).map { it.globalIndex })
  assertEquals(entries,remainingCandidates(entries,listOf("旧候选")))
  assertEquals(entries,remainingCandidates(entries,emptyList()))
 }

 @Test fun fitsActualWidthAndSpacing() {
  assertEquals(2,candidatePrefixCount(listOf(40,70,30),114,4))
  assertEquals(1,candidatePrefixCount(listOf(40,70,30),113,4))
 }
 @Test fun oversizedFirstRemainsAccessible() { assertEquals(1,candidatePrefixCount(listOf(500,20),60,4)) }
 @Test fun focusDoesNotShiftTheRowJustBecauseTheLastGlossOverflows() {
  val widths = listOf(90, 150, 30)
  fun window(width: Int, focused: Int, start: Int = 0) =
   candidateWindow(widths.size, focused, width, 4, start, edgeWidthAt = { if (it == 1) 40 else widths[it] }) { widths[it] }
  assertEquals(0..1, window(134, 0))
  assertEquals(0..1, window(134, 1))
  assertEquals(0..0, window(133, 0)) // 一像素也不能切掉候选本身。
  assertEquals(2..2, window(134, 2)) // 中间的释义占据实际宽度，不把后续词叠在它上面。
 }
 @Test fun emptyAndUnavailable() {
  assertEquals(0,candidatePrefixCount(emptyList(),200,4))
  assertEquals(0,candidatePrefixCount(listOf(20),0,4))
 }
 @Test fun offscreenCandidatesAreNeverMeasured() {
  var measured=0
  val count=candidatePrefixCount(500,114,4) { index -> measured++; listOf(40,70,30)[index] }
  assertEquals(2,count)
  assertEquals(3,measured)
 }
 @Test fun zeroWidthDoesNotMeasureAnyText() {
  assertEquals(0,candidatePrefixCount(500,0,4) { error("offscreen text measured") })
 }
 @Test fun tailPaddingLetsTheLastScreenStartAtAWholeCandidate() {
  val widths=listOf(40,70,80,30,50)
  // 50+4+30=84 fits, 80 more does not: pad 30 so scrolled to the end 30 starts at the left edge.
  assertEquals(30,candidateTailPadding(widths.size,114,4) { widths[it] })
  assertEquals(0,candidateTailPadding(2,114,4) { listOf(40,200)[it] })
  assertEquals(0,candidateTailPadding(0,114,4) { error("nothing to measure") })
  assertEquals(0,candidateTailPadding(3,0,4) { error("nothing to measure") })
 }
 @Test fun tailPaddingOnlyMeasuresTheLastScreen() {
  val measured=mutableListOf<Int>()
  assertEquals(18,candidateTailPadding(500,100,2) { measured+=it;40 })
  assertEquals(listOf(499,498,497),measured)
 }
}
