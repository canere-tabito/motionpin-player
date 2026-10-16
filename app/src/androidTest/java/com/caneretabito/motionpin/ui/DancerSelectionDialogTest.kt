package com.caneretabito.motionpin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.caneretabito.motionpin.tracking.DancerTrackingState
import com.caneretabito.motionpin.tracking.PersonBox
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an Android UI runtime; compiling this test does not verify its execution. */
@RunWith(AndroidJUnit4::class)
class DancerSelectionDialogTest {
    @get:Rule val compose = createComposeRule()
    private val people = listOf(
        PersonBox(.1f, .2f, .2f, .8f, .8f, emptyList()),
        PersonBox(.7f, .2f, .8f, .8f, .8f, emptyList()))

    @Test fun candidatePreviewDoesNotStartTrackingUntilConfirmed() {
        var selected = -1
        compose.setContent {
            MotionPinTheme {
                DancerSelectionDialog(DancerTrackingState(phase = "choosing", candidates = people),
                    onSelect = { selected = it }, onCancel = {}, onQuality = {}, onRotate = {}, onStep = {})
            }
        }
        compose.onNodeWithText("人物を選択").assertIsNotEnabled()
        compose.onNodeWithText("人物 1 · 左側").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(-1, selected) }
        compose.onNodeWithText("人物 1を追尾").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun changedDetectionCannotConfirmAStaleCandidateIndex() {
        val state = mutableStateOf(DancerTrackingState(phase = "choosing", candidates = people))
        compose.setContent {
            MotionPinTheme {
                DancerSelectionDialog(state.value, onSelect = {}, onCancel = {}, onQuality = {},
                    onRotate = {}, onStep = {})
            }
        }
        compose.onNodeWithText("人物 1 · 左側").performScrollTo().performClick()
        compose.onNodeWithText("人物 1を追尾").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(candidates = people.drop(1)) }
        compose.onNodeWithText("人物を選択").assertIsNotEnabled()
    }
}
