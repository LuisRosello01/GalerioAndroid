package com.example.galerio.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.galerio.data.local.AppDatabase
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.remote.adapter.DateTimeAdapter
import com.example.galerio.data.remote.api.CloudApiService
import com.example.galerio.data.remote.interceptor.AuthInterceptor
import com.example.galerio.data.remote.interceptor.LoggingInterceptor
import com.example.galerio.data.repository.AuthRepository
import com.example.galerio.data.repository.CloudSyncRepository
import com.example.galerio.data.repository.MediaRepository
import com.example.galerio.utils.DeviceInfoProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoilOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ============ ROOM DATABASE ============

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideMediaItemDao(database: AppDatabase): MediaItemDao {
        return database.mediaItemDao()
    }

    @Provides
    @Singleton
    fun provideSyncedMediaDao(database: AppDatabase): SyncedMediaDao {
        return database.syncedMediaDao()
    }

    @Provides
    @Singleton
    fun provideMediaRepository(
        @ApplicationContext context: Context,
        mediaItemDao: MediaItemDao,
        cloudSyncRepository: CloudSyncRepository,
        syncedMediaDao: SyncedMediaDao
    ): MediaRepository {
        return MediaRepository(context, mediaItemDao, cloudSyncRepository, syncedMediaDao)
    }

    // ============ AUTENTICACIÓN ============

    @Provides
    @Singleton
    fun provideAuthManager(
        @ApplicationContext context: Context
    ): AuthManager {
        return AuthManager(context)
    }

    @Provides
    @Singleton
    fun provideDeviceInfoProvider(
        @ApplicationContext context: Context
    ): DeviceInfoProvider {
        return DeviceInfoProvider(context)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        apiService: CloudApiService,
        authManager: AuthManager,
        deviceInfoProvider: DeviceInfoProvider
    ): AuthRepository {
        return AuthRepository(apiService, authManager, deviceInfoProvider)
    }

    // ============ RETROFIT & OKHTTP ============

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .registerTypeAdapter(Long::class.java, DateTimeAdapter())
            .registerTypeAdapter(Long::class.javaObjectType, DateTimeAdapter())
            .create()
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
    }

    @Provides
    @Singleton
    fun provideCustomLoggingInterceptor(): LoggingInterceptor {
        return LoggingInterceptor()
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(authManager: AuthManager): AuthInterceptor {
        return AuthInterceptor(authManager)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @CoilOkHttpClient
    fun provideCoilOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(CloudApiService.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideCloudApiService(retrofit: Retrofit): CloudApiService {
        return retrofit.create(CloudApiService::class.java)
    }

    // ============ SINCRONIZACIÓN ============

    @Provides
    @Singleton
    fun provideCloudSyncRepository(
        @ApplicationContext context: Context,
        apiService: CloudApiService,
        authManager: AuthManager,
        mediaItemDao: MediaItemDao,
        syncedMediaDao: SyncedMediaDao
    ): CloudSyncRepository {
        return CloudSyncRepository(context, apiService, authManager, mediaItemDao, syncedMediaDao)
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @CoilOkHttpClient okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25) // Usar 25% de la memoria disponible
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // 2% del espacio en disco
                    .build()
            }
            .respectCacheHeaders(false) // No respetar cache headers del servidor
            .build()
    }
}
