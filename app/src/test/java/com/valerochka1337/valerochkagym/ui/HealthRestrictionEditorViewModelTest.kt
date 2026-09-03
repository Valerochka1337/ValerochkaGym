package com.valerochka1337.valerochkagym.ui
import org.junit.Test
import org.junit.Assert.*
import com.valerochka1337.valerochkagym.ui.health.RestrictionInput
import com.valerochka1337.valerochkagym.domain.health.*

class HealthRestrictionEditorViewModelTest {
 @Test fun `manual restriction input keeps active as default and excluded rows are distinguishable`() { val included=RestrictionInput(text="Присед",included=true);val excluded=RestrictionInput(text="Бег",included=false);assertEquals(HealthRestrictionState.ACTIVE,included.state);assertTrue(included.included);assertFalse(excluded.included) }
}
