package com.example.aggregator

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey val patientId: String,
    val name: String,
    val age: Int,
    val gender: String,
    val bloodType: String,
    val medication: String,
    val description: String,
    @ColumnInfo(defaultValue = "0") val isCurrent: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "patient_reports",
    indices = [Index(value = ["patientId", "reportDate"], unique = true)]
)
data class PatientReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: String,
    val patientName: String,
    val reportDate: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "LOCAL",
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    val syncedAt: Long? = null,
    val lastSyncAttemptAt: Long? = null,
    val syncError: String? = null
)

@Dao
interface PatientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(patient: PatientEntity)

    @Query("UPDATE patients SET isCurrent = 0")
    fun clearCurrent()

    @Query("UPDATE patients SET isCurrent = 1 WHERE patientId = :patientId")
    fun markCurrent(patientId: String)

    @Query("SELECT * FROM patients WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentPatient(): PatientEntity?
}

@Dao
interface PatientReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(report: PatientReportEntity): Long

    @Query("SELECT * FROM patient_reports WHERE patientId = :patientId AND reportDate = :reportDate LIMIT 1")
    fun getReportForDay(patientId: String, reportDate: String): PatientReportEntity?

    @Query("SELECT DISTINCT reportDate FROM patient_reports WHERE patientId = :patientId ORDER BY reportDate DESC")
    fun getAvailableDates(patientId: String): List<String>

    @Query("SELECT * FROM patient_reports WHERE patientId = :patientId AND reportDate = :reportDate ORDER BY updatedAt DESC")
    fun getReportsForDay(patientId: String, reportDate: String): List<PatientReportEntity>

    @Query("SELECT * FROM patient_reports WHERE id = :reportId LIMIT 1")
    fun getReportById(reportId: Long): PatientReportEntity?

    @Query("SELECT * FROM patient_reports ORDER BY updatedAt DESC LIMIT 1")
    fun getLatestReport(): PatientReportEntity?

    @Query("SELECT * FROM patient_reports WHERE isSynced = 0 ORDER BY updatedAt ASC")
    fun getPendingSyncReports(): List<PatientReportEntity>

    @Query("UPDATE patient_reports SET isSynced = 1, syncedAt = :syncedAt, lastSyncAttemptAt = :syncedAt, syncError = NULL WHERE id = :reportId")
    fun markSynced(reportId: Long, syncedAt: Long)

    @Query("UPDATE patient_reports SET lastSyncAttemptAt = :attemptedAt, syncError = :errorMessage WHERE id = :reportId")
    fun markSyncFailed(reportId: Long, attemptedAt: Long, errorMessage: String?)
}

@Database(
    entities = [PatientEntity::class, PatientReportEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun patientReportDao(): PatientReportDao

    companion object {
        @Volatile
        private var INSTANCE: AggregatorDatabase? = null

        fun getInstance(context: Context): AggregatorDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AggregatorDatabase::class.java,
                    "aggregator_room.db"
                )
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
