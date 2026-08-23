package com.example.composeapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ItemDao {

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Query("SELECT * FROM items ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<ItemEntity>
}
