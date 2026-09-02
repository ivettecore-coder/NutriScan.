package com.nutriscan

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// ============================================================================
// 1. MODELOS DE DATOS & ENUMS
// ============================================================================

enum class AppThemeOption {
    GREEN_MINT,   // Verde / Esmeralda
    PINK_PASTEL,  // Rosa Pastel
    BLUE_GLACIER  // Azul Glaciar
}

enum class NutritionColor {
    VERDE,     // Saludable / Recomendado
    AMARILLO,  // Moderado
    ROJO       // No Recomendado / Alto en sellos
}

enum class GusMood {
    HAPPY,       // 🟢 Feliz
    CURIOUS,     // 🟡 Curioso / Neutro
    SAD,         // 🔴 Triste / Alerta
    BATHING,     // En la tina con burbujas
    EATING       // Comiendo en la mesa
}

enum class VirtualPetScene {
    MAIN,
    BATH,
    FEED
}

data class GusOutfit(
    val id: String,
    val name: String,
    val emojiIcon: String,
    val description: String,
    val isProExclusive: Boolean = false,
    val pointsCost: Float = 0f,
    val hatEmoji: String = "",
    val accessoryEmoji: String = "",
    val auraColor: Color = Color.Transparent
)

data class MacroNutrients(
    val proteinas: Float = 0f,
    val carbohidratos: Float = 0f,
    val grasas: Float = 0f,
    val fibra: Float = 0f
)

data class FoodItem(
    val id: String = UUID.randomUUID().toString(),
    val nombre: String,
    val calorias: Int,
    val macros: MacroNutrients,
    val semaforo: NutritionColor,
    val categoria: String = "Almuerzo",
    val timestamp: Long = System.currentTimeMillis()
)

data class DailyArchive(
    val dateString: String,
    val totalCalories: Int,
    val totalProteins: Float,
    val totalCarbs: Float,
    val totalFats: Float,
    val items: List<FoodItem>
)

data class UserProfile(
    val id: String,
    val name: String,
    val email: String,
    val isGuest: Boolean = true,
    val isPro: Boolean = false,
    val gusPoints: Float = 15.0f,
    val streakDays: Int = 3
)

sealed interface AuthUiState {
    data class Guest(val guestId: String) : AuthUiState
    data class Authenticated(val user: UserProfile) : AuthUiState
    object Loading : AuthUiState
}

// ============================================================================
// 2. VIEWMODELS & GESTIÓN DE ESTADO GLOBAL
// ============================================================================

class NutriScanViewModel : ViewModel() {

