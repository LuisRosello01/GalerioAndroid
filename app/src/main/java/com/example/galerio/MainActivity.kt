package com.example.galerio

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.ImageLoader
import com.example.galerio.ui.MainScreen
import com.example.galerio.ui.VideoPlayerScreen
import com.example.galerio.ui.auth.LoginScreen
import com.example.galerio.ui.auth.RegisterScreen
import com.example.galerio.ui.theme.GalerioTheme
import com.example.galerio.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GalerioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(imageLoader)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(imageLoader: ImageLoader) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    // Determinar la pantalla inicial basándose en el estado de autenticación
    val startDestination = if (isAuthenticated) "media_list" else "login"

    // Observar cambios en el estado de autenticación y navegar automáticamente
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            // Si el usuario fue deslogueado, navegar a login
            navController.navigate("login") {
                popUpTo(0) { inclusive = true } // Limpiar toda la pila de navegación
            }
        }
    }

    NavHost(navController, startDestination = startDestination) {
        // Pantalla de Login
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("media_list") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate("register")
                }
            )
        }

        // Pantalla de Registro
        composable("register") {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate("media_list") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Pantalla principal (requiere autenticación)
        composable("media_list") {
            MainScreen(
                onVideoClick = { videoUri ->
                    navController.navigate("video_player?uri=$videoUri")
                },
                imageLoader = imageLoader,
                onLogout = {
                    navController.navigate("login") {
                        popUpTo("media_list") { inclusive = true }
                    }
                }
            )
        }

        // Pantalla de reproductor de video
        composable(
            "video_player?uri={uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val uri = Uri.parse(backStackEntry.arguments?.getString("uri"))
            VideoPlayerScreen(videoUri = uri) {
                navController.popBackStack()
            }
        }
    }
}