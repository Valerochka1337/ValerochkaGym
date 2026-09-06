package com.valerochka1337.valerochkagym.ui.exercise

import androidx.compose.ui.graphics.Color
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import com.valerochka1337.valerochkagym.ui.analysis.body.muscleSectors
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseDetailScreenTest {

  @Test
  fun `equipment line names known requirements and distinguishes no equipment`() {
    assertEquals("Без оборудования", equipmentLine(ExerciseEquipmentRequirements.ExplicitNone))
    assertEquals(
        "Оборудование: Гантели",
        equipmentLine(ExerciseEquipmentRequirements.Required(setOf("dumbbells"))),
    )
  }

  @Test
  fun `shared chest sector uses its strongest logical role`() {
    val fills = ExerciseDetailRoleFills(Color.Gray, Color.Red, Color.Green, Color.Blue)
    val chest = muscleSectors(BodyView.FRONT).first { it.slug == "chest" }
    val fillFor =
        roleSectorFillFor(
            listOf(MuscleLoad(Muscle.UPPER_CHEST, 50), MuscleLoad(Muscle.LOWER_CHEST, 100)),
            fills,
        )

    assertEquals(fills.primary, fillFor(chest))
  }
}