    // --- AUTENTICACIÓN & MODO CREADOR ---
    private val _uiState = MutableStateFlow<AuthUiState>(
        AuthUiState.Guest(guestId = "guest-${(1000..9999).random()}")
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _isCreatorAdmin = MutableStateFlow(false)
    val isCreatorAdmin: StateFlow<Boolean> = _isCreatorAdmin.asStateFlow()

    private val _selectedTheme = MutableStateFlow(AppThemeOption.GREEN_MINT)
    val selectedTheme: StateFlow<AppThemeOption> = _selectedTheme.asStateFlow()

    // --- BALANCE CALÓRICO Y HISTORIAL ---
    private val _todayFoods = MutableStateFlow<List<FoodItem>>(emptyList())
    val todayFoods: StateFlow<List<FoodItem>> = _todayFoods.asStateFlow()

    private val _historyArchives = MutableStateFlow<List<DailyArchive>>(emptyList())
    val historyArchives: StateFlow<List<DailyArchive>> = _historyArchives.asStateFlow()

    val targetCalories = 2000
    val targetProteins = 120f
    val targetCarbs = 220f
    val targetFats = 65f

    // --- MASCOTA GUS ---
    private val _gusMood = MutableStateFlow(GusMood.HAPPY)
    val gusMood: StateFlow<GusMood> = _gusMood.asStateFlow()

    private val _equippedOutfitId = MutableStateFlow("default")
    val equippedOutfitId: StateFlow<String> = _equippedOutfitId.asStateFlow()

    private val _unlockedOutfitIds = MutableStateFlow(setOf("default", "chef_basico"))
    val unlockedOutfitIds: StateFlow<Set<String>> = _unlockedOutfitIds.asStateFlow()

    private val _gusPoints = MutableStateFlow(15.0f)
    val gusPoints: StateFlow<Float> = _gusPoints.asStateFlow()

    val availableOutfits = listOf(
        GusOutfit("default", "Clásico Natural", "🥑", "Gus en su estado más fresco", false, 0f),
        GusOutfit("chef_basico", "Chef Profesional", "👨‍🍳", "Gorro de cocina tradicional", false, 5f, "👨‍🍳"),
        GusOutfit("superhero", "Superhéroe Fit", "🦸‍♂️", "Capa dorada y antifaz de campeón", true, 10f, "👑", "⚡", Color(0xFFFFD700)),
        GusOutfit("rey_inca", "Rey Inca Sagrado", "👑", "Corona solar dorada y poncho imperial", true, 15f, "☀️", "🦙", Color(0xFFF59E0B)),
        GusOutfit("cintas_negras", "Cintas Negras Karate", "🥋", "Vincha marcial y cinturón de disciplina", false, 8f, "🥋", "🥋"),
        GusOutfit("doctor", "Doctor Nutriólogo", "🩺", "Bata blanca y estetoscopio clínico", true, 12f, "🩺", "🩺", Color(0xFF38BDF8))
    )

    val isGuest: Boolean get() = _uiState.value is AuthUiState.Guest
    val isAdmin: Boolean get() = _isCreatorAdmin.value
    val isPro: Boolean get() {
        if (_isCreatorAdmin.value) return true
        val state = _uiState.value
        return state is AuthUiState.Authenticated && state.user.isPro
    }

    fun setTheme(theme: AppThemeOption) { _selectedTheme.value = theme }
    fun toggleCreatorAdminBypass() { _isCreatorAdmin.update { !it } }

    fun loginWithGoogle(email: String, name: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Authenticated(
                UserProfile(
                    id = "google-${email.hashCode()}",
                    name = name,
                    email = email,
                    isGuest = false,
                    isPro = false,
                    gusPoints = _gusPoints.value
                )
            )
        }
    }

    fun logoutToGuest() {
        _uiState.value = AuthUiState.Guest(guestId = "guest-${(1000..9999).random()}")
    }

    // Agregar alimento y disparar reacción de Gus según semáforo
    fun addFood(food: FoodItem) {
        _todayFoods.update { listOf(food) + it }
        _gusPoints.update { it + 0.5f }

        when (food.semaforo) {
            NutritionColor.VERDE -> _gusMood.value = GusMood.HAPPY
            NutritionColor.AMARILLO -> _gusMood.value = GusMood.CURIOUS
            NutritionColor.ROJO -> _gusMood.value = GusMood.SAD
        }
    }

    fun setGusMood(mood: GusMood) {
        _gusMood.value = mood
    }

    fun equipOutfit(outfitId: String) {
        _equippedOutfitId.value = outfitId
    }

    fun unlockOutfit(outfit: GusOutfit): Boolean {
        if (isPro || isAdmin || _gusPoints.value >= outfit.pointsCost) {
            if (!isPro && !isAdmin) {
                _gusPoints.update { it - outfit.pointsCost }
            }
            _unlockedOutfitIds.update { it + outfit.id }
            _equippedOutfitId.value = outfit.id
            return true
        }
        return false
    }

    // Reinicio diario a medianoche
    fun performDailyReset() {
        val currentItems = _todayFoods.value
        if (currentItems.isNotEmpty()) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86400000))
            val archive = DailyArchive(
                dateString = dateStr,
                totalCalories = currentItems.sumOf { it.calorias },
                totalProteins = currentItems.map { it.macros.proteinas }.sum(),
                totalCarbs = currentItems.map { it.macros.carbohidratos }.sum(),
                totalFats = currentItems.map { it.macros.grasas }.sum(),
                items = currentItems
            )
            _historyArchives.update { listOf(archive) + it }
            _todayFoods.value = emptyList()
        }
    }
}

// ============================================================================
// 3. WORKMANAGER (REINICIO AUTOMÁTICO A MEDIANOCHE)
// ============================================================================

class DailyResetWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Se sincroniza el reseteo con el reloj del sistema a medianoche
            scheduleMidnightReset(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun scheduleMidnightReset(context: Context) {
            val now = Calendar.getInstance()
            val midnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val delay = midnight.timeInMillis - now.timeInMillis

            val request = OneTimeWorkRequestBuilder<DailyResetWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "NutriScanDailyResetWorker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

// ============================================================================
// 4. MAIN ACTIVITY & RUTAS
// ============================================================================

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Inicio", Icons.Rounded.Home)
    object Scanner : Screen("scanner", "Escáner", Icons.Rounded.QrCodeScanner)
    object NutriHub : Screen("despensa_ideas", "Despensa/Ideas", Icons.Rounded.Inventory2)
    object History : Screen("history", "Historial", Icons.Rounded.History)
    object Profile : Screen("profile", "Perfil", Icons.Rounded.Person)
    object Wardrobe : Screen("wardrobe", "Armario Gus", Icons.Rounded.Checkroom)
    object PetInteraction : Screen("pet_interaction", "Mundo Gus", Icons.Rounded.Pets)
}

class MainActivity : ComponentActivity() {
    private val viewModel: NutriScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyResetWorker.scheduleMidnightReset(applicationContext)

        setContent {
            val currentTheme by viewModel.selectedTheme.collectAsState()

            NutriScanCustomTheme(theme = currentTheme) {
                NutriScanApp(viewModel = viewModel)
            }
        }
    }
}

// ============================================================================
// 5. SISTEMA DE TEMAS DINÁMICOS CON SEMÁFORO NUTRICIONAL INALTERABLE
// ============================================================================

object NutritionColors {
    val Verde = Color(0xFF10B981)
    val Amarillo = Color(0xFFF59E0B)
    val Rojo = Color(0xFFEF4444)
}

@Composable
fun NutriScanCustomTheme(
    theme: AppThemeOption,
    content: @Composable () -> Unit
) {
    val colorScheme = when (theme) {
        AppThemeOption.GREEN_MINT -> lightColorScheme(
            primary = Color(0xFF10B981),
            primaryContainer = Color(0xFFD1FAE5),
            secondary = Color(0xFF059669),
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F5F9)
        )
        AppThemeOption.PINK_PASTEL -> lightColorScheme(
            primary = Color(0xFFEC4899),
            primaryContainer = Color(0xFFFCE7F3),
            secondary = Color(0xFFDB2777),
            background = Color(0xFFFDF2F8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFFCE7F3)
        )
        AppThemeOption.BLUE_GLACIER -> lightColorScheme(
            primary = Color(0xFF0284C7),
            primaryContainer = Color(0xFFE0F2FE),
            secondary = Color(0xFF0369A1),
            background = Color(0xFFF0F9FF),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE0F2FE)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// ============================================================================
// 6. ESTRUCTURA DE NAVEGACIÓN Y CONTENEDOR PRINCIPAL
// ============================================================================

@Composable
fun NutriScanApp(viewModel: NutriScanViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute != Screen.Wardrobe.route && currentRoute != Screen.PetInteraction.route) {
                NutriScanBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenScanner = { navController.navigate(Screen.Scanner.route) },
                    onOpenPetWorld = { navController.navigate(Screen.PetInteraction.route) }
                )
            }
            composable(Screen.Scanner.route) {
                ScannerScreen(viewModel = viewModel)
            }
            composable(Screen.NutriHub.route) {
                DespensaIdeasScreen(viewModel = viewModel)
            }
            composable(Screen.History.route) {
                HistoryScreen(viewModel = viewModel)
            }
            composable(Screen.Profile.route) {
                ProfileScreen(
                    viewModel = viewModel,
                    onOpenWardrobe = { navController.navigate(Screen.Wardrobe.route) }
                )
            }
            composable(Screen.Wardrobe.route) {
                GusWardrobeScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.PetInteraction.route) {
                GusVirtualPetScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenWardrobe = { navController.navigate(Screen.Wardrobe.route) }
                )
            }
        }
    }
}

// ============================================================================
// 7. BARRA DE NAVEGACIÓN INFERIOR (5 SECCIONES UNIFICADAS)
// ============================================================================

