package com.example.rangefinder

import androidx.room.*

@Dao
interface MeasurementDao {
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

