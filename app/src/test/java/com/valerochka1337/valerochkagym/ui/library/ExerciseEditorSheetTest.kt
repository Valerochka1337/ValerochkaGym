package com.valerochka1337.valerochkagym.ui.library

import androidx.compose.ui.graphics.Color
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import com.valerochka1337.valerochkagym.ui.analysis.body.muscleSectors
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseEditorSheetTest {

    @Test
    fun `editor shared sector keeps its strongest role colour`() {
        val chest = muscleSectors(BodyView.FRONT).first { it.slug == "chest" }
        val fillFor = editorSectorFillFor(
            loads = mapOf(Muscle.UPPER_CHEST to 50, Muscle.LOWER_CHEST to 100),
            inactive = Color.Gray,
            primary = Color.Red,
            secondary = Color.Green,
            stabilizer = Color.Blue,
        )

        assertEquals(Color.Red, fillFor(chest))
    }

}
