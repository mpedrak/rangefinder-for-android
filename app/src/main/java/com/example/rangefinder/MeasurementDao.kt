package com.example.rangefinder

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAllByDateDescending(): Flow<List<MeasurementEntity>>
    
    @Query("SELECT * FROM measurements ORDER BY timestamp ASC")
    fun getAllByDateAscending(): Flow<List<MeasurementEntity>>
    
    @Query("SELECT * FROM measurements ORDER BY " +
           "CASE WHEN distance IS NULL THEN 1 ELSE 0 END, " +
           "CASE WHEN distance IS NULL THEN 0 ELSE distance END ASC")
    fun getAllByDistanceAscending(): Flow<List<MeasurementEntity>>
    
    @Query("SELECT * FROM measurements ORDER BY " +
           "CASE WHEN distance IS NULL THEN 1 ELSE 0 END, " +
           "CASE WHEN distance IS NULL THEN 0 ELSE distance END DESC")
    fun getAllByDistanceDescending(): Flow<List<MeasurementEntity>>
    
    @Query("SELECT * FROM measurements")
    suspend fun getAllSync(): List<MeasurementEntity>
    
    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun getById(id: String): MeasurementEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: MeasurementEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(measurements: List<MeasurementEntity>)
    
    @Delete
    suspend fun delete(measurement: MeasurementEntity)
    
    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun getCount(): Int
}

