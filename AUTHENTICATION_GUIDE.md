# Guía de Autenticación - Galerio

## 📋 Resumen

Sistema de autenticación completo integrado con el backend Flask que incluye:
- Login con usuario/contraseña
- Registro de nuevos usuarios
- Gestión de tokens (access + refresh)
- Información del dispositivo
- Persistencia de sesión con DataStore

## 🏗️ Arquitectura

### Modelos de Datos

#### `User.kt`
Modelo del usuario sincronizado con el backend:
- **Campos básicos**: id, username, email
- **Tokens**: token, refreshToken
- **Rol**: UserRole (ADMIN, PREMIUM, BASIC)
- **Fechas**: createdAt, updatedAt, lastLogin
- **Información personal**: displayName
- **Preferencias**: language, timezone, themePreference
- **Privacidad**: profilePublic, showActivity, emailNotifications, analyticsConsent

#### `AuthModels.kt`
Modelos de peticiones y respuestas:
- **DeviceInfo**: Información del dispositivo (id, nombre, tipo, user-agent)
- **LoginRequest**: username, password, deviceInfo
- **RegisterRequest**: username, email, password, deviceInfo, language, timezone
- **LoginResponse**: success, message, user, tokenInfo
- **RegisterResponse**: success, message, user
- **RefreshTokenRequest**: refreshToken, deviceInfo
- **RefreshTokenResponse**: success, token, refreshToken, message
- **TokenInfo**: jti, issuedAt, expires, type

### Componentes Principales

#### 1. **DeviceInfoProvider** (`utils/DeviceInfoProvider.kt`)
Genera información del dispositivo para el seguimiento de sesiones:
```kotlin
- deviceId: Android ID único del dispositivo
- deviceName: Nombre legible (ej: "Samsung Galaxy S21")
- deviceType: "mobile"
- userAgent: String completo del user-agent
```

#### 2. **AuthManager** (`data/local/preferences/AuthManager.kt`)
Gestiona la persistencia de credenciales usando DataStore:
- Guarda: token, refreshToken, user data
- Flows reactivos para observar cambios
- Métodos síncronos para interceptores

**Métodos principales:**
- `saveCredentials(user: User)`
- `updateToken(newToken, newRefreshToken)`
- `getToken()`: Para obtener token actual
- `getRefreshToken()`: Para renovación
- `logout()`: Limpia todas las credenciales
- `isAuthenticated()`: Verifica si hay sesión activa

#### 3. **AuthRepository** (`data/repository/AuthRepository.kt`)
Implementa la lógica de negocio de autenticación:

**Operaciones:**
- `login(username, password)`: Inicia sesión e incluye deviceInfo
- `register(username, email, password)`: Registra nuevo usuario
- `logout()`: Cierra sesión en servidor y local
- `refreshToken()`: Renueva el token de acceso
- `isAuthenticated()`: Verifica estado de autenticación

**Características:**
- Incluye información del dispositivo en todas las peticiones
- Maneja errores y devuelve `Result<T>`
- Logs detallados para debugging
- Siempre limpia credenciales locales en logout (aunque falle el servidor)

#### 4. **CloudApiService** (`data/remote/api/CloudApiService.kt`)
Define los endpoints de la API:

```kotlin
POST /auth/login        -> LoginResponse
POST /auth/register     -> RegisterResponse
POST /auth/refresh      -> RefreshTokenResponse
POST /auth/logout       -> LogoutResponse
```

#### 5. **AuthViewModel** (`viewmodel/AuthViewModel.kt`)
ViewModel para la UI:
- Estados reactivos: isLoading, error, currentUser, isAuthenticated
- Métodos: login(), register(), logout(), clearError()
- Verificación automática de autenticación al inicio

### Pantallas UI

#### **LoginScreen** (`ui/auth/LoginScreen.kt`)
Pantalla de inicio de sesión:
- Campos: username, password
- Validación en tiempo real
- Toggle para mostrar/ocultar contraseña
- Navegación automática al login exitoso
- Botón para ir a registro

