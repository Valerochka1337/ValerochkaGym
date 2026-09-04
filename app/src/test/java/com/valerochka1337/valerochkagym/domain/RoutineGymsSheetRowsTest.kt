package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineGymsSheetRowsTest {

  @Test
  fun `routine gyms header and rows contain a sorted full gym set`() {
    val snapshot =
        RoutineGymsSheetRecord.Snapshot(
            routineSyncId = ROUTINE_ID,
            updatedAt = 200,
            gymSyncIds = linkedSetOf(GYM_ID_B, GYM_ID_A),
        )

    assertEquals(
        listOf("routine_id", "updated_at", "is_deleted", "gym_id"),
        RoutineGymsSheetRowMapper.HEADER_ROW,
    )
    assertEquals("RoutineGyms!A:D", RoutineGymsSheetRowMapper.RANGE)
    assertEquals(
        listOf(
            listOf(ROUTINE_ID, 200L, "false", GYM_ID_A),
            listOf(ROUTINE_ID, 200L, "false", GYM_ID_B),
        ),
        RoutineGymsSheetRowMapper.rows(snapshot),
    )
  }

  @Test
  fun `routine gyms parser selects the snapshot with greatest updatedAt`() {
    val old =
        RoutineGymsSheetRecord.Snapshot(
            routineSyncId = ROUTINE_ID,
            updatedAt = 100,
            gymSyncIds = setOf(GYM_ID_A),
        )
    val current =
        RoutineGymsSheetRecord.Snapshot(
            routineSyncId = ROUTINE_ID,
            updatedAt = 200,
            gymSyncIds = setOf(GYM_ID_A, GYM_ID_B),
        )
    val rows =
        listOf(RoutineGymsSheetRowMapper.HEADER_ROW) +
            RoutineGymsSheetRowMapper.rows(old).asStrings() +
            RoutineGymsSheetRowMapper.rows(current).asStrings()

    val parsed = RoutineGymsSheetRowParser.parse(rows)

    assertEquals(0, parsed.skippedRows)
    assertEquals(current, parsed.records.single())
  }

  @Test
  fun `empty routine gym selection remains live and differs from tombstone`() {
    val empty =
        RoutineGymsSheetRecord.Snapshot(
            routineSyncId = ROUTINE_ID,
            updatedAt = 200,
            gymSyncIds = emptySet(),
        )

    val emptyRows = RoutineGymsSheetRowMapper.rows(empty)
    val parsedEmpty = RoutineGymsSheetRowParser.parse(emptyRows.asStrings())
    val parsedDeletion =
        RoutineGymsSheetRowParser.parse(
            listOf(
                RoutineGymsSheetRowMapper.deletion(ROUTINE_ID, 300).map { it?.toString().orEmpty() }
            ),
        )

    assertEquals("false", emptyRows.single()[2])
    assertEquals("", emptyRows.single()[3])
    assertEquals(empty, parsedEmpty.records.single())
    assertEquals(
        RoutineGymsSheetRecord.Tombstone(ROUTINE_ID, 300),
        parsedDeletion.records.single(),
    )
  }

  @Test
  fun `malformed latest routine gyms row is rejected without falling back`() {
    val old =
        RoutineGymsSheetRecord.Snapshot(
            routineSyncId = ROUTINE_ID,
            updatedAt = 100,
            gymSyncIds = setOf(GYM_ID_A),
        )
    val malformedLatest = listOf(ROUTINE_ID, "200", "false", "not-a-uuid")
    val rows = RoutineGymsSheetRowMapper.rows(old).asStrings() + listOf(malformedLatest)

    val parsed = RoutineGymsSheetRowParser.parse(rows)

    assertEquals(1, parsed.skippedRows)
    assertTrue(parsed.records.isEmpty())
  }

  @Test
  fun `routine gyms parser accepts numeric booleans and exact decimal versions`() {
    val row = listOf(ROUTINE_ID, "200.0", "0", GYM_ID_A)

    val parsed = RoutineGymsSheetRowParser.parse(listOf(row))

    assertEquals(
        RoutineGymsSheetRecord.Snapshot(ROUTINE_ID, 200, setOf(GYM_ID_A)),
        parsed.records.single(),
    )
  }

  private fun List<List<Any?>>.asStrings(): List<List<String>> = map { row ->
    row.map { it?.toString().orEmpty() }
  }

  private companion object {
    const val ROUTINE_ID = "99999999-9999-4999-8999-999999999999"
    const val GYM_ID_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val GYM_ID_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
  }
}
