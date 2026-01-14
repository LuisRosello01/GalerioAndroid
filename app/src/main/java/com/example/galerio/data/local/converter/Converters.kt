package com.example.galerio.data.local.converter

import android.net.Uri
import androidx.room.TypeConverter
import com.example.galerio.data.model.MediaType

/**
 * Converters para Room Database
 * Convierte tipos complejos a tipos primitivos que Room puede almacenar
 */
class Converters {

    @TypeConverter
    fun fromMediaType(value: MediaType): String {
        return value.name
    }

    @TypeConverter
    fun toMediaType(value: String): MediaType {
        return MediaType.valueOf(value)
    }
}

