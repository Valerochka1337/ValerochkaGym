package com.valerochka1337.valerochkagym.ui.analysis.body

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.haptics.LocalGymHaptics
import com.valerochka1337.valerochkagym.ui.haptics.rememberGymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class MuscleSelectorComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `selector exposes three text slots and two decorative dividers without arrows or chips`() {
        val recorder = RecordingHaptics()
        compose.setContent {
            SelectorContent(recorder = recorder, selected = Muscle.UPPER_CHEST, onSelected = {})
        }

        assertEquals(1, compose.onAllNodesWithTag("muscle_selector_previous").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("muscle_selector_current").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("muscle_selector_next").fetchSemanticsNodes().size)
        compose.onNodeWithTag("muscle_selector_current").assertIsNotEnabled().assertIsSelected()
        assertEquals(1, compose.onAllNodesWithTag("muscle_selector_divider_previous").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("muscle_selector_divider_next").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Открыть весь список").fetchSemanticsNodes().size)
    }

    @Test
    fun `neighbor tap emits one changed logical selection and haptic`() {
        val recorder = RecordingHaptics()
        val selections = mutableListOf<Muscle>()
        compose.setContent {
            SelectorContent(
                recorder = recorder,
                selected = Muscle.UPPER_CHEST,
                onSelected = { selections += it },
            )
        }

        compose.onNodeWithTag("muscle_selector_next").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(Muscle.LOWER_CHEST), selections)
            assertEquals(1, recorder.performed.size)
        }

        compose.onNodeWithTag("muscle_selector_next").performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(
                listOf(Muscle.LOWER_CHEST, Muscle.FRONT_DELTS),
                selections,
            )
            assertEquals(2, recorder.performed.size)
        }
    }

    @Test
    fun `external selection and null initial choice reconcile silently as logical muscles`() {
        val recorder = RecordingHaptics()
        val selections = mutableListOf<Muscle>()
        var external by mutableStateOf<Muscle?>(null)
        compose.setContent {
            SelectorContent(
                recorder = recorder,
                selected = external,
                onSelected = { selections += it },
            )
        }

        compose.runOnIdle { external = Muscle.LATS }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(emptyList<Muscle>(), selections)
            assertEquals(0, recorder.performed.size)
        }
        compose.onNodeWithText(Muscle.LATS.displayName()).fetchSemanticsNode()
    }

    @Test
    fun `recreation anchors from restored logical muscle instead of a virtual position`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SelectorContent(
                recorder = RecordingHaptics(),
                selected = Muscle.UPPER_CHEST,
                onSelected = {},
            )
        }

        compose.onNodeWithTag("muscle_selector_viewport").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag("muscle_selector_current").fetchSemanticsNode()
        compose.onNodeWithText(Muscle.UPPER_CHEST.displayName()).fetchSemanticsNode()
    }

    @Test
    fun `fast fling can settle beyond one neighboring muscle`() {
        val recorder = RecordingHaptics()
        val selections = mutableListOf<Muscle>()
        compose.setContent {
            SelectorContent(
                recorder = recorder,
                selected = Muscle.UPPER_CHEST,
                onSelected = { selections += it },
            )
        }

        compose.onNodeWithTag("muscle_selector_viewport").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, selections.size)
            assertTrue(selections.single() !in MuscleSelectorState.visible(Muscle.UPPER_CHEST))
            assertEquals(1, recorder.performed.size)
        }
    }

    @Test
    fun `external selection arriving during a held drag does not steal the user settlement`() {
        val recorder = RecordingHaptics()
        val selections = mutableListOf<Muscle>()
        var external by mutableStateOf<Muscle?>(Muscle.UPPER_CHEST)
        compose.setContent {
            SelectorContent(
                recorder = recorder,
                selected = external,
                onSelected = { muscle ->
                    selections += muscle
                    external = muscle
                },
            )
        }

        // Input state deliberately spans two calls: the external update is applied while the
        // imaginary finger remains down, rather than queued after the gesture has ended.
        compose.onNodeWithTag("muscle_selector_viewport").performTouchInput {
            down(center)
            moveTo(Offset(0f, center.y), delayMillis = 300)
        }
        compose.runOnIdle { external = Muscle.LATS }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(emptyList<Muscle>(), selections)
            assertEquals(0, recorder.performed.size)
        }

        compose.onNodeWithTag("muscle_selector_viewport").performTouchInput { up() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, selections.size)
            assertTrue(selections.single() != Muscle.LATS)
            assertEquals(selections.single(), external)
            assertEquals(1, recorder.performed.size)
        }
    }

    @Test
    fun `talkback previous and next actions settle once and describe the selected role`() {
        val recorder = RecordingHaptics()
        val selections = mutableListOf<Muscle>()
        compose.setContent {
            SelectorContent(
                recorder = recorder,
                selected = Muscle.UPPER_CHEST,
                onSelected = { selections += it },
            )
        }

        val initial = compose.onNodeWithTag("muscle_selector").fetchSemanticsNode()
        assertEquals(
            listOf("Предыдущая мышца", "Следующая мышца"),
            initial.config[SemanticsActions.CustomActions].map { it.label },
        )
        assertEquals(
            "${Muscle.UPPER_CHEST.displayName()}: Роль",
            initial.config[SemanticsProperties.StateDescription],
        )

        compose.runOnIdle { initial.config[SemanticsActions.CustomActions][1].action() }
        compose.waitForIdle()
        val afterNext = compose.onNodeWithTag("muscle_selector").fetchSemanticsNode()
        assertEquals(
            "${Muscle.LOWER_CHEST.displayName()}: Роль",
            afterNext.config[SemanticsProperties.StateDescription],
        )
        compose.runOnIdle { afterNext.config[SemanticsActions.CustomActions][0].action() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(listOf(Muscle.LOWER_CHEST, Muscle.UPPER_CHEST), selections)
            assertEquals(2, recorder.performed.size)
        }
    }

    @Test
    fun `selector without role shows only the muscle name and describes it without an empty value`() {
        compose.setContent {
            SelectorContent(
                recorder = RecordingHaptics(),
                selected = Muscle.UPPER_CHEST,
                roleText = null,
                onSelected = {},
            )
        }

        assertEquals(
            0,
            compose.onAllNodesWithText("Мышца-стабилизатор").fetchSemanticsNodes().size,
        )
        val selector = compose.onNodeWithTag("muscle_selector").fetchSemanticsNode()
        assertEquals(
            Muscle.UPPER_CHEST.displayName(),
            selector.config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun `selector grows for full names and a long role at two times font scale`() {
        val recorder = RecordingHaptics()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                SelectorContent(
                    recorder = recorder,
                    selected = Muscle.TIBIALIS_ANTERIOR,
                    roleText = { "Мышца-стабилизатор" },
                    onSelected = {},
                )
            }
        }

        val previousSlot = compose.onNodeWithTag("muscle_selector_previous").fetchSemanticsNode().boundsInRoot
        val currentSlot = compose.onNodeWithTag("muscle_selector_current").fetchSemanticsNode().boundsInRoot
        val nextSlot = compose.onNodeWithTag("muscle_selector_next").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("muscle_selector_viewport").fetchSemanticsNode().boundsInRoot
        val previousDivider = compose.onNodeWithTag("muscle_selector_divider_previous").fetchSemanticsNode().boundsInRoot
        val nextDivider = compose.onNodeWithTag("muscle_selector_divider_next").fetchSemanticsNode().boundsInRoot
        val tibialis = compose.onNodeWithText(Muscle.TIBIALIS_ANTERIOR.displayName()).fetchSemanticsNode().boundsInRoot
        val longRole = compose.onNodeWithText("Мышца-стабилизатор").fetchSemanticsNode().boundsInRoot

        assertTrue(tibialis.left >= currentSlot.left && tibialis.right <= currentSlot.right)
        assertTrue(longRole.left >= currentSlot.left && longRole.right <= currentSlot.right)
        assertTrue(tibialis.top >= viewport.top && tibialis.bottom <= viewport.bottom)
        assertTrue(longRole.top >= viewport.top && longRole.bottom <= viewport.bottom)
        assertTrue(previousDivider.height == viewport.height && nextDivider.height == viewport.height)
        // Pixel remainder from two 1px dividers may make one weighted slot 1px wider.
        assertEquals(previousSlot.width, currentSlot.width, 1.1f)
        assertEquals(currentSlot.width, nextSlot.width, 1.1f)
        val minTouchHeight = 48f
        assertTrue(previousSlot.height >= minTouchHeight)
        assertTrue(nextSlot.height >= minTouchHeight)
    }

    @Test
    fun `selector slots have equal widths and neighbor touch targets`() {
        val recorder = RecordingHaptics()
        compose.setContent {
            SelectorContent(recorder = recorder, selected = Muscle.UPPER_CHEST, onSelected = {})
        }

        val previous = compose.onNodeWithTag("muscle_selector_previous").fetchSemanticsNode().boundsInRoot
        val current = compose.onNodeWithTag("muscle_selector_current").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("muscle_selector_next").fetchSemanticsNode().boundsInRoot
        val minTouchHeight = 48f * compose.density.density

        // Pixel remainder from two 1px dividers may make one weighted slot 1px wider.
        assertEquals(previous.width, current.width, 1.1f)
        assertEquals(current.width, next.width, 1.1f)
        assertTrue(previous.height >= minTouchHeight)
        assertTrue(next.height >= minTouchHeight)
    }

    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    @Composable
    private fun SelectorContent(
        recorder: RecordingHaptics,
        selected: Muscle?,
        roleText: ((Muscle) -> String)? = { "Роль" },
        onSelected: (Muscle) -> Unit,
    ) {
        CompositionLocalProvider(LocalHapticFeedback provides recorder) {
            val haptics = rememberGymHaptics(enabled = true)
            CompositionLocalProvider(LocalGymHaptics provides haptics) {
                GymTheme {
                    MuscleSelector(
                        selected = selected,
                        roleText = roleText,
                        onSelected = onSelected,
                    )
                }
            }
        }
    }
}
