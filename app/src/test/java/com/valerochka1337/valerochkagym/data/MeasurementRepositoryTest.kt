package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.domain.measurements.ParsedMeasurementSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementRepositoryTest : RoomDaoTest() {
    @Test
    fun `create edit and delete each append one hashed immutable snapshot and outbox row`() = runTest {
        val repository = MeasurementRepository(db)
        repository.save(BodyMeasurementEntity("m", 10, weightKg = 70.0), now = 100)
        repository.save(BodyMeasurementEntity("m", 20, weightKg = 69.0), now = 100)
        repository.delete("m", now = 100)

        assertNull(db.bodyMeasurementDao().getById("m"))
        val snapshots = db.healthDao().measurementSnapshots("m")
        assertEquals(listOf(3L, 2L, 1L), snapshots.map { it.version })
        assertEquals(listOf(true, false, false), snapshots.map { it.isTombstone })
        assertEquals(listOf(102L, 101L, 100L), snapshots.map { it.updatedAt })
        assertEquals(3, db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS).size)
        assertEquals(64, snapshots[1].payloadHash!!.length)
    }

    @Test
    fun `new canonical payload includes local conditions and sha256 is deterministic`() {
        val measurement = BodyMeasurementEntity("quoted'id", 1, weightKg = 70.0)
        val payload = MeasurementRepository.canonicalPayload(measurement)

        assertEquals("v2|id='quoted''id'|measuredAt=1|weightKg=70.0", payload.substringBefore("|skeletalMuscleMassKg"))
        assertEquals(true, payload.endsWith("|afterMeal=false|afterWorkout=false|unusualHydration=false|conditionNote=NULL"))
        assertEquals(MeasurementRepository.sha256(payload), MeasurementRepository.sha256(payload))
    }

    @Test
    fun `snapshot codec decodes the migration v1 quote form including a tombstone`() {
        val measurement = BodyMeasurementEntity("quoted'id", 1, weightKg = 70.0, waistCm = 72.0)
        val payload = MeasurementSnapshotCodec.encodeV1(measurement, isTombstone = true)

        val decoded = MeasurementSnapshotCodec.decode(payload)

        assertEquals(measurement, decoded!!.measurement)
        assertEquals(true, decoded.isTombstone)
        assertEquals(MeasurementSnapshotCodec.PayloadFormat.V1, decoded.payloadFormat)
        assertNull(MeasurementSnapshotCodec.decode("v1|id='m'|measuredAt=1"))
    }

    @Test
    fun `new save keeps explicit body fat mass and WHR as primary snapshot values`() = runTest {
        val repository = MeasurementRepository(db)
        val measurement = BodyMeasurementEntity(
            "explicit", 1, weightKg = 70.0, bodyFatPercentage = 20.0,
            bodyFatMassKg = 13.7, waistHipRatio = 0.82, waistCm = 75.0, hipsCm = 100.0,
        )

        repository.save(measurement, now = 10)

        val decoded = MeasurementSnapshotCodec.decode(db.healthDao().measurementSnapshots("explicit").single().canonicalPayload)!!
        assertEquals(13.7, decoded.measurement.bodyFatMassKg ?: 0.0, 0.0)
        assertEquals(0.82, decoded.measurement.waistHipRatio ?: 0.0, 0.0)
    }

    @Test
    fun `new save normalizes condition note and retains condition flags in v2 snapshot`() = runTest {
        val repository = MeasurementRepository(db)
        repository.save(
            BodyMeasurementEntity(
                "conditions", 1, afterMeal = true, afterWorkout = true,
                unusualHydration = true, conditionNote = "  После сауны  ",
            ),
            now = 10,
        )

        val snapshot = db.healthDao().measurementSnapshots("conditions").single()
        assertEquals("v2", snapshot.canonicalPayload.substringBefore('|'))
        assertEquals("После сауны", db.bodyMeasurementDao().getById("conditions")!!.conditionNote)
        assertEquals(true, MeasurementSnapshotCodec.decode(snapshot.canonicalPayload)!!.measurement.afterMeal)
        assertEquals("После сауны", MeasurementSnapshotCodec.decode(snapshot.canonicalPayload)!!.measurement.conditionNote)
        assertEquals(MeasurementSnapshotCodec.PayloadFormat.V2, MeasurementSnapshotCodec.decode(snapshot.canonicalPayload)!!.payloadFormat)
    }

    @Test fun `measurement persistence does not create medical observations or device metadata`() = runTest {
        val repository = MeasurementRepository(db)
        repository.save(BodyMeasurementEntity("standalone", 1, weightKg = 70.0), now = 10)

        assertEquals(emptyList<com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity>(), db.healthDao().observations("standalone"))
        val payload = db.healthDao().measurementSnapshots("standalone").single().canonicalPayload
        assertEquals(false, payload.contains("device", ignoreCase = true))
        assertEquals(false, payload.contains("model", ignoreCase = true))
    }

    @Test
    fun `same legacy v1 snapshot is not rewritten or conflicted as a v2 payload`() = runTest {
        val repository = MeasurementRepository(db)
        val legacy = BodyMeasurementEntity("legacy", 1, weightKg = 70.0)
        val v1 = MeasurementSnapshotCodec.encodeV1(legacy)
        val snapshot = ParsedMeasurementSnapshot(
            measurement = legacy,
            version = 1,
            updatedAt = 1,
            isDeleted = false,
            payloadHash = null,
            idempotencyKey = "legacy:1",
            canonicalPayload = v1,
        )

        assertEquals(true, repository.applyImported(snapshot))
        assertEquals(false, repository.applyImported(snapshot))
        assertEquals(v1, db.healthDao().measurementSnapshots("legacy").single().canonicalPayload)
        assertEquals(0, db.healthDao().conflicts().size)
    }

    @Test
    fun `v2 note containing tombstone marker decodes normally and exact suffix remains tombstone`() {
        val measurement = BodyMeasurementEntity("note", 1, conditionNote = "после |isDeleted=1 воды")
        val normal = MeasurementSnapshotCodec.decode(MeasurementSnapshotCodec.encode(measurement))!!
        val tombstone = MeasurementSnapshotCodec.decode(MeasurementSnapshotCodec.encode(measurement, isTombstone = true))!!

        assertEquals("после |isDeleted=1 воды", normal.measurement.conditionNote)
        assertEquals(false, normal.isTombstone)
        assertEquals(true, tombstone.isTombstone)
        assertNull(MeasurementSnapshotCodec.decode("v2|id='m'|isDeleted=1"))
    }

    @Test
    fun `codec rejects malformed v2 booleans and non suffix tombstone markers`() {
        val measurement = BodyMeasurementEntity("strict", 1)
        val normal = MeasurementSnapshotCodec.encode(measurement)
        val malformedBoolean = normal.replace("afterMeal=false", "afterMeal=0")
        val malformedTombstone = "$normal|isDeleted=1x"

        assertNull(MeasurementSnapshotCodec.decode(malformedBoolean))
        assertNull(MeasurementSnapshotCodec.decode(malformedTombstone))
        assertEquals(true, MeasurementSnapshotCodec.decode("${normal}|isDeleted=1")!!.isTombstone)
    }

    @Test
    fun `remote v2 replaces legacy v1 while equal divergent and tombstone versions are durable`() = runTest {
        val repository = MeasurementRepository(db)
        val v1 = BodyMeasurementEntity("m", 100, weightKg = 70.0)
        repository.applyImported(ParsedMeasurementSnapshot(v1, 1, 100, false, null, "m:1"))
        val v2 = v1.copy(weightKg = 69.0)
        repository.applyImported(
            ParsedMeasurementSnapshot(v2, 2, 200, false, MeasurementRepository.sha256(MeasurementRepository.canonicalPayload(v2)), "m:2"),
        )
        repository.applyImported(ParsedMeasurementSnapshot(v2.copy(weightKg = 68.0), 2, 201, false, "different", "m:2"))

        assertEquals(69.0, db.bodyMeasurementDao().getById("m")!!.weightKg!!, 0.0)
        assertEquals(1, db.healthDao().conflicts().size)

        repository.applyImported(ParsedMeasurementSnapshot(v2, 3, 300, true, "deleted", "m:3"))
        assertNull(db.bodyMeasurementDao().getById("m"))
        assertEquals(true, db.healthDao().measurementSnapshots("m").first().isTombstone)
    }

    @Test
    fun `aggregate import preserves v1 and v2 history regardless of input order`() = runTest {
        val repository = MeasurementRepository(db)
        val v1 = BodyMeasurementEntity("m", 100, weightKg = 70.0)
        val v2 = v1.copy(weightKg = 69.0)
        val snapshots = listOf(
            ParsedMeasurementSnapshot(v2, 2, 200, false, MeasurementRepository.sha256(MeasurementRepository.canonicalPayload(v2)), "m:2"),
            ParsedMeasurementSnapshot(v1, 1, 100, false, MeasurementRepository.sha256(MeasurementRepository.canonicalPayload(v1)), "m:1"),
        )

        assertEquals(true, repository.applyImportedAggregate(snapshots))
        assertEquals(listOf(2L, 1L), db.healthDao().measurementSnapshots("m").map { it.version })
        assertEquals(69.0, db.bodyMeasurementDao().getById("m")!!.weightKg ?: 0.0, 0.0)
        assertEquals(false, repository.applyImportedAggregate(snapshots.reversed()))
    }

    @Test
    fun `maximal imported tombstone removes projection while retaining both history versions`() = runTest {
        val repository = MeasurementRepository(db)
        val measurement = BodyMeasurementEntity("m", 100, weightKg = 70.0)
        val live = ParsedMeasurementSnapshot(measurement, 1, 100, false, null, "m:1")
        val tombstone = ParsedMeasurementSnapshot(measurement, 2, 200, true, null, "m:2")

        assertEquals(true, repository.applyImportedAggregate(listOf(tombstone, live)))
        assertNull(db.bodyMeasurementDao().getById("m"))
        assertEquals(listOf(2L, 1L), db.healthDao().measurementSnapshots("m").map { it.version })
    }
}
