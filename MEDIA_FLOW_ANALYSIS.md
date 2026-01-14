# Análisis del Flujo de Medios - Galerio

## Resumen Ejecutivo

La aplicación Galerio implementa un sistema robusto de gestión de archivos multimedia con arquitectura MVVM, sincronización con la nube, y estrategias avanzadas de caché. Este documento analiza el flujo completo de obtención y visualización de archivos media.

---

## 1. Arquitectura General

### Capas de la Aplicación
```
┌─────────────────────────────────────────────────┐
│              UI Layer (Compose)                 │
│  MainScreen → MediaList → ImageCard/VideoCard   │
└────────────────────┬────────────────────────────┘
                     │ StateFlow
┌────────────────────▼────────────────────────────┐
│           ViewModel Layer (Hilt)                │
│              MediaViewModel                     │
└────────────────────┬────────────────────────────┘
                     │ Repository Pattern
┌────────────────────▼────────────────────────────┐
│          Repository Layer                       │
│  MediaRepository + CloudSyncRepository          │
└─────┬────────────────────────────────────┬──────┘
      │                                    │
┌─────▼──────────┐                  ┌─────▼──────────┐
│ Local Storage  │                  │  Cloud Storage │
│ - MediaStore   │                  │  - API Service │
│ - Room DB      │                  │  - REST API    │
└────────────────┘                  └────────────────┘
```

---

## 2. Flujo de Carga Inicial

### Secuencia Detallada

1. **Inicialización de MainScreen**
   ```kotlin
   MainScreen() → RequestMediaPermissions()
   ```
   - Verifica permisos de almacenamiento
   - Android 13+: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO
   - Android ≤12: READ_EXTERNAL_STORAGE

2. **Creación de MediaViewModel** (Automático con Hilt)
   ```kotlin
   @HiltViewModel
   class MediaViewModel @Inject constructor(
       private val repository: MediaRepository
   )
   ```
   - El bloque `init {}` ejecuta `loadMedia()` automáticamente

3. **Carga desde Repository (Cache-First)**
   ```kotlin
   getDeviceMedia() {
       // Estrategia paralela
       async { loadLocalMedia() }    // Thread 1
       async { syncWithCloud() }     // Thread 2
       
       // Combina resultados
       combinedList.distinctBy { it.uri }
   }
   ```

4. **Verificación de Caché Local**
   ```kotlin
   loadLocalMedia() {
       val cached = mediaItemDao.getAllMedia()
       if (cached.isNotEmpty()) {
           return cached  // ✓ Cache hit
       }
       // ✗ Cache miss → MediaStore
   }
   ```

5. **Consulta a MediaStore** (si no hay caché)
   ```kotlin
   // Consulta paralela de imágenes
   contentResolver.query(
       MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
       projection = [_ID, DATE_MODIFIED],
       sortOrder = "DATE_MODIFIED DESC"
   )
   
   // Consulta paralela de videos
   contentResolver.query(
       MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
       ...
   )
   ```

6. **Sincronización con Nube** (paralelo)
   ```kotlin
   CloudSyncRepository.syncWithCloud() {
       // Mutex para evitar sincronizaciones simultáneas
       syncMutex.withLock {
           // Cache de 30 segundos
           if (cachedCloudItems != null && ageInMs < 30000) {
               return cachedCloudItems
           }
           // Llamada a API
           apiService.getUserMedia(token)
       }
   }
   ```

7. **Actualización de UI**
   ```kotlin
   // StateFlow emite nuevos valores
   _mediaItems.value = sortedList
   _isLoading.value = false
   
   // Compose se recompone automáticamente
   val mediaItems by viewModel.mediaItems.collectAsState()
   ```

---

## 3. Estructura de MediaItem

```kotlin
data class MediaItem(
    val uri: String,              // "content://media/external/images/123"
    val type: MediaType,          // Image | Video
    val dateModified: Long,       // Unix timestamp (milisegundos)
    val relativePath: String?,    // "DCIM/Camera/"
    val duration: Long?,          // Solo para videos
    val isCloudItem: Boolean,     // true si viene de la nube
    val cloudId: String?,         // ID del backend
    val hasThumbnail: Boolean,    // Tiene miniatura disponible
    val thumbnailUri: String?     // URL o URI del thumbnail
)
```