#### **RegisterScreen** (`ui/auth/RegisterScreen.kt`)
Pantalla de registro:
- Campos: username, email, password, confirmPassword
- Validaciones:
  - Username: mínimo 3 caracteres
  - Email: formato válido
  - Password: mínimo 6 caracteres
  - ConfirmPassword: coincidencia
- Mensajes de error descriptivos
- Navegación automática al registro exitoso

## 🔧 Configuración

### 1. Base URL del Servidor
Actualizar en `CloudApiService.kt`:
```kotlin
const val BASE_URL = "https://tu-servidor.com/api/"
```

### 2. Inyección de Dependencias (Hilt)
Ya configurado en `AppModule.kt`:
- `provideAuthManager()`: DataStore para credenciales
- `provideDeviceInfoProvider()`: Información del dispositivo
- `provideAuthRepository()`: Lógica de autenticación

### 3. Interceptores HTTP
**AuthInterceptor**: Añade automáticamente el token a las peticiones
- Lee el token del AuthManager
- Añade header: `Authorization: Bearer <token>`
- Se ejecuta en todas las peticiones autenticadas

## 📱 Flujo de Uso

### Login
1. Usuario ingresa credenciales
2. Se obtiene DeviceInfo automáticamente
3. Se envía LoginRequest al servidor
4. Si es exitoso:
   - Se guarda user + tokens en DataStore
   - Se actualiza estado isAuthenticated
   - Se navega a pantalla principal

### Registro
1. Usuario completa formulario
2. Validaciones en tiempo real
3. Se obtiene DeviceInfo
4. Se envía RegisterRequest
5. Si es exitoso:
   - Auto-login (guarda credenciales)
   - Navega a pantalla principal

### Refresh Token
1. Cuando un token expira (401)
2. AuthRepository.refreshToken()
3. Envía refreshToken + deviceInfo
4. Actualiza solo los tokens (mantiene user data)

### Logout
1. Intenta notificar al servidor
2. Limpia todas las credenciales locales
3. Actualiza estado isAuthenticated = false
4. Redirige a login

## 🔐 Seguridad

### Almacenamiento
- **DataStore**: Almacenamiento encriptado de Android
- **No se almacenan contraseñas**: Solo tokens

### Tokens
- **Access Token**: Para peticiones autenticadas
- **Refresh Token**: Para renovar access token
- **JTI**: Identificador único del token (para revocación)

### Dispositivos
- Cada sesión registra:
  - ID único del dispositivo
  - Nombre del dispositivo
  - Tipo (mobile)
  - User-Agent completo
  - IP del dispositivo (desde servidor)

## 📊 Estados de la UI

### AuthViewModel States
```kotlin
isLoading: Boolean          // Mostrando loading
error: String?              // Mensaje de error
currentUser: User?          // Usuario actual
isAuthenticated: Boolean    // Estado de autenticación
```

## 🎯 Próximos Pasos

1. **Navegación**: Integrar las pantallas de login/register en el flujo principal
2. **Interceptor de Refresh**: Renovar automáticamente tokens expirados
3. **Gestión de Sesiones**: Pantalla para ver/cerrar dispositivos activos
4. **Biometría**: Añadir autenticación con huella/face
5. **Recordar sesión**: Opción para mantener sesión iniciada

## 🐛 Debugging

### Logs
Todos los componentes tienen logs con TAG:
- `AuthRepository`: Operaciones de autenticación
- `AuthInterceptor`: Headers y tokens
- `AuthViewModel`: Estados de UI

### Verificar Estado
```kotlin
// En cualquier ViewModel
authManager.isLoggedIn.collect { isLogged -> }
authManager.currentUser.collect { user -> }
authManager.authToken.collect { token -> }
```

## 📝 Notas Importantes

1. **Sincronización Backend**: Todos los campos del modelo User coinciden con el backend Flask
2. **Device Tracking**: El servidor puede rastrear dispositivos y revocar sesiones específicas
3. **Timezone**: Se envía la zona horaria del dispositivo en el registro
4. **Language**: Por defecto "es" (español)
5. **Roles**: El rol del usuario afecta permisos (ADMIN, PREMIUM, BASIC)

