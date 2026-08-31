package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GymSheetRowsTest {

    @Test
    fun `gym header and rows contain a sorted full exercise set`() {
        val snapshot = GymSheetRecord.Snapshot(
            syncId = GYM_ID,
            updatedAt = 200,
            name = "Основной зал",
            exerciseSyncIds = linkedSetOf(EXERCISE_ID_B, EXERCISE_ID_A),
        )

        assertEquals(
            listOf("gym_id", "updated_at", "is_deleted", "gym_name", "exercise_id"),
            GymSheetRowMapper.HEADER_ROW,
        )
        assertEquals("Gyms!A:E", GymSheetRowMapper.RANGE)
        assertEquals(
            listOf(
                listOf(GYM_ID, 200L, "false", "Основной зал", EXERCISE_ID_A),
                listOf(GYM_ID, 200L, "false", "Основной зал", EXERCISE_ID_B),
            ),
            GymSheetRowMapper.rows(snapshot),
        )
    }

    @Test
    fun `gym parser selects the latest complete snapshot`() {
        val old = GymSheetRecord.Snapshot(
            syncId = GYM_ID,
            updatedAt = 100,
            name = "Старый зал",
            exerciseSyncIds = setOf(EXERCISE_ID_A),
        )
        val current = GymSheetRecord.Snapshot(
            syncId = GYM_ID,
            updatedAt = 200,
            name = "Новый зал",
            exerciseSyncIds = setOf(EXERCISE_ID_A, EXERCISE_ID_B),
        )
        val rows = listOf(GymSheetRowMapper.HEADER_ROW) +
            GymSheetRowMapper.rows(old).asStrings() +
            GymSheetRowMapper.rows(current).asStrings()

        val parsed = GymSheetRowParser.parse(rows)

        assertEquals(0, parsed.skippedRows)
        assertEquals(current, parsed.records.single())
    }

    @Test
    fun `empty gym round trips as one live marker row`() {
        val emptyGym = GymSheetRecord.Snapshot(
            syncId = GYM_ID,
            updatedAt = 200,
            name = "Пустой зал",
            exerciseSyncIds = emptySet(),
        )

        val rows = GymSheetRowMapper.rows(emptyGym)
        val parsed = GymSheetRowParser.parse(rows.asStrings())

        assertEquals("false", rows.single()[2])
        assertEquals("", rows.single()[4])
        assertEquals(emptyGym, parsed.records.single())
    }

    @Test
    fun `newer gym tombstone wins over an older snapshot`() {
        val old = GymSheetRecord.Snapshot(
            syncId = GYM_ID,
            updatedAt = 100,
            name = "Удаляемый зал",
            exerciseSyncIds = setOf(EXERCISE_ID_A),
        )
        val rows = GymSheetRowMapper.rows(old).asStrings() + listOf(
            GymSheetRowMapper.deletion(GYM_ID, 300).map { it?.toString().orEmpty() },
        )

        val parsed = GymSheetRowParser.parse(rows)

        assertEquals(GymSheetRecord.Tombstone(GYM_ID, 300), parsed.records.single())
    }

    @Test
    fun `malformed latest gym membership rejects that version and keeps parsing other gyms`() {
        val validOther = GymSheetRecord.Snapshot(
            syncId = OTHER_GYM_ID,
            updatedAt = 50,
            name = "Другой зал",
            exerciseSyncIds = emptySet(),
        )
        val malformed = listOf(GYM_ID, "200", "false", "Зал", "not-a-uuid")
        val rows = listOf(malformed) + GymSheetRowMapper.rows(validOther).asStrings()

        val parsed = GymSheetRowParser.parse(rows)

        assertEquals(1, parsed.skippedRows)
        assertEquals(listOf(validOther), parsed.records)
    }

    @Test
    fun `conflicting empty marker and exercise row reject a gym snapshot`() {
        val emptyMarker = listOf(GYM_ID, "200", "false", "Зал", "")
        val membership = listOf(GYM_ID, "200", "false", "Зал", EXERCISE_ID_A)

        val parsed = GymSheetRowParser.parse(listOf(emptyMarker, membership))

        assertEquals(1, parsed.skippedRows)
        assertTrue(parsed.records.isEmpty())
    }

    private fun List<List<Any?>>.asStrings(): List<List<String>> =
        map { row -> row.map { it?.toString().orEmpty() } }

    private companion object {
        const val GYM_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_GYM_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val EXERCISE_ID_A = "11111111-1111-4111-8111-111111111111"
        const val EXERCISE_ID_B = "22222222-2222-4222-8222-222222222222"
    }
}
