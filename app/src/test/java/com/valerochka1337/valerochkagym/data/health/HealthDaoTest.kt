package com.valerochka1337.valerochkagym.data.health

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.HealthHeadHistoryEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthLogicalRecordEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRecordVersionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthDaoTest : RoomDaoTest() {
  @Test
  fun `logical record keeps immutable versions and cascades audit children`() = runTest {
    db.withTransaction {
      db.healthDao()
          .upsertRecord(
              HealthLogicalRecordEntity("logical", "GUEST", "RESTRICTION", 1, "v1", 1, false, null)
          )
      db.healthDao()
          .insertVersion(
              HealthRecordVersionEntity(
                  "v1",
                  "logical",
                  null,
                  "RESTRICTION",
                  "CONFIRMED",
                  1,
                  "{\"textOriginal\":\"x\",\"confirmedAtEpochMs\":1}",
                  null,
                  null,
              )
          )
      db.healthDao()
          .insertVersion(
              HealthRecordVersionEntity(
                  "v2",
                  "logical",
                  "v1",
                  "RESTRICTION",
                  "TOMBSTONE",
                  2,
                  null,
                  null,
                  null,
              )
          )
      db.healthDao()
          .insertHeadHistory(HealthHeadHistoryEntity("logical", 1, "v1", "RESTRICTION", false, 10))
    }
    assertEquals(
        listOf("v1", "v2"),
        db.healthDao().observeVersions("logical").first().map { it.versionId },
    )
    db.healthDao().deleteScope("GUEST")
    assertEquals(0, tableCount("health_record_versions"))
    assertEquals(0, tableCount("health_head_history"))
  }
}
