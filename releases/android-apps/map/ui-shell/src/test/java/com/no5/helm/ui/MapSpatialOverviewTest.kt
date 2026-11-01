package com.no5.helm.ui
import com.no5.helm.library.ChartSelectionExtent
import org.junit.Assert.*
import org.junit.Test
class MapSpatialOverviewTest {
 private val plot=M1Rect(32f,234f,1272f,754f)
 @Test fun antimeridianCellsAreAdjacentRatherThanAWorldApart(){
  val a=ChartSelectionExtent(1.0,2.0,178.0,180.0);val b=ChartSelectionExtent(1.0,2.0,-180.0,-178.0)
  val p=MapSpatialOverview(listOf(a,b),plot);val x=p.rect(a)!!;val y=p.rect(b)!!
  assertEquals(x.right,y.left,.01f);assertTrue(x.left>=plot.left);assertTrue(y.right<=plot.right)
 }
 @Test fun panChangesOnlyCoordinatesAndNeverTheExtent(){
  val e=ChartSelectionExtent(42.0,43.0,-72.0,-71.0);val p=MapSpatialOverview(listOf(e),plot)
  val a=p.rect(e)!!;val b=p.rect(e,17f,-25f)!!;assertEquals(a.left+17,b.left,.001f);assertEquals(a.top-25,b.top,.001f);assertEquals(42.0,e.south,0.0)
 }
 @Test fun pointAndInvalidEnvelopesHaveExplicitBehavior(){
  val p=MapSpatialOverview(emptyList(),plot);val point=p.rect(ChartSelectionExtent(0.0,0.0,0.0,0.0))!!
  assertTrue(point.right>point.left);assertTrue(point.bottom>point.top)
  assertNull(p.rect(ChartSelectionExtent(Double.NaN,0.0,0.0,1.0)))
 }
 @Test fun paginationNeverShrinksBelowOneAndNeverExceedsFiveRows(){
  assertEquals(5,mapPageCapacity(808f));assertEquals(3,mapPageCapacity(600f));assertEquals(1,mapPageCapacity(200f))
 }
}
