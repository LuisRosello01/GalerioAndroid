package com.example.galerio.data.local.converter

import android.net.Uri
import androidx.room.TypeConverter

/**
 * Converter para Uri
 */
class UriConverter {

    @TypeConverter
    fun fromUri(uri: Uri?): String? {
        return uri?.toString()
    }

    @TypeConverter
    fun toUri(uriString: String?): Uri? {
        return uriString?.let { Uri.parse(it) }
    }
}

