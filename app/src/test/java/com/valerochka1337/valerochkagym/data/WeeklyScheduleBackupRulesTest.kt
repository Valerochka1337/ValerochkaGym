package com.valerochka1337.valerochkagym.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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

    @Test
    fun `private health originals are excluded from every backup path`() {
        val legacy = Files.readString(Path.of("src/main/res/xml/backup_rules.xml"))
        val extraction = Files.readString(Path.of("src/main/res/xml/data_extraction_rules.xml"))
        val originals = "health_documents/"

        assertEquals(1, legacy.windowed(originals.length).count { it == originals })
        assertEquals(2, extraction.windowed(originals.length).count { it == originals })
    }

    @Test
    fun `private InBody originals and temporary copies are excluded from every backup path`() {
        val legacy = Files.readString(Path.of("src/main/res/xml/backup_rules.xml"))
        val extraction = Files.readString(Path.of("src/main/res/xml/data_extraction_rules.xml"))
        val originals = "measurement_documents/"

        assertEquals(1, legacy.windowed(originals.length).count { it == originals })
        assertEquals(2, extraction.windowed(originals.length).count { it == originals })
    }
}
