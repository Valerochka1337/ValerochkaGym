package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Upsert
    suspend fun upsertReport(report: HealthReportEntity)

    @Insert
    suspend fun insertReportSnapshot(snapshot: HealthReportSnapshotEntity)

    @Query("SELECT * FROM health_report_snapshots WHERE syncId = :syncId ORDER BY version DESC")
    suspend fun reportSnapshots(syncId: String): List<HealthReportSnapshotEntity>

    @Query("SELECT * FROM health_report_snapshots WHERE syncId = :syncId ORDER BY version DESC")
    fun observeReportSnapshots(syncId: String): Flow<List<HealthReportSnapshotEntity>>

    @Query("SELECT * FROM health_report_snapshots WHERE syncId = :syncId AND operationId = :operationId LIMIT 1")
    suspend fun reportSnapshotForOperation(syncId: String, operationId: String): HealthReportSnapshotEntity?

    @Insert
    suspend fun insertObservations(observations: List<HealthObservationEntity>)

    @Query("SELECT * FROM health_reports WHERE syncId = :syncId")
    suspend fun report(syncId: String): HealthReportEntity?

    @Query("SELECT * FROM health_reports WHERE syncId = :syncId")
    fun observeReport(syncId: String): Flow<HealthReportEntity?>

    @Query("SELECT * FROM health_observations WHERE reportSyncId = :reportSyncId ORDER BY observedAt ASC")
    suspend fun observations(reportSyncId: String): List<HealthObservationEntity>

    @Query("DELETE FROM health_observations WHERE reportSyncId = :reportSyncId")
    suspend fun deleteObservations(reportSyncId: String): Int

    @Query("SELECT * FROM health_observations WHERE reportSyncId = :reportSyncId ORDER BY observedAt ASC")
    fun observeObservations(reportSyncId: String): Flow<List<HealthObservationEntity>>

    /** History deliberately includes corrected and revoked reports so their audit trail survives. */
    @Query("SELECT * FROM health_reports ORDER BY reportedAt ASC")
    fun observeReportsForHistory(): Flow<List<HealthReportEntity>>

    @Query("""
        SELECT health_observations.* FROM health_observations
        INNER JOIN health_reports ON health_reports.syncId = health_observations.reportSyncId
        WHERE health_observations.isTombstone = 0
          AND health_reports.isTombstone = 0
          AND health_reports.status != 'REVOKED'
        ORDER BY health_observations.observedAt ASC
    """)
    fun observeObservationsForHistory(): Flow<List<HealthObservationEntity>>

    @Query("SELECT * FROM health_documents WHERE reportSyncId = :reportSyncId ORDER BY createdAt ASC")
    fun observeDocuments(reportSyncId: String): Flow<List<HealthDocumentEntity>>

    @Query("""
        SELECT health_sync_conflicts.* FROM health_sync_conflicts
        LEFT JOIN health_observations ON health_observations.syncId = health_sync_conflicts.syncId
        WHERE health_sync_conflicts.syncId = :reportSyncId
           OR health_observations.reportSyncId = :reportSyncId
        ORDER BY health_sync_conflicts.createdAt ASC
    """)
    fun observeConflictsForReport(reportSyncId: String): Flow<List<HealthSyncConflictEntity>>

    @Query("SELECT * FROM health_restrictions WHERE syncId = :syncId")
    suspend fun restriction(syncId: String): HealthRestrictionEntity?

    @Upsert
    suspend fun upsertRestriction(restriction: HealthRestrictionEntity)

    @Insert
    suspend fun insertRestrictionSnapshot(snapshot: HealthRestrictionSnapshotEntity)

    @Query("SELECT * FROM health_restriction_snapshots WHERE syncId = :syncId ORDER BY version DESC")
    suspend fun restrictionSnapshots(syncId: String): List<HealthRestrictionSnapshotEntity>

    @Query("SELECT * FROM health_restriction_snapshots WHERE operationId = :operationId ORDER BY syncId ASC")
    suspend fun restrictionSnapshotsForOperation(operationId: String): List<HealthRestrictionSnapshotEntity>

    @Query("SELECT * FROM health_restriction_snapshots WHERE syncId = :syncId ORDER BY version DESC")
    fun observeRestrictionSnapshots(syncId: String): Flow<List<HealthRestrictionSnapshotEntity>>

    @Upsert
    suspend fun upsertDocument(document: HealthDocumentEntity)

    @Query("SELECT * FROM health_documents WHERE id = :documentId")
    suspend fun document(documentId: String): HealthDocumentEntity?

    @Query("SELECT * FROM health_documents WHERE state = :state")
    suspend fun documentsInState(state: String): List<HealthDocumentEntity>

    @Query("DELETE FROM health_documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String): Int

    @Upsert
    suspend fun upsertConflict(conflict: HealthSyncConflictEntity)

    @Query("SELECT * FROM health_sync_conflicts WHERE category = :category AND syncId = :syncId AND version = :version")
    suspend fun conflict(category: String, syncId: String, version: Long): HealthSyncConflictEntity?

    @Query("DELETE FROM health_sync_conflicts WHERE category = :category AND syncId = :syncId AND version = :version")
    suspend fun deleteConflict(category: String, syncId: String, version: Long): Int

    @Insert
    suspend fun insertMeasurementSnapshot(snapshot: MeasurementSnapshotEntity)

    @Insert
    suspend fun insertOutbox(entry: HealthSyncOutboxEntity)

    /** The immutable snapshot is never visible without its durable delivery request. */
    @Transaction
    suspend fun insertMeasurementHistory(
        snapshot: MeasurementSnapshotEntity,
        outbox: HealthSyncOutboxEntity,
    ) {
        insertMeasurementSnapshot(snapshot)
        insertOutbox(outbox)
    }

    @Query("SELECT * FROM measurement_snapshots WHERE syncId = :syncId ORDER BY version DESC")
    suspend fun measurementSnapshots(syncId: String): List<MeasurementSnapshotEntity>

    @Query("SELECT * FROM health_reports WHERE isTombstone = 0 ORDER BY reportedAt DESC")
    fun observeLiveReports(): Flow<List<HealthReportEntity>>

    @Query("SELECT * FROM health_reports WHERE isTombstone = 0 AND status != 'REVOKED' ORDER BY reportedAt DESC")
    fun observeCurrentReports(): Flow<List<HealthReportEntity>>

    @Query("SELECT * FROM health_restrictions WHERE isTombstone = 0 ORDER BY confirmedAt DESC")
    fun observeLiveRestrictions(): Flow<List<HealthRestrictionEntity>>

    @Query("SELECT * FROM health_restrictions ORDER BY confirmedAt DESC")
    fun observeRestrictionsForHistory(): Flow<List<HealthRestrictionEntity>>

    @Query("SELECT * FROM health_sync_conflicts ORDER BY createdAt ASC")
    suspend fun conflicts(): List<HealthSyncConflictEntity>

    /** Global health surface includes measurement and restriction conflicts, not reports only. */
    @Query("SELECT * FROM health_sync_conflicts ORDER BY createdAt ASC")
    fun observeConflicts(): Flow<List<HealthSyncConflictEntity>>

    @Query("SELECT * FROM health_sync_conflicts WHERE category = :category AND syncId = :syncId AND version = :version")
    fun observeConflict(category: String, syncId: String, version: Long): Flow<HealthSyncConflictEntity?>
}
