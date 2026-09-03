package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseVariantSheetRowsTest {
    @Test
    fun `variant rows round trip and newest definition wins`() {
        val old = ExerciseVariantSheetRecord(VARIANT_ID, EXERCISE_ID, 100, "Узкий хват", false)
        val current = old.copy(updatedAt = 200, name = "Нейтральный хват", isArchived = true)

        val parsed = ExerciseVariantSheetRowParser.parse(
            listOf(
                ExerciseVariantSheetRowMapper.HEADER_ROW,
                ExerciseVariantSheetRowMapper.row(old).asStrings(),
                ExerciseVariantSheetRowMapper.row(current).asStrings(),
            ),
        )

        assertEquals("ExerciseVariants!A:F", ExerciseVariantSheetRowMapper.RANGE)
        assertEquals(current, parsed.single())
    }

    @Test
    fun `variant parser ignores deleted and malformed records`() {
        val deleted = listOf(VARIANT_ID, EXERCISE_ID, "200", "Старый", "false", "true")
        val malformed = listOf("bad", EXERCISE_ID, "200", "Сломан", "false", "false")

        assertTrue(ExerciseVariantSheetRowParser.parse(listOf(deleted, malformed)).isEmpty())
    }

    private companion object {
        const val VARIANT_ID = "11111111-1111-1111-1111-111111111111"
        const val EXERCISE_ID = "22222222-2222-2222-2222-222222222222"
    }
}

private fun List<Any?>.asStrings(): List<String> = map { it?.toString().orEmpty() }