@Composable
fun NutriScanBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        Screen.Home,
        Screen.Scanner,
        Screen.NutriHub,
        Screen.History,
        Screen.Profile
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            modifier = Modifier.height(66.dp)
        ) {
            items.forEach { screen ->
                val isSelected = currentRoute == screen.route
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onNavigate(screen.route) },
                    icon = {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = screen.title,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

// ============================================================================
// 8. PANTALLA INICIO (HOME) CON BALANCE CALÓRICO Y GUS
// ============================================================================

@Composable
fun HomeScreen(
    viewModel: NutriScanViewModel,
    onOpenScanner: () -> Unit,
    onOpenPetWorld: () -> Unit
) {
    val foods by viewModel.todayFoods.collectAsState()
    val gusMood by viewModel.gusMood.collectAsState()
    val equippedId by viewModel.equippedOutfitId.collectAsState()
    val isPro = viewModel.isPro
    val isAdmin = viewModel.isAdmin

    val totalKcal = foods.sumOf { it.calorias }
    val totalProt = foods.map { it.macros.proteinas }.sum()
    val totalCarb = foods.map { it.macros.carbohidratos }.sum()
    val totalFat = foods.map { it.macros.grasas }.sum()

    val progress = (totalKcal.toFloat() / viewModel.targetCalories.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecera
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "¡Hola, ${if (viewModel.isGuest) "Invitado" else "Ale"}! 👋",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Tu meta nutricional de hoy",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isPro || isAdmin) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isAdmin) "CREADOR" else "PRO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF92400E),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Widget Mascota Gus con reacción emocional interactiva
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPetWorld() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GusAvatarView(
                    mood = gusMood,
                    equippedOutfitId = equippedId,
                    sizeDp = 70
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (gusMood) {
                            GusMood.HAPPY -> "¡Gus está feliz con tu nutrición! 🥑"
                            GusMood.CURIOUS -> "Comida moderada. ¡Sigue así! 🧐"
                            GusMood.SAD -> "¡Cuidado con los ultraprocesados! 😟"
                            else -> "¡Gus te acompaña hoy!"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Toca para alimentar o bañar a Gus",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Widget Balance Calórico
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Balance Calórico",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$totalKcal / ${viewModel.targetCalories} kcal",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MacroBadge("Proteínas", totalProt, viewModel.targetProteins, Color(0xFF3B82F6))
                    MacroBadge("Carbos", totalCarb, viewModel.targetCarbs, Color(0xFFF59E0B))
                    MacroBadge("Grasas", totalFat, viewModel.targetFats, Color(0xFFEF4444))
                }
            }
        }

        Button(
            onClick = onOpenScanner,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Rounded.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Escanear Plato o Producto", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MacroBadge(label: String, current: Float, target: Float, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
            Text("${current.toInt()}/${target.toInt()}g", fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
    }
}

// ============================================================================
// 9. MASCOTA VIRTUAL: ESCENAS DE BAÑO Y ALIMENTACIÓN
// ============================================================================

@Composable
fun GusVirtualPetScreen(
    viewModel: NutriScanViewModel,
    onBack: () -> Unit,
    onOpenWardrobe: () -> Unit
) {
    val gusMood by viewModel.gusMood.collectAsState()
    val equippedId by viewModel.equippedOutfitId.collectAsState()
    var currentScene by remember { mutableStateOf(VirtualPetScene.MAIN) }

    var soapOffsetX by remember { mutableStateOf(0f) }
    var soapOffsetY by remember { mutableStateOf(0f) }
    var cleanLevel by remember { mutableStateOf(40) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Barra Superior
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Atrás")
            }
            Text("Cuidado de Gus el Aguacate", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            IconButton(onClick = onOpenWardrobe) {
                Icon(Icons.Rounded.Checkroom, contentDescription = "Armario")
            }
        }

        // Escenario Interactivo Central
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .background(
                    when (currentScene) {
                        VirtualPetScene.BATH -> Color(0xFFE0F2FE)
                        VirtualPetScene.FEED -> Color(0xFFFEF3C7)
                        else -> MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(32.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when (currentScene) {
                VirtualPetScene.MAIN -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GusAvatarView(mood = gusMood, equippedOutfitId = equippedId, sizeDp = 140)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "¡Gus está listo para jugar y comer sano!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                VirtualPetScene.BATH -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🧼 ¡Arrastra el jabón sobre Gus para bañarlo!", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        GusAvatarView(mood = GusMood.BATHING, equippedOutfitId = equippedId, sizeDp = 130)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Limpieza: $cleanLevel%", fontSize = 11.sp, color = Color(0xFF0284C7), fontWeight = FontWeight.Black)
                    }

                    // Jabón arrastrable
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(soapOffsetX.roundToInt(), soapOffsetY.roundToInt()) }
                            .size(54.dp)
                            .background(Color(0xFF38BDF8), CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    soapOffsetX += dragAmount.x
                                    soapOffsetY += dragAmount.y
                                    if (cleanLevel < 100) cleanLevel += 1
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🧼", fontSize = 26.sp)
                    }
                }
                VirtualPetScene.FEED -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🍽️ Toca un alimento para darle de comer a Gus", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        GusAvatarView(mood = GusMood.EATING, equippedOutfitId = equippedId, sizeDp = 130)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                viewModel.addFood(FoodItem(nombre = "Ensalada", calorias = 150, macros = MacroNutrients(5f, 10f, 2f), semaforo = NutritionColor.VERDE))
                            }) { Text("🥗 Ensalada") }

                            Button(onClick = {
                                viewModel.addFood(FoodItem(nombre = "Fruta", calorias = 80, macros = MacroNutrients(1f, 18f, 0f), semaforo = NutritionColor.VERDE))
                            }) { Text("🍎 Fruta") }
                        }
                    }
                }
            }
        }

        // Botones de Acción de Mascota
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { currentScene = VirtualPetScene.MAIN },
                modifier = Modifier.weight(1f)
            ) { Text("🥑 Casa") }

            Button(
                onClick = { currentScene = VirtualPetScene.BATH },
                modifier = Modifier.weight(1f)
            ) { Text("🛁 Bañar") }

            Button(
                onClick = { currentScene = VirtualPetScene.FEED },
                modifier = Modifier.weight(1f)
            ) { Text("🥣 Alimentar") }
        }
    }
}