### Origen de Datos
- **Local**: `uri` es ContentUri del MediaStore
- **Nube**: `uri` es URL del servidor, `cloudId` no nulo

---

## 4. UI: MediaList con LazyVerticalGrid

### Composición de la Lista

```kotlin
@Composable
fun MediaList() {
    // Estados reactivos
    val mediaItems by viewModel.mediaItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    // Pull-to-refresh
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = { viewModel.refreshMedia() }
    ) {
        // Agrupación por fecha
        val grouped = mediaItems.groupBy { 
            it.dateModified.toLocalDate().format("EEE, d MMM")
        }
        
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp)) {
            grouped.forEach { (date, items) ->
                // Header de fecha
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(date)
                }
                // Items de media
                items(items) { mediaItem ->
                    when (mediaItem.type) {
                        MediaType.Image -> ImageCard(...)
                        MediaType.Video -> VideoCard(...)
                    }
                }
            }
        }
    }
}
```

### Estados de UI

| Estado | Condición | Visualización |
|--------|-----------|---------------|
| **Carga inicial** | `isLoading && mediaItems.isEmpty()` | CircularProgressIndicator centrado |
| **Recarga** | `isLoading && mediaItems.isNotEmpty()` | Indicador de pull-to-refresh |
| **Vacío** | `!isLoading && mediaItems.isEmpty()` | "No media found" |
| **Normal** | `!isLoading && mediaItems.isNotEmpty()` | Grid de medios |
| **Error** | `error != null` | Snackbar con mensaje |

---

## 5. Carga de Imágenes con Coil

### Configuración
```kotlin
@Provides
@Singleton
fun provideImageLoader(context: Context): ImageLoader {
    return ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder(context)
            .maxSizePercent(0.25)  // 25% de RAM
            .build()
        }
        .diskCache { DiskCache.Builder()
            .directory(context.cacheDir.resolve("image_cache"))
            .maxSizePercent(0.02)  // 2% de disco
            .build()
        }
        .build()
}
```

### Uso en ImageCard
```kotlin
AsyncImage(
    model = ImageRequest.Builder(context)
        .data(mediaItem.uri)
        .crossfade(true)
        .build(),
    imageLoader = imageLoader,
    contentDescription = null
)
```

**Ventajas de Coil:**
- Caché automático en memoria y disco
- Soporte nativo para ContentUri
- Carga asíncrona sin bloquear UI
- Transformaciones eficientes

---

## 6. Estrategias de Optimización Actual

### 6.1 Caché Multinivel

#### Nivel 1: Room Database (Persistente)
- **Duración**: 24 horas (configurable)
- **Propósito**: Evitar queries a MediaStore en cada inicio
- **Invalidación**: `forceRefresh()` o después de 24h

```kotlin
@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_items ORDER BY dateModified DESC")
    fun getAllMedia(): Flow<List<MediaItemEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)
}
```

#### Nivel 2: Caché de Nube (En Memoria)
- **Duración**: 30 segundos
- **Propósito**: Evitar llamadas repetidas a API
- **Implementación**: Variables en CloudSyncRepository

```kotlin
private var lastSyncTime = 0L
private var cachedCloudItems: List<MediaItem>? = null
```

#### Nivel 3: Coil ImageLoader (Híbrido)
- **Memoria**: 25% de RAM disponible (LRU)
- **Disco**: 2% de almacenamiento interno
- **Propósito**: Renderizado rápido de imágenes

### 6.2 Carga Paralela con Coroutines

```kotlin
suspend fun getDeviceMedia() = withContext(Dispatchers.IO) {
    // Lanzar en paralelo
    val localJob = async { loadLocalMedia() }
    val cloudJob = async { cloudSyncRepository.syncWithCloud() }
    
    // Esperar ambos
    val local = localJob.await()
    val cloud = cloudJob.await()
    
    // Combinar y eliminar duplicados
    combine(local, cloud).distinctBy { it.uri }
}
```

**Beneficios:**
- Reduce tiempo de carga total a `max(timeLocal, timeCloud)` en lugar de `timeLocal + timeCloud`
- Aprovecha threads de I/O dispatcher

### 6.3 Mutex para Sincronización

