package com.example.galerio.data.repository

import android.net.Uri
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.MediaType

/**
 * FakeMediaRepository para testing
 * Simula el comportamiento del MediaRepository sin depender del sistema Android
 */
class FakeMediaRepository : MediaRepository(null, null) {

    private var shouldReturnError = false
    private var fakeMediaItems = mutableListOf<MediaItem>()

    fun setMediaItems(items: List<MediaItem>) {
        fakeMediaItems = items.toMutableList()
    }

    fun setShouldReturnError(value: Boolean) {
        shouldReturnError = value
    }

    override suspend fun getDeviceMedia(): Result<List<MediaItem>> {
        return if (shouldReturnError) {
            Result.failure(Exception("Test error: Failed to load media"))
        } else {
            Result.success(fakeMediaItems)
        }
    }

    override suspend fun getImages(): Result<List<MediaItem>> {
        return if (shouldReturnError) {
            Result.failure(Exception("Test error: Failed to load images"))
        } else {
            Result.success(fakeMediaItems.filter { it.type == MediaType.Image })
        }
    }

    override suspend fun getVideos(): Result<List<MediaItem>> {
        return if (shouldReturnError) {
            Result.failure(Exception("Test error: Failed to load videos"))
        } else {
            Result.success(fakeMediaItems.filter { it.type == MediaType.Video })
        }
    }

    companion object {
        fun createFakeMediaItems(): List<MediaItem> {
            return listOf(
                MediaItem(
                    uri = Uri.parse("content://media/image/1"),
                    type = MediaType.Image,
                    dateModified = 1704067200L, // 2024-01-01
                    relativePath = "Pictures/",
                    duration = null
                ),
                MediaItem(
                    uri = Uri.parse("content://media/image/2"),
                    type = MediaType.Image,
                    dateModified = 1704153600L, // 2024-01-02
                    relativePath = "Pictures/",
                    duration = null
                ),
                MediaItem(
                    uri = Uri.parse("content://media/video/1"),
                    type = MediaType.Video,
                    dateModified = 1704240000L, // 2024-01-03
                    relativePath = "Movies/",
                    duration = 30000L
                )
            )
        }
    }
}
