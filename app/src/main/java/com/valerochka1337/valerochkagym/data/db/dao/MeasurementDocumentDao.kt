package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: MeasurementDocumentEntity)

    @Query("SELECT * FROM measurement_documents WHERE measurementId = :measurementId ORDER BY createdAt DESC")
    fun observeForMeasurement(measurementId: String): Flow<List<MeasurementDocumentEntity>>

    @Query("SELECT * FROM measurement_documents WHERE measurementId = :measurementId AND state = 'READY' ORDER BY createdAt DESC")
    fun observeReadyForMeasurement(measurementId: String): Flow<List<MeasurementDocumentEntity>>

    @Query("SELECT * FROM measurement_documents WHERE measurementId = :measurementId ORDER BY createdAt DESC")
    suspend fun forMeasurement(measurementId: String): List<MeasurementDocumentEntity>

    @Query("SELECT * FROM measurement_documents WHERE id = :id")
    suspend fun document(id: String): MeasurementDocumentEntity?

    @Query("SELECT * FROM measurement_documents WHERE state = :state ORDER BY createdAt")
    suspend fun byState(state: String): List<MeasurementDocumentEntity>

    @Query("SELECT COUNT(*) FROM measurement_documents WHERE sha256 = :sha256 AND state = 'READY'")
    suspend fun readyReferenceCount(sha256: String): Int

    @Query("DELETE FROM measurement_documents WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM measurement_documents WHERE measurementId = :measurementId")
    suspend fun deleteForMeasurement(measurementId: String): Int
}