```kotlin
private val syncMutex = Mutex()

suspend fun syncWithCloud() {
    syncMutex.withLock {
        // Solo una sincronización a la vez
        // Evita race conditions y llamadas duplicadas
    }
}
```

---

## 7. Flujo de Actualización (Pull-to-Refresh)

```
Usuario desliza hacia abajo
         │
         ▼
MediaList.onRefresh() ejecutado
         │
         ▼
viewModel.refreshMedia() llamado
         │
         ▼
repository.forceRefresh()
         │
         ├─> mediaItemDao.deleteAll()          [Limpia Room]
         ├─> cloudSyncRepository.clearCache()  [Limpia nube]
         │
         ▼
repository.getDeviceMedia()
         │
         ├─> MediaStore (imágenes + videos)
         ├─> API de nube (archivos remotos)
         │
         ▼
Combinar, ordenar, eliminar duplicados
         │
         ▼
_mediaItems.value = newList
         │
         ▼
Compose recompone MediaList
         │
         ▼
Usuario ve lista actualizada
```

---

## 8. Gestión de Errores

### Niveles de Manejo

1. **Repository Layer**
   ```kotlin
   try {
       val items = loadFromSource()
       Result.success(items)
   } catch (e: Exception) {
       Log.e(TAG, "Error loading", e)
       Result.failure(e)
   }
   ```

2. **ViewModel Layer**
   ```kotlin
   repository.getDeviceMedia()
       .onSuccess { items ->
           _mediaItems.value = items
       }
       .onFailure { exception ->
           _error.value = exception.message
           _mediaItems.value = emptyList()
       }
   ```

3. **UI Layer**
   ```kotlin
   LaunchedEffect(error) {
       error?.let {
           snackbarHostState.showSnackbar(
               message = "Error: $it",
               actionLabel = "Reintentar"
           )
       }
   }
   ```

### Tipos de Errores Manejados
- **SecurityException**: Permisos denegados
- **IOException**: Fallos de red (nube)
- **SQLiteException**: Errores de base de datos
- **IllegalStateException**: Estados inválidos

---

## 9. Navegación y Visualización Completa

### Flujo de Selección

```
MediaList
    │
    ├─> Usuario toca ImageCard
    │       │
    │       ▼
    │   onMediaClick(uri, MediaType.Image)
    │       │
    │       ▼
    │   MainScreen actualiza selectedMediaUri
    │       │
    │       ▼
    │   FullScreenImage se superpone (zIndex = 1f)
    │       │
    │       ├─> AsyncImage con Coil (imagen completa)
    │       ├─> Gestos de zoom/pan
    │       └─> Botón de cerrar
    │
    └─> Usuario toca VideoCard
            │
            ▼
        onMediaClick(uri, MediaType.Video)
            │
            ▼
        MainScreen actualiza selectedMediaUri
            │
            ▼
        VideoPlayerScreen se superpone
            │
            ├─> ExoPlayer con controles
            ├─> Reproducción automática
            └─> Botón de cerrar
```

### Implementación de Superposición

```kotlin
@Composable
fun MainScreen() {
    var selectedMediaUri by remember { mutableStateOf<String?>(null) }
    
    Scaffold(...) {
        // Lista de fondo
        MediaList { uri, type ->
            selectedMediaUri = uri
        }
        
        // Vista completa superpuesta
        selectedMediaUri?.let { uri ->
            Box(modifier = Modifier.zIndex(1f)) {
                if (isVideo) {
                    VideoPlayerScreen(uri) { selectedMediaUri = null }
                } else {
                    FullScreenImage(uri) { selectedMediaUri = null }
                }
            }
        }
    }
}
```

---

## 10. Integración con Autenticación

### Control de Acceso

```kotlin
@Composable
fun AppNavigation() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()
    
    // Determinar pantalla inicial
    val startDestination = if (isAuthenticated) "media_list" else "login"
    
    // Observar cambios de autenticación
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }
}
```

### Sincronización con Usuario

- **CloudSyncRepository** obtiene el token de `AuthManager`
- Las llamadas a API incluyen header de autorización
- Si el token expira, se redirige a login automáticamente

---

## 11. Métricas de Rendimiento

### Tiempos Típicos de Carga

