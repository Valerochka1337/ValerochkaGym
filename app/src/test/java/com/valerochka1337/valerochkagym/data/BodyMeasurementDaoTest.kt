package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyMeasurementDaoTest : RoomDaoTest() {

    private lateinit var dao: BodyMeasurementDao

    @org.junit.Before
    fun grabDao() {
        dao = db.bodyMeasurementDao()
    }

    @Test
    fun `measurements persist in descending date order and keep nullable values`() = runTest {
        dao.insert(BodyMeasurementEntity(id = "old", measuredAt = 1_000, weightKg = 70.0, waistCm = null))
        dao.insert(BodyMeasurementEntity(id = "new", measuredAt = 2_000, weightKg = null, chestCm = 96.0))

        val measurements = dao.observeAll().first()

        assertEquals(listOf("new", "old"), measurements.map { it.id })
        assertNull(measurements.first().weightKg)
        assertNull(measurements.last().waistCm)
    }

    @Test
    fun `updating a measurement preserves its UUID and status can be changed`() = runTest {
        val original = BodyMeasurementEntity(id = "m1", measuredAt = 1_000, weightKg = 70.0)
        dao.insert(original)

        dao.update(original.copy(weightKg = 69.5, waistCm = 71.0))
        dao.setUploadStatus("m1", UploadStatus.FAILED, "Нет сети")

        val stored = dao.getById("m1")!!
        assertEquals(69.5, stored.weightKg!!, 1e-6)
        assertEquals(71.0, stored.waistCm!!, 1e-6)
        assertEquals(UploadStatus.FAILED, stored.uploadStatus)
        assertEquals("Нет сети", stored.uploadError)
    }

    @Test
    fun `not uploaded query returns pending and failed measurements only`() = runTest {
        dao.insert(BodyMeasurementEntity(id = "pending", measuredAt = 3_000))
        dao.insert(BodyMeasurementEntity(id = "failed", measuredAt = 2_000, uploadStatus = UploadStatus.FAILED))
        dao.insert(BodyMeasurementEntity(id = "done", measuredAt = 1_000, uploadStatus = UploadStatus.UPLOADED))

        assertEquals(listOf("pending", "failed"), dao.getNotUploaded())
    }

    @Test
    fun `deleting a measurement removes only its local row`() = runTest {
        dao.insert(BodyMeasurementEntity(id = "m1", measuredAt = 1_000))
        dao.delete("m1")

        assertNull(dao.getById("m1"))
        assertEquals(0, tableCount("body_measurements"))
    }
}
