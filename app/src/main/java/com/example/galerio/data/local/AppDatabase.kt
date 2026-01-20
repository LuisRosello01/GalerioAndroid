package com.example.galerio.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.galerio.data.local.converter.Converters
import com.example.galerio.data.local.converter.UriConverter
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.local.entity.MediaItemEntity
import com.example.galerio.data.local.entity.SyncedMediaEntity

/**
 * Base de datos Room para Galerio
 */
@Database(
    entities = [MediaItemEntity::class, SyncedMediaEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class, UriConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaItemDao(): MediaItemDao
    abstract fun syncedMediaDao(): SyncedMediaDao

    companion object {
        const val DATABASE_NAME = "galerio_database"

        /**
         * Migración de versión 1 a 2: añade tabla synced_media
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS synced_media (
                        localUri TEXT NOT NULL PRIMARY KEY,
                        cloudId TEXT NOT NULL,
                        hash TEXT NOT NULL,
                        syncedAt INTEGER NOT NULL
                    )
                """)
            }
        }
    }
}

