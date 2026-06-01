package com.example.priceradar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

val Context.dataStore by preferencesDataStore(name = "user_settings")

// ─── Цветовая палитра (светлая тема) ────────────────────────────────────────
object AppColors {
    val Background    = Color(0xFFF5F5F0)   // тёплый офф-уайт
    val Surface       = Color(0xFFFFFFFF)   // белые карточки
    val SurfaceAlt    = Color(0xFFF0EFF8)   // бледно-лавандовый фон
    val Primary       = Color(0xFF3D5AFE)   // синий акцент
    val PrimaryLight  = Color(0xFFE8EDFF)   // бледно-синий фон
    val WBColor       = Color(0xFFAA00FF)   // фиолетовый Wildberries
    val WBLight       = Color(0xFFF3E5FF)
    val OzonColor     = Color(0xFF005BFF)   // синий Ozon
    val OzonLight     = Color(0xFFE5EEFF)
    val YandexColor   = Color(0xFFFFB300)   // жёлтый Яндекс
    val YandexLight   = Color(0xFFFFF8E1)
    val GreenPrice    = Color(0xFF00875A)   // зелёный цена
    val GreenLight    = Color(0xFFE3F9EE)
    val TextPrimary   = Color(0xFF1A1A2E)
    val TextSecondary = Color(0xFF6B7280)
    val TextHint      = Color(0xFF9CA3AF)
    val Divider       = Color(0xFFE5E7EB)
    val Discount      = Color(0xFFFF3B30)
    val DiscountBg    = Color(0xFFFFEEED)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PriceRadarApp() }
    }
}

data class Product(
    val marketplace: String,
    val name: String,
    val price: Int,
    val originalPrice: Int,
    val discountPercent: Int,
    val rating: Float,
    val reviewsCount: Int,
    val deliveryDays: Int,
    val accentColor: Color,
    val lightColor: Color,
    val productUrl: String,
    val imageUrl: String
)

// ─── API (без изменений логики) ──────────────────────────────────────────────
class ApifyApi {
    private val apiToken = "apify_api_HVzFCOusuJ2K3NfEhqWe17dViGyV9y1rxTSe"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    suspend fun searchWildberries(query: String): List<Product> = withContext(Dispatchers.IO) {
        try {
            val actorId = "2SZSjETPO5i2W8Ytc"
            val runBody = """{"keyword": "$query", "max_items": 10}"""
            val runRequest = Request.Builder()
                .url("https://api.apify.com/v2/acts/$actorId/runs?token=$apiToken")
                .post(RequestBody.create(JSON_MEDIA, runBody))
                .build()
            val runResponse = client.newCall(runRequest).execute()
            val runJson = JSONObject(runResponse.body?.string() ?: "{}")
            if (!runJson.has("data")) return@withContext emptyList()
            val runId = runJson.getJSONObject("data").getString("id")
            val datasetId = runJson.getJSONObject("data").getString("defaultDatasetId")

            var status = "RUNNING"; var attempts = 0
            while (status == "RUNNING" && attempts < 20) {
                delay(2000)
                val statusResponse = client.newCall(
                    Request.Builder().url("https://api.apify.com/v2/acts/$actorId/runs/$runId?token=$apiToken").build()
                ).execute()
                status = JSONObject(statusResponse.body?.string() ?: "{}").getJSONObject("data").getString("status")
                attempts++
            }
            if (status != "SUCCEEDED") return@withContext emptyList()

            val itemsResponse = client.newCall(
                Request.Builder().url("https://api.apify.com/v2/datasets/$datasetId/items?token=$apiToken&clean=true").build()
            ).execute()
            val itemsString = itemsResponse.body?.string() ?: "[]"
            val itemsArray = JSONObject("{\"items\":$itemsString}").getJSONArray("items")
            val products = mutableListOf<Product>()
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                val currentPrice = item.optString("current_price","0").replace("₽","").replace(" ","").trim().toIntOrNull() ?: 0
                val oldPrice = item.optString("old_price","0").replace("₽","").replace(" ","").trim().toIntOrNull() ?: 0
                val discount = item.optString("discount","0%").replace("%","").replace("−","-").trim().toIntOrNull() ?: 0
                products.add(Product(
                    marketplace = "Wildberries",
                    name = item.optString("name", query),
                    price = currentPrice, originalPrice = oldPrice, discountPercent = discount,
                    rating = 4.5f, reviewsCount = 0, deliveryDays = (2..4).random(),
                    accentColor = AppColors.WBColor, lightColor = AppColors.WBLight,
                    productUrl = item.optString("product_url",""),
                    imageUrl = item.optString("image_url","")
                ))
            }
            return@withContext products
        } catch (e: Exception) { Log.e("ApifyWB", "Error", e); return@withContext emptyList() }
    }

    fun getOzonDemo(query: String) = listOf(Product(
        marketplace = "Ozon", name = query, price = 91209, originalPrice = 115551,
        discountPercent = 21, rating = 4.6f, reviewsCount = 355, deliveryDays = 2,
        accentColor = AppColors.OzonColor, lightColor = AppColors.OzonLight,
        productUrl = "https://www.ozon.ru/product/iphone-15-pro-128gb-153079148/",
        imageUrl = "https://ir.ozone.ru/s3/multimedia-1-8/c1000/7303632675.jpg"
    ))

    fun getYandexDemo(query: String) = listOf(Product(
        marketplace = "Яндекс", name = query, price = 94169, originalPrice = 0,
        discountPercent = 0, rating = 4.5f, reviewsCount = 765, deliveryDays = 1,
        accentColor = AppColors.YandexColor, lightColor = AppColors.YandexLight,
        productUrl = "https://market.yandex.ru/search?text=${query.replace(" ","+")}",
        imageUrl = ""
    ))
}

