package com.corecoder.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_configs ORDER BY name ASC")
    fun getAllProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    suspend fun getById(id: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs WHERE isDefault = 1 LIMIT 1")
    fun getDefaultFlow(): Flow<ProviderConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderConfigEntity)

    @Update
    suspend fun update(provider: ProviderConfigEntity)

    @Delete
    suspend fun delete(provider: ProviderConfigEntity)

    @Query("UPDATE provider_configs SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Query("UPDATE provider_configs SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: String)

    /** Set a provider as default in one transaction. */
    @Transaction
    suspend fun setAsDefault(id: String) {
        clearAllDefaults()
        setDefault(id)
    }
}