| Escenario | Tiempo Esperado | Optimización |
|-----------|----------------|--------------|
| Cache hit (Room) | ~50-100ms | Flow reactivo |
| MediaStore (1000 items) | ~200-500ms | Consulta optimizada |
| API Cloud (10 items) | ~300-1000ms | Caché de 30s |
| Carga completa inicial | ~500-1500ms | Carga paralela |
| Force refresh | ~1000-2000ms | Feedback visual |

### Consumo de Memoria

- **Room DB**: ~5-10 MB para 10,000 items
- **Coil Cache (Memoria)**: Max 25% RAM (~200MB en device típico)
- **Coil Cache (Disco)**: Max 2% disco (~500MB)

---

## 12. Puntos de Mejora Identificados

### Optimizaciones Potenciales

1. **Paginación de MediaStore**
   ```kotlin
   // Actual: Carga todo de una vez
   // Propuesta: Cargar en chunks de 100 items
   query(..., limit = 100, offset = pageIndex * 100)
   ```

2. **Thumbnails Dedicados**
   ```kotlin
   // Usar thumbnails de MediaStore para grid
   MediaStore.Images.Thumbnails.getThumbnail(
       contentResolver, 
       imageId, 
       MediaStore.Images.Thumbnails.MINI_KIND
   )
   ```

3. **Caché más Inteligente**
   ```kotlin
   // Invalidar solo medios modificados desde última sincronización
   val lastSync = preferences.getLastSyncTime()
   query(where = "date_modified > ?", lastSync)
   ```

4. **Compresión de Imágenes en Nube**
   ```kotlin
   // Subir versiones comprimidas para ahorro de ancho de banda
   Compressor.compress(context, imageFile) {
       quality(80)
       format(Bitmap.CompressFormat.WEBP)
   }
   ```

---

## 13. Testing del Flujo

### Test Cases Críticos

1. **ViewModel Tests**
   ```kotlin
   @Test
   fun `loadMedia should update mediaItems on success`() = runTest {
       // Given
       val mockItems = listOf(mockMediaItem)
       coEvery { repository.getDeviceMedia() } returns Result.success(mockItems)
       
       // When
       val viewModel = MediaViewModel(repository)
       
       // Then
       assertEquals(mockItems, viewModel.mediaItems.value)
   }
   ```

2. **Repository Tests**
   ```kotlin
   @Test
   fun `getDeviceMedia should use cache when valid`() = runTest {
       // Given
       dao.insertAll(cachedItems)
       
       // When
       val result = repository.getDeviceMedia()
       
       // Then
       verify(exactly = 0) { contentResolver.query(...) }
   }
   ```

3. **UI Tests**
   ```kotlin
   @Test
   fun `MediaList should show items after loading`() {
       composeTestRule.setContent {
           MediaList(...)
       }
       
       composeTestRule.onNodeWithText("Loading").assertIsDisplayed()
       composeTestRule.waitUntil { items.isNotEmpty() }
       composeTestRule.onNodeWithTag("media_grid").assertIsDisplayed()
   }
   ```

---

## 14. Conclusiones

### Fortalezas del Diseño Actual

✅ **Arquitectura sólida**: MVVM con separación clara de responsabilidades  
✅ **Caché efectivo**: Múltiples niveles reducen latencia  
✅ **Carga paralela**: Optimiza tiempo de respuesta  
✅ **UI reactiva**: StateFlow + Compose = UX fluida  
✅ **Manejo de errores**: Robusto y visible al usuario  
✅ **Sincronización nube**: Integración transparente  

### Áreas de Oportunidad

⚠️ **Paginación**: Cargar todo de MediaStore puede ser lento con >10,000 items  
⚠️ **Thumbnails**: Cargar imágenes completas en grid consume mucha memoria  
⚠️ **Caché inteligente**: Invalidación selectiva vs. limpiar todo  
⚠️ **Progreso de sincronización**: No hay feedback detallado del progreso de subida/bajada  
⚠️ **Manejo offline**: Mejorar experiencia cuando no hay conexión  

---

## Referencias

- **MediaStore**: https://developer.android.com/reference/android/provider/MediaStore
- **Room**: https://developer.android.com/training/data-storage/room
- **Coil**: https://coil-kt.github.io/coil/
- **Jetpack Compose**: https://developer.android.com/jetpack/compose
- **Kotlin Coroutines**: https://kotlinlang.org/docs/coroutines-overview.html

