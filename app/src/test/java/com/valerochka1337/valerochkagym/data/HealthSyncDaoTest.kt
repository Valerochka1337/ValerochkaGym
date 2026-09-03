package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HealthSyncDaoTest : RoomDaoTest() {
    @Test
    fun `measurement history commits immutable snapshot and exact outbox delivery together`() = runTest {
        val legacySnapshot = MeasurementSnapshotEntity("m-1", 1, 100, false, "legacy-payload-v1", null)
        val legacyOutbox = HealthSyncOutboxEntity(
            HealthSyncCategory.MEASUREMENTS, "m-1", 1, "legacy-payload-v1", null, "m-1:1", 100,
        )
        val localSnapshot = MeasurementSnapshotEntity("m-1", 2, 200, false, "payload-v2", "hash-v2")
        val localOutbox = HealthSyncOutboxEntity(
            HealthSyncCategory.MEASUREMENTS, "m-1", 2, "payload-v2", "hash-v2", "m-1:2", 200,
        )

        db.healthDao().insertMeasurementHistory(legacySnapshot, legacyOutbox)
        db.healthDao().insertMeasurementHistory(localSnapshot, localOutbox)

        assertEquals(listOf(localSnapshot, legacySnapshot), db.healthDao().measurementSnapshots("m-1"))
        assertEquals(listOf(legacyOutbox, localOutbox), db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS))
        assertEquals(0, db.healthSyncOutboxDao().acknowledge(HealthSyncCategory.MEASUREMENTS, "m-1", 3))
        assertEquals(1, db.healthSyncOutboxDao().acknowledge(HealthSyncCategory.MEASUREMENTS, "m-1", 1))
        assertEquals(listOf(localOutbox), db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS))
    }

    @Test
    fun `failed durable outbox insert rolls back its new measurement snapshot`() = runTest {
        db.healthDao().insertMeasurementHistory(
            MeasurementSnapshotEntity("m-1", 1, 100, false, "payload-v1", "hash-v1"),
            HealthSyncOutboxEntity(HealthSyncCategory.MEASUREMENTS, "m-1", 1, "payload-v1", "hash-v1", "m-1:1", 100),
        )

        try {
            db.healthDao().insertMeasurementHistory(
                MeasurementSnapshotEntity("m-1", 2, 200, false, "payload-v2", "hash-v2"),
                HealthSyncOutboxEntity(HealthSyncCategory.MEASUREMENTS, "m-1", 1, "payload-v1", "hash-v1", "m-1:1", 100),
            )
            fail("duplicate outbox primary key must fail")
        } catch (_: Exception) {
            // Room/SQLite signals the unique key failure; the transaction is the behavior under test.
        }

        assertEquals(listOf(1L), db.healthDao().measurementSnapshots("m-1").map { it.version })
    }
}