// ─── Данные профиля ──────────────────────────────────────────────────────────
data class UserProfileData(
    val gender: String = "Не указано",
    val age: Int = 0,
    val phone: String = ""
)

suspend fun loadUserProfile(context: Context): UserProfileData {
    val prefs = context.dataStore.data.first()
    return UserProfileData(
        gender = prefs[stringPreferencesKey("gender")] ?: "Не указано",
        age    = (prefs[floatPreferencesKey("age")] ?: 0f).toInt(),
        phone  = prefs[stringPreferencesKey("phone")] ?: ""
    )
}

// ─── BottomSheet профиля ─────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBottomSheet(onDismiss: () -> Unit, onEditProfile: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var profile by remember { mutableStateOf<UserProfileData?>(null) }

    LaunchedEffect(Unit) {
        profile = loadUserProfile(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = AppColors.Surface,
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Аватар
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AppColors.PrimaryLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = AppColors.Primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Ваш профиль", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Divider(color = AppColors.Divider, thickness = 0.5.dp)
            Spacer(Modifier.height(16.dp))

            if (profile != null) {
                ProfileRow(label = "Пол:", value = profile!!.gender.ifBlank { "Не указано" })
                Spacer(Modifier.height(12.dp))
                ProfileRow(label = "Возраст:", value = if (profile!!.age > 0) "${profile!!.age} лет" else "Не указано")
                Spacer(Modifier.height(12.dp))
                ProfileRow(
                    label = "Регистрация (телефон):",
                    value = profile!!.phone.ifBlank { "Не указан" }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Эти настройки используются для индивидуального подбора выгодных предложений.",
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                    lineHeight = 19.sp, textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            } else {
                CircularProgressIndicator(color = AppColors.Primary, modifier = Modifier.size(32.dp))
            }

            Spacer(Modifier.height(24.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Сбросить профиль
                TextButton(
                    onClick = {
                        scope.launch {
                            context.dataStore.edit { s ->
                                s.remove(stringPreferencesKey("gender"))
                                s.remove(floatPreferencesKey("age"))
                                s.remove(stringPreferencesKey("phone"))
                                s.remove(booleanPreferencesKey("onboarding_complete"))
                            }
                            profile = UserProfileData()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Сбросить профиль", color = AppColors.Discount, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                // Изменить
                Button(
                    onClick = { onDismiss(); onEditProfile() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.TextPrimary),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Изменить", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ProfileRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = AppColors.Primary, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Навигация ───────────────────────────────────────────────────────────────
@Composable
fun PriceRadarApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    var isOnboardingComplete by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            val prefs = context.dataStore.data.first()
            isOnboardingComplete = prefs[booleanPreferencesKey("onboarding_complete")] ?: false
        }
    }

    if (isOnboardingComplete == null) {
        Box(Modifier.fillMaxSize().background(AppColors.Background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.Primary)
        }
    } else {
        NavHost(
            navController = navController,
            startDestination = if (isOnboardingComplete == true) "search" else "onboarding"
        ) {
            composable("onboarding") { OnboardingScreen { navController.navigate("profile") } }
            composable("profile")    { ProfileScreen   { navController.navigate("search") } }
            composable("search")     { SearchScreen(
                onSearch       = { q -> navController.navigate("results/$q") },
                onEditProfile  = { navController.navigate("profile") }
            ) }
            composable("results/{query}", arguments = listOf(navArgument("query") { type = NavType.StringType })) { back ->
                ResultsScreen(
                    query         = back.arguments?.getString("query") ?: "",
                    onBack        = { navController.popBackStack() },
                    onEditProfile = { navController.navigate("profile") }
                )
            }
        }
    }
}

// ─── Онбординг ───────────────────────────────────────────────────────────────
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Логотип
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AppColors.Primary),
            contentAlignment = Alignment.Center
        ) {
            Text("🛒", fontSize = 44.sp)
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Добро пожаловать в",
            fontSize = 16.sp,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Сравни-ка",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ваш персональный аналитик цен на маркетплейсах. Настройте профиль за секунду!",
            fontSize = 15.sp,
            color = AppColors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.TextPrimary),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Настроить профиль", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

// ─── Профиль ─────────────────────────────────────────────────────────────────
@Composable
fun ProfileScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedGender by remember { mutableStateOf("Не указывать") }
    var age by remember { mutableStateOf(25f) }
    var wantPhone by remember { mutableStateOf(false) }
    var phoneNumber by remember { mutableStateOf("") }
    val genders = listOf("Мужской", "Женский", "Не указывать")

    LaunchedEffect(Unit) {
        scope.launch {
            val prefs = context.dataStore.data.first()
            selectedGender = prefs[stringPreferencesKey("gender")] ?: "Не указывать"
            age = prefs[floatPreferencesKey("age")] ?: 25f
            phoneNumber = prefs[stringPreferencesKey("phone")] ?: ""
            if (phoneNumber.isNotEmpty()) wantPhone = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // Секция: пол
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = AppColors.Primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Укажите ваш пол", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                genders.forEach { g ->
                    val selected = selectedGender == g
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) AppColors.TextPrimary else AppColors.Background)
                            .clickable { selectedGender = g },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            g, fontSize = 13.sp,
                            color = if (selected) Color.White else AppColors.TextSecondary,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Секция: возраст
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("🗓", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Сколько вам лет?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppColors.PrimaryLight)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("${age.toInt()} лет", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Slider(
                value = age, onValueChange = { age = it }, valueRange = 10f..95f, steps = 85,
                colors = SliderDefaults.colors(thumbColor = AppColors.Primary, activeTrackColor = AppColors.Primary, inactiveTrackColor = AppColors.Divider)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("10 лет", fontSize = 12.sp, color = AppColors.TextHint)
                Text("50 лет", fontSize = 12.sp, color = AppColors.TextHint)
                Text("95 лет", fontSize = 12.sp, color = AppColors.TextHint)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Секция: телефон
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("📱", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Телефон", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.PrimaryLight).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("по желанию", fontSize = 11.sp, color = AppColors.Primary) }
                    }
                    Text("Вы можете привязать номер мобильного телефона для автоматической синхронизации вашего списка избранного и аналитических отчетов.", fontSize = 12.sp, color = AppColors.TextSecondary, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, AppColors.Divider, RoundedCornerShape(12.dp))
                    .clickable { wantPhone = !wantPhone }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = wantPhone, onCheckedChange = { wantPhone = it },
                    colors = CheckboxDefaults.colors(checkedColor = AppColors.Primary)
                )
                Spacer(Modifier.width(8.dp))
                Text("Я хочу привязать телефон", fontSize = 14.sp, color = AppColors.TextPrimary)
            }
            if (wantPhone) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phoneNumber, onValueChange = { phoneNumber = it },
                    label = { Text("+7 XXX XXX XX XX", color = AppColors.TextHint) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary, unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary
                    )
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                scope.launch {
                    context.dataStore.edit { s ->
                        s[booleanPreferencesKey("onboarding_complete")] = true
                        s[stringPreferencesKey("gender")] = selectedGender
                        s[floatPreferencesKey("age")] = age
                        if (wantPhone && phoneNumber.isNotBlank()) s[stringPreferencesKey("phone")] = phoneNumber
                        else s.remove(stringPreferencesKey("phone"))
                    }
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.TextPrimary),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Продолжить работу  ▶", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

// ─── Поиск ───────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onSearch: (String) -> Unit, onEditProfile: () -> Unit = {}) {
    var searchQuery by remember { mutableStateOf("") }
    var showProfile by remember { mutableStateOf(false) }

    if (showProfile) {
        ProfileBottomSheet(
            onDismiss      = { showProfile = false },
            onEditProfile  = onEditProfile
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // Топбар
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(AppColors.Primary),
                contentAlignment = Alignment.Center
            ) { Text("🛒", fontSize = 18.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Сравни-ка", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppColors.PrimaryLight)
                    .clickable { showProfile = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Person, null, tint = AppColors.Primary, modifier = Modifier.size(20.dp)) }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Сравнить цены\nв один клик", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, lineHeight = 34.sp)
            Spacer(Modifier.height(20.dp))

            // Поисковая строка
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск товара на маркетплейсах...", color = AppColors.TextHint, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = AppColors.TextHint, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (searchQuery.isNotEmpty()) { { IconButton(onClick = { searchQuery = "" }) { Text("✕", color = AppColors.TextHint) } } } else null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = AppColors.Surface, unfocusedContainerColor = AppColors.Surface,
                    focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary
                )
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (searchQuery.isNotBlank()) onSearch(searchQuery) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (searchQuery.isNotBlank()) AppColors.TextPrimary else AppColors.Divider
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Сравнить цены", fontSize = 16.sp, color = if (searchQuery.isNotBlank()) Color.White else AppColors.TextHint)
            }

            Spacer(Modifier.height(24.dp))
            Text("Популярное", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Xiaomi Redmi Note 13", "iPhone 15 Pro", "Dyson Styler")) { item ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(AppColors.Surface)
                            .border(1.dp, AppColors.Divider, RoundedCornerShape(20.dp))
                            .clickable { onSearch(item) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(item, fontSize = 13.sp, color = AppColors.TextPrimary) }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Инфо-карточка
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Surface)
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(AppColors.PrimaryLight),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Search, null, tint = AppColors.Primary) }
                    Spacer(Modifier.height(12.dp))
                    Text("Интеллектуальный поиск цен", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Введите наименование смартфона, техники или обуви. Мы сопоставим цены на площадках Wildberries, Ozon и Яндекс Маркет, подсветив самый дешевый вариант с учетом действующих скидок.",
                        fontSize = 13.sp, color = AppColors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// ─── Результаты ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(query: String, onBack: () -> Unit, onEditProfile: () -> Unit = {}) {
    var isLoading by remember { mutableStateOf(true) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var aiAnalysis by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val api = remember { ApifyApi() }
    val scope = rememberCoroutineScope()

    if (showProfile) {
        ProfileBottomSheet(
            onDismiss     = { showProfile = false },
            onEditProfile = onEditProfile
        )
    }

    LaunchedEffect(query) {
        scope.launch {
            isLoading = true; errorMessage = null
            try {
                val wbDeferred = async { api.searchWildberries(query) }
                val all = mutableListOf<Product>().apply {
                    addAll(wbDeferred.await())
                    addAll(api.getOzonDemo(query))
                    addAll(api.getYandexDemo(query))
                }
                if (all.isEmpty()) errorMessage = "Ничего не найдено" else products = all
            } catch (e: Exception) {
                errorMessage = "Ошибка: ${e.message}"
            } finally { isLoading = false }
        }
    }

    val cheapest = products.minByOrNull { it.price }
    val fastest = products.minByOrNull { it.deliveryDays }
    val highestRated = products.maxByOrNull { it.rating }
    val minPrice = products.minOfOrNull { it.price } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(AppColors.Primary), Alignment.Center) {
                            Text("🛒", fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Сравни-ка", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = AppColors.TextPrimary)
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppColors.PrimaryLight)
                            .clickable { showProfile = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null, tint = AppColors.Primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Surface)
            )
        },
        containerColor = AppColors.Background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppColors.Primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Ищем лучшие цены...", color = AppColors.TextSecondary, fontSize = 14.sp)
                }
                errorMessage != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(errorMessage!!, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = AppColors.TextPrimary)) { Text("Назад") }
                }
                products.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ── Заголовок + лайк
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text("Результаты сравнения", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.FavoriteBorder, null, tint = AppColors.TextSecondary, modifier = Modifier.size(24.dp))
                            }
                        }

                        // ── Лучшая цена
                        if (cheapest != null) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(AppColors.PrimaryLight)
                                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cheapest.productUrl))) }
                                        .padding(20.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Text("ЛУЧШАЯ ЦЕНА", fontSize = 11.sp, color = AppColors.Primary, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                String.format(Locale.getDefault(), "%,d", cheapest.price) + " ₽",
                                                fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text("Выгодное предложение на маркетплейсе ${cheapest.marketplace.take(2)}", fontSize = 13.sp, color = AppColors.TextSecondary)
                                        }
                                        if (cheapest.discountPercent > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(AppColors.Primary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("▼", fontSize = 10.sp, color = Color.White)
                                                    Text("-${cheapest.discountPercent}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── Метрики (3 карточки)
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LightMetricCard("Минимум ₽", String.format(Locale.getDefault(), "%,d", minPrice) + " ₽", cheapest?.marketplace ?: "", cheapest?.accentColor ?: AppColors.Primary, Modifier.weight(1f))
                                LightMetricCard("Скорость ⚡", "${fastest?.deliveryDays ?: 0} дн.", fastest?.marketplace ?: "", fastest?.accentColor ?: AppColors.Primary, Modifier.weight(1f))
                                LightMetricCard("Оценка ⭐", "${highestRated?.rating}★", highestRated?.marketplace ?: "", highestRated?.accentColor ?: AppColors.Primary, Modifier.weight(1f))
                            }
                        }

                        // ── Шкала цен
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AppColors.Surface).padding(16.dp)
                            ) {
                                Column {
                                    Text("Сравнительная шкала цен", fontSize = 14.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(16.dp))
                                    val maxP = products.maxOfOrNull { it.price }?.toFloat() ?: 1f
                                    val minP = products.minOfOrNull { it.price }?.toFloat() ?: 0f
                                    val range = maxP - minP
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        products.forEach { p ->
                                            val frac = if (range > 0) (p.price - minP) / range else 0f
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(if (p.price == minPrice) p.accentColor else Color.White)
                                                        .border(2.dp, p.accentColor, CircleShape),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        products.forEach { p ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                                Text(p.marketplace.take(2), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.accentColor)
                                                Text(String.format(Locale.getDefault(), "%,d", p.price) + " ₽", fontSize = 11.sp, color = AppColors.TextSecondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── AI-аналитика
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AppColors.Surface).padding(16.dp)
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("ℹ️", fontSize = 16.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Умная AI Аналитика", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Primary, modifier = Modifier.weight(1f))
                                        Button(
                                            onClick = {
                                                if (!isAiLoading && aiAnalysis == null) {
                                                    isAiLoading = true
                                                    scope.launch {
                                                        delay(1500)
                                                        val wb = products.find { it.marketplace == "Wildberries" }
                                                        val ozon = products.find { it.marketplace == "Ozon" }
                                                        val yandex = products.find { it.marketplace == "Яндекс" }
                                                        aiAnalysis = buildString {
                                                            appendLine("📊 Аналитический отчет сравнения цен:")
                                                            appendLine()
                                                            if (cheapest != null) {
                                                                appendLine("🔥 Лучшая цена: Маркетплейс ${cheapest.marketplace} лидирует! Стоимость товара составляет всего ${String.format(Locale.getDefault(), "%,d", cheapest.price)} руб., что позволяет сэкономить существенную сумму по сравнению с конкурентами.${if (cheapest.discountPercent > 0) " Скидка составляет ${cheapest.discountPercent}% от первоначальной цены." else ""}")
                                                                appendLine()
                                                            }
                                                            if (fastest != null) {
                                                                appendLine("⚡ Скорость доставки: Самым оперативным является ${fastest.marketplace} с доставкой за ${fastest.deliveryDays} день. Это идеальный выбор, если покупка нужна вам срочно.")
                                                                appendLine()
                                                            }
                                                            if (highestRated != null) {
                                                                appendLine("⭐ Оценка покупателей: Наивысший рейтинг имеет предложение на ${highestRated.marketplace} (${highestRated.rating}★ на основе ${highestRated.reviewsCount} отзывов).")
                                                                appendLine()
                                                            }
                                                            appendLine("🎯 Рекомендация: Сбалансированным выбором по соотношению цена/скорость/надёжность выглядит покупка на ${cheapest?.marketplace ?: "WB"}. Однако, если критически важна ультра-быстрая доставка, рассмотрите предложение на ${fastest?.marketplace ?: "Яндекс"}.")
                                                        }
                                                        isAiLoading = false
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            if (isAiLoading) {
                                                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                            } else {
                                                Text("★ Спросить AI", fontSize = 13.sp, color = Color.White)
                                            }
                                        }
                                    }
                                    if (aiAnalysis == null && !isAiLoading) {
                                        Spacer(Modifier.height(6.dp))
                                        Text("Коснитесь 'Спросить AI' для получения умного вердикта: риски наценок, динамика изменения стоимости товара, удобство пошлин и логистики.", fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 19.sp)
                                    }
                                    if (aiAnalysis != null) {
                                        Spacer(Modifier.height(12.dp))
                                        Text(aiAnalysis!!, fontSize = 13.sp, color = AppColors.TextPrimary, lineHeight = 20.sp)
                                    }
                                }
                            }
                        }

                        // ── Все предложения
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text("Все предложения", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                Box(Modifier.size(32.dp).clip(CircleShape).border(1.dp, AppColors.Divider, CircleShape))
                            }
                        }

                        items(products) { product ->
                            LightProductCard(product = product) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(product.productUrl)))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Переиспользуемые компоненты ─────────────────────────────────────────────

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface)
            .padding(20.dp)
    ) {
        Column(content = content)
    }
}

