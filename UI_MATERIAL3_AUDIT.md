# UI audit: Material Design 3 moderno en GalerioAndroid

Relacionado con #18.

## Resumen

Se ha realizado una revisión de la UI actual de `GalerioAndroid` contrastándola con documentación reciente de Jetpack Compose + Material Design 3.

Conclusión: la app **ya usa Material 3**, pero su adopción es todavía **funcional y parcial**, no completa. La base actual es sólida, pero hay margen claro para incorporar patrones y componentes más modernos: búsqueda M3, diseño adaptativo para tablets/foldables, tematización más rica, eliminación de colores hardcodeados y modernización de auth.

---

## Qué está bien actualmente

### Base Material 3 ya presente
- `GalerioTheme` ya usa `MaterialTheme`
- Soporte de `dynamicLightColorScheme` / `dynamicDarkColorScheme`
- `MainActivity.kt` ya activa `enableEdgeToEdge()`
- `MainScreen.kt` usa `Scaffold`
- `MediaList.kt` usa `PullToRefreshBox`
- Se usan componentes Material 3 como `Card`, `Badge`, `FilterChip`, `AlertDialog`, `Snackbar`, etc.

### Arquitectura UI razonablemente ordenada
- `MainActivity.kt` centraliza la navegación por autenticación
- `MainScreen.kt` coordina sync, permisos y detalle visual
- `MediaList.kt` delega al `MediaViewModel`
- La lógica de sync y negocio no está mezclada de forma extrema con la UI

---

## Hallazgos principales

## 1. Theme correcto, pero todavía muy básico
Archivos implicados:
- `app/src/main/java/com/example/galerio/ui/theme/Theme.kt`
- `app/src/main/java/com/example/galerio/ui/theme/Type.kt`
- `app/src/main/java/com/example/galerio/ui/theme/Color.kt`

### Observaciones
- Bien: color dinámico en Android 12+
- Bien: soporte light/dark
- Débil: `Typography` casi no está definida; solo se personaliza `bodyLarge`
- Débil: no hay `Shapes` del sistema visual
- Débil: varios componentes siguen dependiendo de colores hardcodeados y no de roles de `colorScheme`

### Impacto
La app usa M3, pero no transmite todavía una identidad M3 completa ni aprovecha bien la jerarquía visual basada en superficies, tipografía y shapes.

---

## 2. `MainScreen.kt` usa una estructura válida, pero con un patrón visual todavía clásico
Archivo implicado:
- `app/src/main/java/com/example/galerio/ui/MainScreen.kt`

### Observaciones
- `Scaffold` bien aplicado
- `TopAppBar` simple con menú overflow
- La sincronización se expone con cards/banners superiores
- El detalle de imagen/video se muestra como overlay manual (`Box` + `zIndex`)

### Limitaciones actuales
- La app bar es funcional, pero poco moderna para una galería
- No hay búsqueda integrada ni filtros de primer nivel
- El patrón overlay manual no escala bien a tablets/foldables
- La pantalla principal no está preparada para layout adaptativo moderno

---

## 3. `MediaList.kt` ya usa `PullToRefreshBox`, pero la cuadrícula es rígida
Archivo implicado:
- `app/src/main/java/com/example/galerio/ui/MediaList.kt`

### Observaciones
- Muy buen punto: ya usa `PullToRefreshBox`, alineado con Material 3 moderno
- La grid es fija: `GridCells.Fixed(3)`
- Los estados vacío/carga/error son correctos, pero visualmente básicos
- Los headers por fecha no explotan mucho la jerarquía visual M3

### Recomendación
Pasar a una rejilla adaptativa (`GridCells.Adaptive(...)`) para mejorar el comportamiento en anchos distintos y facilitar evolución futura.

---

## 4. `ImageCard.kt` y `VideoCard.kt` siguen muy apoyadas en colores hardcodeados
Archivos implicados:
- `app/src/main/java/com/example/galerio/ui/components/ImageCard.kt`
- `app/src/main/java/com/example/galerio/ui/components/VideoCard.kt`

### Observaciones
- Estados visuales con verdes/grises directos
- Overlays y fondos no están totalmente integrados con `MaterialTheme.colorScheme`
- La elevación y los shapes no siguen un sistema unificado del tema

### Impacto
Esto dificulta que el look and feel sea coherente en dark theme, dynamic color y futuros ajustes de branding.

---

## 5. `LoginScreen.kt` y `RegisterScreen.kt` funcionan bien, pero no aprovechan lo más reciente de Material 3
Archivos implicados:
- `app/src/main/java/com/example/galerio/ui/auth/LoginScreen.kt`
- `app/src/main/java/com/example/galerio/ui/auth/RegisterScreen.kt`

### Observaciones
- Formularios correctos y usables
- Uso clásico de `OutlinedTextField` + `PasswordVisualTransformation`
- Jerarquía visual sencilla, todavía poco expresiva

### Oportunidad
Las releases recientes de Compose Material 3 incorporan `SecureTextField` y `OutlinedSecureTextField`, que encajan muy bien en login/registro.

---