// ============================================================================
// 10. TIENDA Y ARMARIO DE ATUENDOS CON VISTA PREVIA
// ============================================================================

@Composable
fun GusWardrobeScreen(
    viewModel: NutriScanViewModel,
    onBack: () -> Unit
) {
    val equippedId by viewModel.equippedOutfitId.collectAsState()
    val unlockedIds by viewModel.unlockedOutfitIds.collectAsState()
    val gusPoints by viewModel.gusPoints.collectAsState()

    var previewOutfit by remember { mutableStateOf(viewModel.availableOutfits.first { it.id == equippedId }) }
    var showGuestModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Atrás") }
            Text("Armario de Atuendos", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Surface(
                color = Color(0xFFFEF3C7),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "🪙 ${"%.1f".format(gusPoints)} pts",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF92400E),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Vista previa dinámica antes de equipar
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Vista Previa Dinámica", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                GusAvatarView(
                    mood = GusMood.HAPPY,
                    equippedOutfitId = previewOutfit.id,
                    sizeDp = 120
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(previewOutfit.name, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(previewOutfit.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Colección de Trajes", fontWeight = FontWeight.Bold, fontSize = 14.sp)

        // Lista de Trajes
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.availableOutfits) { outfit ->
                val isUnlocked = unlockedIds.contains(outfit.id)
                val isEquipped = equippedId == outfit.id

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = if (previewOutfit.id == outfit.id) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { previewOutfit = outfit }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(outfit.emojiIcon, fontSize = 28.sp)
                            Column {
                                Text(outfit.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(if (outfit.isProExclusive) "⭐ Exclusivo PRO" else "${outfit.pointsCost} pts", fontSize = 11.sp)
                            }
                        }

                        if (isEquipped) {
                            Text("Equipado ✅", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        } else if (isUnlocked) {
                            Button(onClick = { viewModel.equipOutfit(outfit.id) }) { Text("Equipar") }
                        } else {
                            Button(onClick = {
                                if (viewModel.isGuest && outfit.isProExclusive && !viewModel.isAdmin) {
                                    showGuestModal = true
                                } else {
                                    viewModel.unlockOutfit(outfit)
                                }
                            }) { Text("Desbloquear") }
                        }
                    }
                }
            }
        }
    }

    if (showGuestModal) {
        GuestLinkGoogleDialog(
            onDismiss = { showGuestModal = false },
            onConnectGoogle = {
                viewModel.loginWithGoogle("usuario@gmail.com", "Ale")
                showGuestModal = false
            }
        )
    }
}

