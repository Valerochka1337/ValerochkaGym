package com.valerochka1337.valerochkagym.data

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WeeklyScheduleBackupRulesTest {
  @Test
  fun `machine local journal is excluded from every backup path while active settings remains`() {
    val legacy = Files.readString(Path.of("src/main/res/xml/backup_rules.xml"))
    val extraction = Files.readString(Path.of("src/main/res/xml/data_extraction_rules.xml"))
    val journal = "datastore/weekly_schedule_operations.preferences_pb"

    assertEquals(1, legacy.windowed(journal.length).count { it == journal })
    assertEquals(2, extraction.windowed(journal.length).count { it == journal })
    assertFalse(legacy.contains("datastore/settings.preferences_pb"))
    assertFalse(extraction.contains("datastore/settings.preferences_pb"))
  }
}
