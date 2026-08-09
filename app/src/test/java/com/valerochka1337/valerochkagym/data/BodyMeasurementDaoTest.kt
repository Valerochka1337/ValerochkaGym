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
    fun `full InBody report and all segment values survive a DAO round trip`() = runTest {
        dao.insert(
            BodyMeasurementEntity(
                id = "inbody",
                measuredAt = 2_000,
                bodyFatMassKg = 14.8,
                inBodyScore = 74,
                totalBodyWaterLiters = 32.7,
                proteinKg = 8.7,
                mineralsKg = 3.34,
                bodyMassIndex = 19.4,
                fatFreeMassKg = 44.7,
                basalMetabolicRateKcal = 1335,
                recommendedCalorieIntakeKcal = 2499,
                leftArmLeanMassKg = 1.99,
                leftArmLeanPercentage = 91.7,
                rightArmLeanMassKg = 2.07,
                rightArmLeanPercentage = 95.1,
                trunkLeanMassKg = 19.1,
                trunkLeanPercentage = 91.4,
                leftLegLeanMassKg = 7.43,
                leftLegLeanPercentage = 108.0,
                rightLegLeanMassKg = 7.39,
                rightLegLeanPercentage = 107.5,
                leftArmFatMassKg = 1.0,
                leftArmFatPercentage = 88.8,
                rightArmFatMassKg = 1.0,
                rightArmFatPercentage = 86.3,
                trunkFatMassKg = 7.1,
                trunkFatPercentage = 92.9,
                leftLegFatMassKg = 2.4,
                leftLegFatPercentage = 85.7,
                rightLegFatMassKg = 2.4,
                rightLegFatPercentage = 85.6,
            ),
        )

        val stored = dao.getById("inbody")!!

        assertEquals(14.8, stored.bodyFatMassKg!!, 1e-6)
        assertEquals(74, stored.inBodyScore)
        assertEquals(32.7, stored.totalBodyWaterLiters!!, 1e-6)
        assertEquals(2499, stored.recommendedCalorieIntakeKcal)
        assertEquals(1.99, stored.leftArmLeanMassKg!!, 1e-6)
        assertEquals(92.9, stored.trunkFatPercentage!!, 1e-6)
        assertEquals(85.6, stored.rightLegFatPercentage!!, 1e-6)
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
