package com.no5.helm.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.helm.library.*
import com.no5.preparation.ui.No5ControlTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1ImportFlowTest {
 @get:Rule val ui=createEmptyComposeRule()
 @Test fun importCancellationRequiresConfirmationAndRechecksTheCurrentPhase(){
  var state by mutableStateOf(ChartLibraryUiState(busy=true,importProgress=ImportProgress(phase=ImportPhase.READING)))
  var cancels=0;var selections=0
  Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{h->
   h.get().setContent{No5ControlTheme(false){Box(Modifier.fillMaxWidth().height(404.dp)){
    MapLibraryWorkspace("progress",{},state,false,false,true,{}, {},{selections++},{},{},{},{cancels++},{},"",{},0,{})
   }}}
   ui.onNodeWithTag("no5-action-chart-import-cancel").performClick();focusHostInputWindow(h.get(),ui)
   assertEquals(0,cancels);ui.onNodeWithTag("chart-import-cancel-stay").performClick();focusHostInputWindow(h.get(),ui)
   ui.onNodeWithTag("no5-action-chart-import-cancel").performClick();focusHostInputWindow(h.get(),ui)
   ui.runOnIdle{state=state.copy(importProgress=ImportProgress(phase=ImportPhase.SAVING))}
   ui.onNodeWithTag("chart-import-cancel-confirm").assertIsNotEnabled();assertEquals(0,cancels)
   ui.runOnIdle{state=state.copy(importProgress=ImportProgress(phase=ImportPhase.READING))}
   ui.onNodeWithTag("chart-import-cancel-confirm").assertIsEnabled().performClick();assertEquals(1,cancels)
   ui.runOnIdle{state=state.copy(busy=false,importProgress=ImportProgress(activity=ImportActivity.COMPLETED))};focusHostInputWindow(h.get(),ui)
   ui.onNodeWithTag("no5-action-chart-import-select").performClick();assertEquals(1,selections)
  }
 }
}