## 6. `FullScreenImage.kt` y `VideoPlayerScreen.kt` son útiles, pero poco Material 3 / poco adaptativos
Archivos implicados:
- `app/src/main/java/com/example/galerio/ui/FullScreenImage.kt`
- `app/src/main/java/com/example/galerio/ui/VideoPlayerScreen.kt`

### Observaciones
- Fondo negro y controles construidos manualmente
- Snackbars embebidos en overlay
- El patrón está orientado a móvil fullscreen, no a list-detail adaptativo

### Limitación
Para móvil puede ser aceptable, pero en tablets/foldables sería preferible migrar a un patrón list-detail o supporting pane.

---

## Contraste con documentación reciente de Material 3

La revisión se ha contrastado con documentación reciente de Jetpack Compose / Material 3 (Context7), y las APIs más relevantes para el futuro de esta app son:

### 1. Búsqueda moderna
APIs observadas:
- `SearchBar`
- `ExpandedFullScreenSearchBar`
- `ExpandedDockedSearchBar`
- `SearchBarState`
- `AppBarWithSearch`

### Relevancia para Galerio
Muy alta. Una galería se beneficia mucho de:
- búsqueda por nombre o ruta
- filtros por tipo (`Image` / `Video`)
- filtros por origen (local / cloud)
- filtros por estado de sincronización
- filtros por fecha

---

### 2. Navegación adaptativa
APIs observadas:
- `NavigationSuiteScaffold`

### Relevancia para Galerio
Media hoy, alta a medio plazo. Encaja si se separan destinos top-level como:
- Galería
- Sincronización
- Actividad
- Ajustes / Cuenta

---

### 3. List-detail / supporting pane
APIs observadas:
- `NavigableListDetailPaneScaffold`
- `ListDetailPaneScaffold`
- `SupportingPaneScaffold`

### Relevancia para Galerio
Muy alta. El caso de uso encaja casi perfectamente:
- lista = grid de medios
- detalle = preview de imagen/video + metadata + acciones de sync

Actualmente esto se resuelve con overlays manuales; en pantallas grandes un list-detail daría un salto importante de calidad.

---

### 4. Password fields modernos
Releases recientes de Material 3 incluyen:
- `SecureTextField`
- `OutlinedSecureTextField`

### Relevancia para Galerio
Alta para `LoginScreen.kt` y `RegisterScreen.kt`.

---

### 5. Mejor soporte de edge-to-edge e insets
La documentación actual insiste en que con target SDK recientes el edge-to-edge debe estar bien resuelto. La app ya activa `enableEdgeToEdge()`, pero varias pantallas custom todavía dependen de padding y overlays manuales.

---

## Priorización propuesta

## Prioridad 1 — Mejora visual inmediata, bajo riesgo
1. Completar el theme (`Typography`, `Shapes`, mejor uso de `colorScheme`)
2. Modernizar la app bar principal (`MediumTopAppBar` o variante más rica)
3. Hacer la grid adaptativa en `MediaList.kt`
4. Eliminar colores hardcodeados de cards y overlays
5. Mejorar jerarquía visual de estados vacíos, carga y sync

---

## Prioridad 2 — Material 3 moderno de verdad
1. Añadir búsqueda real con `SearchBar` / `AppBarWithSearch`
2. Añadir filtros con chips / segmented actions
3. Modernizar auth con secure text fields si la versión lo permite
4. Replantear el panel de sync settings con patrones más modernos (`ModalBottomSheet` o pane)

---

## Prioridad 3 — Adaptive UI
1. Migrar la experiencia principal a `NavigableListDetailPaneScaffold`
2. Evaluar `NavigationSuiteScaffold` si se consolidan secciones top-level
3. Optimizar la UX para tablets y foldables

---

## Recomendación práctica

Si hay que empezar por el mayor retorno con riesgo controlado, el orden recomendado es:

1. Theme más completo
2. `MainScreen.kt` con app bar nueva + búsqueda
3. `MediaList.kt` adaptativa
4. `LoginScreen.kt` y `RegisterScreen.kt` modernizados
5. Después abordar la fase adaptive (`ListDetailPaneScaffold`)

---

## Dependencias / compatibilidad

Actualmente el proyecto usa una base moderna de Compose (`composeBom = "2024.11.00"`), pero no se ha verificado todavía que todas las APIs más nuevas del ecosistema Material 3 estén disponibles exactamente con la configuración actual.

Antes de implementar las fases más avanzadas conviene revisar:
- versión efectiva de `androidx.compose.material3`
- si hace falta actualizar BOM
- si hay que añadir dependencias de adaptive Material 3

---

## Resultado esperado

Tras estas mejoras, `GalerioAndroid` pasaría de una UI Material 3 funcional a una experiencia claramente más actual, con:
- mejor coherencia visual
- mejor aprovechamiento de dynamic color
- búsqueda moderna
- mejor soporte para pantallas grandes
- menos deuda visual en componentes custom

---

## Plan de trabajo sugerido

### Fase 1
- Theme más completo
- App bar principal más moderna
- Grid adaptativa
- Limpieza de colores hardcodeados

### Fase 2
- Búsqueda + filtros
- Auth moderna
- Mejora de UX de sync settings

### Fase 3
- List-detail adaptativo
- Evaluación de navegación adaptativa global