// ============================================================================
// 11. AVATAR COMPOSABLE DE GUS (REACCIONES EMOCIONALES Y TRAJES)
// ============================================================================

@Composable
fun GusAvatarView(
    mood: GusMood,
    equippedOutfitId: String,
    sizeDp: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "GusBounce")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (mood == GusMood.HAPPY) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GusScale"
    )

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .scale(scale),
        contentAlignment = Alignment.Center
    ) {
        // Cuerpo de Gus
        Text(
            text = when (mood) {
                GusMood.HAPPY -> "🥑"
                GusMood.CURIOUS -> "🥑"
                GusMood.SAD -> "🥑"
                GusMood.BATHING -> "🥑"
                GusMood.EATING -> "🥑"
            },
            fontSize = (sizeDp * 0.75).sp
        )

        // Sombreros / Accesorios superpuestos
        when (equippedOutfitId) {
            "superhero" -> Text("👑", fontSize = (sizeDp * 0.35).sp, modifier = Modifier.align(Alignment.TopCenter))
            "rey_inca" -> Text("☀️", fontSize = (sizeDp * 0.35).sp, modifier = Modifier.align(Alignment.TopCenter))
            "chef_basico" -> Text("👨‍🍳", fontSize = (sizeDp * 0.35).sp, modifier = Modifier.align(Alignment.TopCenter))
            "doctor" -> Text("🩺", fontSize = (sizeDp * 0.35).sp, modifier = Modifier.align(Alignment.BottomEnd))
        }

        // Emoticón de emoción superpuesto
        when (mood) {
            GusMood.HAPPY -> Text("✨", fontSize = 16.sp, modifier = Modifier.align(Alignment.TopEnd))
            GusMood.SAD -> Text("💧", fontSize = 16.sp, modifier = Modifier.align(Alignment.TopStart))
            GusMood.BATHING -> Text("🫧", fontSize = 16.sp, modifier = Modifier.align(Alignment.TopEnd))
            else -> {}
        }
    }
}

// ============================================================================
// 12. DESPENSA E IDEAS (NUTRIHUB CON TABROW SUPERIOR)
// ============================================================================