@Composable
fun LightMetricCard(title: String, value: String, subtitle: String, accentColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Surface)
            .padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 11.sp, color = AppColors.TextHint, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(subtitle, fontSize = 11.sp, color = accentColor, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun LightProductCard(product: Product, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(16.dp)) {
            // Маркетплейс + скидка
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(product.accentColor))
                Spacer(Modifier.width(6.dp))
                Text(product.marketplace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = product.accentColor, modifier = Modifier.weight(1f))
                if (product.discountPercent > 0) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.DiscountBg).padding(horizontal = 8.dp, vertical = 3.dp)
                    ) { Text("-${product.discountPercent}%", fontSize = 12.sp, color = AppColors.Discount, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Картинка
                Box(
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(AppColors.Background),
                    contentAlignment = Alignment.Center
                ) {
                    if (product.imageUrl.isNotEmpty()) {
                        AsyncImage(
                            model = product.imageUrl, contentDescription = product.name,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop,
                            error = painterResource(android.R.drawable.ic_menu_gallery)
                        )
                    } else {
                        Text("🛒", fontSize = 28.sp)
                    }
                }
                // Инфо
                Column(Modifier.weight(1f)) {
                    Text(product.name, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★", fontSize = 13.sp, color = Color(0xFFFFB300))
                        Spacer(Modifier.width(3.dp))
                        Text("${product.rating}", fontSize = 13.sp, color = AppColors.TextSecondary)
                        if (product.reviewsCount > 0) Text(" (${product.reviewsCount} отзывов)", fontSize = 12.sp, color = AppColors.TextHint)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // Цена
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (product.originalPrice > product.price) {
                        Text(
                            text = String.format(Locale.getDefault(), "%,d", product.originalPrice) + " ₽",
                            fontSize = 12.sp, color = AppColors.TextHint, textDecoration = TextDecoration.LineThrough
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("С учетом выгоды", fontSize = 11.sp, color = AppColors.TextHint, modifier = Modifier.align(Alignment.Bottom).padding(bottom = 2.dp))
                    }
                    Text(
                        text = String.format(Locale.getDefault(), "%,d", product.price) + " ₽",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.GreenPrice
                    )
                }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.Background).padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("≥ ${product.deliveryDays} дня", fontSize = 12.sp, color = AppColors.TextSecondary) }
            }

            Spacer(Modifier.height(10.dp))

            // Кнопка перехода
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.TextPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Перейти к покупке", fontSize = 14.sp, color = Color.White)
            }
        }
    }
}