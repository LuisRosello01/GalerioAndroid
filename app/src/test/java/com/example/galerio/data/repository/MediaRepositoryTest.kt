package com.example.galerio.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import com.example.galerio.data.model.MediaType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE)
class MediaRepositoryTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var repository: MediaRepository
    private lateinit var cloudSyncRepository: CloudSyncRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        cloudSyncRepository = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        coEvery { cloudSyncRepository.syncWithCloud() } returns Result.success(emptyList())
        repository = MediaRepository(context, null, cloudSyncRepository, null)
    }

    @Test
    fun `getDeviceMedia returns success with images and videos`() = runTest {
        // Given - Mock cursor for images
        val imageCursor = MatrixCursor(
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.RELATIVE_PATH
            )
        ).apply {
            addRow(arrayOf<Any?>(1L, 1704067200L, "Pictures/"))
            addRow(arrayOf<Any?>(2L, 1704153600L, "DCIM/"))
        }

        // Mock cursor for videos
        val videoCursor = MatrixCursor(
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.RELATIVE_PATH
            )
        ).apply {
            addRow(arrayOf<Any?>(1L, 1704240000L, 30000L, "Movies/"))
        }

        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns imageCursor

        every {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns videoCursor

        // When
        val result = repository.getDeviceMedia()

        // Then
        assertThat(result.isSuccess).isTrue()
        result.onSuccess { items ->
            assertThat(items).hasSize(3)
            assertThat(items.count { it.type == MediaType.Image }).isEqualTo(2)
            assertThat(items.count { it.type == MediaType.Video }).isEqualTo(1)
        }
    }

    @Test
    fun `getDeviceMedia returns empty list when no media found`() = runTest {
        // Given
        val emptyCursor = MatrixCursor(arrayOf(MediaStore.Images.Media._ID))

        every {
            contentResolver.query(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns emptyCursor

        // When
        val result = repository.getDeviceMedia()

        // Then
        assertThat(result.isSuccess).isTrue()
        result.onSuccess { items ->
            assertThat(items).isEmpty()
        }
    }

    @Test
    fun `getDeviceMedia returns failure when exception occurs`() = runTest {
        // Given
        every {
            contentResolver.query(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } throws SecurityException("Permission denied")

        // When
        val result = repository.getDeviceMedia()

        // Then
        assertThat(result.isFailure).isTrue()
        result.onFailure { exception ->
            assertThat(exception).isInstanceOf(SecurityException::class.java)
            assertThat(exception.message).contains("Permission denied")
        }
    }

    @Test
    fun `getImages returns only images`() = runTest {
        // Given
        val imageCursor = MatrixCursor(
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.RELATIVE_PATH
            )
        ).apply {
            addRow(arrayOf<Any?>(1L, 1704067200L, "Pictures/"))
            addRow(arrayOf<Any?>(2L, 1704153600L, "DCIM/"))
        }

        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns imageCursor

        // When
        val result = repository.getImages()

        // Then
        assertThat(result.isSuccess).isTrue()
        result.onSuccess { items ->
            assertThat(items).hasSize(2)
            assertThat(items.all { it.type == MediaType.Image }).isTrue()
        }
    }

    @Test
    fun `getVideos returns only videos`() = runTest {
        // Given
        val videoCursor = MatrixCursor(
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.RELATIVE_PATH
            )
        ).apply {
            addRow(arrayOf<Any?>(1L, 1704240000L, 30000L, "Movies/"))
            addRow(arrayOf<Any?>(2L, 1704326400L, 45000L, "Downloads/"))
        }

        every {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns videoCursor

        // When
        val result = repository.getVideos()

        // Then
        assertThat(result.isSuccess).isTrue()
        result.onSuccess { items ->
            assertThat(items).hasSize(2)
            assertThat(items.all { it.type == MediaType.Video }).isTrue()
            assertThat(items.all { it.duration != null }).isTrue()
        }
    }

    @Test
    fun `media items are sorted by date descending`() = runTest {
        // Given
        val imageCursor = MatrixCursor(
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED
            )
        ).apply {
            addRow(arrayOf<Any?>(1L, 1000L)) // Más antiguo
            addRow(arrayOf<Any?>(2L, 3000L)) // Más reciente
            addRow(arrayOf<Any?>(3L, 2000L)) // Medio
        }

        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns imageCursor

        every {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns MatrixCursor(arrayOf(MediaStore.Video.Media._ID))

        // When
        val result = repository.getDeviceMedia()

        // Then
        assertThat(result.isSuccess).isTrue()
        val items = result.getOrNull()!!
        assertThat(items).hasSize(3)
        // Verificar que están ordenados por fecha descendente
        // 3000 * 1000 = 3000000, etc.
        assertThat(items[0].dateModified).isEqualTo(3000000L)
        assertThat(items[1].dateModified).isEqualTo(2000000L)
        assertThat(items[2].dateModified).isEqualTo(1000000L)
    }
}
