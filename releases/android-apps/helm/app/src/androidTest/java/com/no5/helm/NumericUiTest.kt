package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.helm.numeric.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled here; real device execution remains separately reported. */
class NumericUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun composeNumbersKeepWidthAndAccessibleOriginalTextAcrossTransitions() {
        val value=mutableStateOf("0.0 V")
        compose.setContent { MaterialTheme {
            Box(Modifier.width(360.dp)) { StableNumericText(value.value,NumericSlots(4,1,true,true),Modifier.testTag("number"),
                TextStyle(fontFamily=FontFamily(Font(R.font.barlow_condensed_semibold)),fontSize=32.sp)) }
        } }
        val original=compose.onNodeWithTag("number").fetchSemanticsNode().boundsInRoot
        for(sample in listOf("1.1 V","7.7 V","8.8 V","9.9 V","10.0 V","99.9 V","100.0 V","999.9 V","1000.0 V","-1000.0 V","— V","9.9* V")) {
            compose.runOnIdle{value.value=sample}
            compose.onNodeWithTag("number").assertTextEquals(sample)
            val next=compose.onNodeWithTag("number").fetchSemanticsNode().boundsInRoot
            assertEquals(original.width,next.width,.1f);assertEquals(original.height,next.height,.1f)
        }
    }
}
