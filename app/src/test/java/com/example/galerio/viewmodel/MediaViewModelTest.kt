package com.example.galerio.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.example.galerio.data.repository.FakeMediaRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class MediaViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeMediaRepository
    private lateinit var viewModel: MediaViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeMediaRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        // Given
        fakeRepository.setMediaItems(FakeMediaRepository.createFakeMediaItems())

        // When
        viewModel = MediaViewModel(fakeRepository)

        // Then
        assertThat(viewModel.isLoading.value).isTrue()
    }

    @Test
    fun `loadMedia success updates mediaItems and sets loading to false`() = runTest {
        // Given
        val fakeItems = FakeMediaRepository.createFakeMediaItems()
        fakeRepository.setMediaItems(fakeItems)
        viewModel = MediaViewModel(fakeRepository)

        // When
        advanceUntilIdle()

        // Then
        assertThat(viewModel.mediaItems.value).isEqualTo(fakeItems)
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `loadMedia failure sets error and clears mediaItems`() = runTest {
        // Given
        fakeRepository.setShouldReturnError(true)
        viewModel = MediaViewModel(fakeRepository)

        // When
        advanceUntilIdle()

        // Then
        assertThat(viewModel.mediaItems.value).isEmpty()
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.error.value).isNotNull()
        assertThat(viewModel.error.value).contains("Test error")
    }

    @Test
    fun `refreshMedia reloads data successfully`() = runTest {
        // Given
        val initialItems = FakeMediaRepository.createFakeMediaItems()
        fakeRepository.setMediaItems(initialItems)
        viewModel = MediaViewModel(fakeRepository)
        advanceUntilIdle()

        // When - cambiar los items y refrescar
        val newItems = initialItems.take(1)
        fakeRepository.setMediaItems(newItems)
        viewModel.refreshMedia()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.mediaItems.value).hasSize(1)
        assertThat(viewModel.isLoading.value).isFalse()
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        // Given
        fakeRepository.setShouldReturnError(true)
        viewModel = MediaViewModel(fakeRepository)
        advanceUntilIdle()
        assertThat(viewModel.error.value).isNotNull()

        // When
        viewModel.clearError()

        // Then
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `mediaItems flow emits correct values over time`() = runTest {
        // Given
        val fakeItems = FakeMediaRepository.createFakeMediaItems()
        fakeRepository.setMediaItems(fakeItems)
        viewModel = MediaViewModel(fakeRepository)

        // When/Then
        viewModel.mediaItems.test {
            // Estado inicial (vacío)
            assertThat(awaitItem()).isEmpty()

            // Después de cargar
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(fakeItems)
        }
    }

    @Test
    fun `isLoading flow transitions correctly`() = runTest {
        // Given
        fakeRepository.setMediaItems(FakeMediaRepository.createFakeMediaItems())
        viewModel = MediaViewModel(fakeRepository)

        // When/Then
        viewModel.isLoading.test {
            // Estado inicial
            assertThat(awaitItem()).isTrue()

            // Después de cargar
            advanceUntilIdle()
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `empty repository returns empty list successfully`() = runTest {
        // Given
        fakeRepository.setMediaItems(emptyList())
        viewModel = MediaViewModel(fakeRepository)

        // When
        advanceUntilIdle()

        // Then
        assertThat(viewModel.mediaItems.value).isEmpty()
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `multiple refresh calls handle correctly`() = runTest {
        // Given
        fakeRepository.setMediaItems(FakeMediaRepository.createFakeMediaItems())
        viewModel = MediaViewModel(fakeRepository)
        advanceUntilIdle()

        // When
        viewModel.refreshMedia()
        advanceUntilIdle()
        viewModel.refreshMedia()
        advanceUntilIdle()
        viewModel.refreshMedia()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.mediaItems.value).hasSize(3)
        assertThat(viewModel.isLoading.value).isFalse()
    }
}

