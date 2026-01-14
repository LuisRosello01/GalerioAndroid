package com.example.galerio.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.galerio.data.local.converter.Converters
import com.example.galerio.data.local.converter.UriConverter
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.entity.MediaItemEntity

/**
 * Base de datos Room para Galerio
 */
@Database(
    entities = [MediaItemEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class, UriConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaItemDao(): MediaItemDao

    companion object {
        const val DATABASE_NAME = "galerio_database"
    }
}

