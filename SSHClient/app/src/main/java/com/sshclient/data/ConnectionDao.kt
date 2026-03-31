package com.sshclient.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY createdAt DESC")
    fun getAllConnections(): Flow<List<Connection>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: Connection): Long

    @Update
    suspend fun update(connection: Connection)

    @Delete
    suspend fun delete(connection: Connection)

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: Long): Connection?
}