@Composable
fun DespensaIdeasScreen(viewModel: NutriScanViewModel) {
    var selectedSubTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // TabRow Superior
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            indicator = { tabPositions ->
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedSubTab])
                        .fillMaxHeight()
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                )
            }
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = { Text("🥦 Mi Despensa", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = { Text("💡 Ideas y Recetas", fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedSubTab == 0) {
            Text("Alimentos disponibles en casa:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Quinua perlada orgánica (500g)")
            Text("• Palta fuerte fresca (3 unidades)")
            Text("• Queso fresco pasteurizado")
            Text("• Habas verdes peladas")
        } else {
            Text("Sugerencia de Receta Peruana Saludable:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🥗 Solterito Arequipeño Nutritivo", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text("Alto en proteínas y fibra vegetal sin sellos de advertencia.", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Calorías: 280 kcal • Proteínas: 18g • Semáforo: 🟢 Saludable", color = NutritionColors.Verde, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}

// ============================================================================
// 13. HISTORIAL ORGANIZADO POR FECHAS Y DÍAS PREVIOS
// ============================================================================

@Composable
fun HistoryScreen(viewModel: NutriScanViewModel) {
    val archives by viewModel.historyArchives.collectAsState()
    val todayFoods by viewModel.todayFoods.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Historial Diario Nutricional", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("Registros acumulados guardados automáticamente", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Hoy
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hoy (En Curso)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${todayFoods.sumOf { it.calorias }} kcal registradas hoy", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                }
            }
        }

        // Días Anteriores Archivados
        items(archives) { archive ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📅 Fecha: ${archive.dateString}", fontWeight = FontWeight.Bold)
                    Text("Total: ${archive.totalCalories} kcal • P: ${archive.totalProteins}g • C: ${archive.totalCarbs}g", fontSize = 12.sp)
                }
            }
        }
    }
}

// ============================================================================
// 14. PERFIL, GOOGLE AUTH Y MODO CREADOR BYPASS
// ============================================================================

@Composable
fun ProfileScreen(
    viewModel: NutriScanViewModel,
    onOpenWardrobe: () -> Unit
) {
    val isGuest = viewModel.isGuest
    val isAdmin by viewModel.isCreatorAdmin.collectAsState()
    val selectedTheme by viewModel.selectedTheme.collectAsState()
    var showGoogleModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Mi Perfil", fontSize = 22.sp, fontWeight = FontWeight.Black)

        // Estado de Cuenta
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = if (isGuest) "Modo Invitado 👤" else "Ale Monteza Espinoza (Google)", fontWeight = FontWeight.Bold)
                if (isGuest) {
                    Button(
                        onClick = { showGoogleModal = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Conectar con Google") }
                } else {
                    OutlinedButton(onClick = { viewModel.logoutToGuest() }) { Text("Cerrar Sesión") }
                }
            }
        }

        // Selector de Temas
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tema Global de la App", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.setTheme(AppThemeOption.GREEN_MINT) }) { Text("Verde") }
                    Button(onClick = { viewModel.setTheme(AppThemeOption.PINK_PASTEL) }) { Text("Rosa") }
                    Button(onClick = { viewModel.setTheme(AppThemeOption.BLUE_GLACIER) }) { Text("Azul") }
                }
            }
        }

        // Armario
        Button(
            onClick = onOpenWardrobe,
            modifier = Modifier.fillMaxWidth()
        ) { Text("👕 Abrir Armario de Gus") }

        // Modo Creador / Admin Bypass
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (isAdmin) Color(0xFFEEF2FF) else MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Modo Creador / Admin", fontWeight = FontWeight.Bold)
                    Text("Bypass ilimitado sin pagos", fontSize = 11.sp)
                }
                Switch(checked = isAdmin, onCheckedChange = { viewModel.toggleCreatorAdminBypass() })
            }
        }
    }

    if (showGoogleModal) {
        GuestLinkGoogleDialog(
            onDismiss = { showGoogleModal = false },
            onConnectGoogle = {
                viewModel.loginWithGoogle("montezaespinozaale@gmail.com", "Ale Monteza Espinoza")
                showGoogleModal = false
            }
        )
    }
}

// ============================================================================
// 15. ESCÁNER & DIÁLOGOS DE BLOQUEO
// ============================================================================

@Composable
fun ScannerScreen(viewModel: NutriScanViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("📷 Escáner Inteligente con IA", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Simula un escaneo para registrar:", fontSize = 12.sp)

            Button(onClick = {
                viewModel.addFood(
                    FoodItem(nombre = "Ensalada Andina", calorias = 220, macros = MacroNutrients(12f, 24f, 6f), semaforo = NutritionColor.VERDE)
                )
            }) { Text("🟢 Escanear Plato Saludable (Verde)") }

            Button(onClick = {
                viewModel.addFood(
                    FoodItem(nombre = "Snack Ultraprocesado", calorias = 540, macros = MacroNutrients(3f, 62f, 28f), semaforo = NutritionColor.ROJO)
                )
            }) { Text("🔴 Escanear Snack Alto en Sellos (Rojo)") }
        }
    }
}

@Composable
fun GuestLinkGoogleDialog(
    onDismiss: () -> Unit,
    onConnectGoogle: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("⭐ Función PRO", fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(
                    text = "Para guardar tu progreso y desbloquear atuendos exclusivos, vincula tu cuenta de Google.",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp
                )
                Button(
                    onClick = onConnectGoogle,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Conectar con Google") }

                TextButton(onClick = onDismiss) { Text("Seguir como Invitado") }
            }
        }
    }
}
