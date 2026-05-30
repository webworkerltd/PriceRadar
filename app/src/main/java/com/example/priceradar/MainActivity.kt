package com.example.priceradar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PriceRadarApp()
        }
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
    val color: Color,
    val productUrl: String,
    val imageUrl: String
)

class ApifyApi {
    private val apiToken = "apify_api_HVzFCOusuJ2K3NfEhqWe17dViGyV9y1rxTSe"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ==================== WILDBERRIES (реальный парсинг) ====================
    suspend fun searchWildberries(query: String): List<Product> = withContext(Dispatchers.IO) {
        try {
            val actorId = "2SZSjETPO5i2W8Ytc"
            val runBody = """{
                "keyword": "$query",
                "max_items": 10
            }"""
            Log.d("ApifyWB", "Запуск WB с keyword: $query, body: $runBody")

            val runRequest = Request.Builder()
                .url("https://api.apify.com/v2/acts/$actorId/runs?token=$apiToken")
                .post(RequestBody.create(JSON_MEDIA, runBody))
                .build()
            val runResponse = client.newCall(runRequest).execute()
            val runJson = JSONObject(runResponse.body?.string() ?: "{}")
            if (!runJson.has("data")) {
                Log.e("ApifyWB", "Ошибка запуска: ${runJson.optString("error", "неизвестно")}")
                return@withContext emptyList()
            }
            val runId = runJson.getJSONObject("data").getString("id")
            val datasetId = runJson.getJSONObject("data").getString("defaultDatasetId")
            Log.d("ApifyWB", "Актор запущен, runId: $runId, datasetId: $datasetId")

            // Увеличиваем таймаут: 60 попыток * 3000 мс = 180 секунд
            var status = "RUNNING"
            var attempts = 0
            val maxAttempts = 60
            while (status == "RUNNING" && attempts < maxAttempts) {
                delay(3000)
                val statusRequest = Request.Builder()
                    .url("https://api.apify.com/v2/acts/$actorId/runs/$runId?token=$apiToken")
                    .build()
                val statusResponse = client.newCall(statusRequest).execute()
                val statusJson = JSONObject(statusResponse.body?.string() ?: "{}")
                status = statusJson.getJSONObject("data").getString("status")
                attempts++
                Log.d("ApifyWB", "Статус: $status (${attempts}/$maxAttempts)")
                if (status != "RUNNING") break
            }
            if (status != "SUCCEEDED") {
                Log.e("ApifyWB", "Ошибка статуса: $status после $attempts попыток")
                return@withContext emptyList()
            }

            val itemsRequest = Request.Builder()
                .url("https://api.apify.com/v2/datasets/$datasetId/items?token=$apiToken&clean=true")
                .build()
            val itemsResponse = client.newCall(itemsRequest).execute()
            val itemsString = itemsResponse.body?.string() ?: "[]"
            Log.d("ApifyWB", "Получены данные: $itemsString")

            val itemsArray = JSONObject("{\"items\":$itemsString}").getJSONArray("items")
            Log.d("ApifyWB", "Количество товаров: ${itemsArray.length()}")
            val products = mutableListOf<Product>()

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                val name = item.optString("name", query)
                val productUrl = item.optString("product_url", "")
                val imageUrl = item.optString("image_url", "")
                val currentPriceStr = item.optString("current_price", "0").replace("₽", "").replace(" ", "").trim()
                val currentPrice = currentPriceStr.toIntOrNull() ?: 0
                val oldPriceStr = item.optString("old_price", "0").replace("₽", "").replace(" ", "").trim()
                val oldPrice = oldPriceStr.toIntOrNull() ?: 0
                val discountStr = item.optString("discount", "0%").replace("%", "").replace("−", "-").trim()
                val discount = discountStr.toIntOrNull() ?: 0
                val rating = item.optDouble("rating", 0.0).toFloat()
                val reviews = item.optInt("feedbacks", 0)

                products.add(Product(
                    marketplace = "Wildberries",
                    name = name,
                    price = currentPrice,
                    originalPrice = oldPrice,
                    discountPercent = discount,
                    rating = rating,
                    reviewsCount = reviews,
                    deliveryDays = (2..4).random(),
                    color = Color(0xFF9D5BFF),
                    productUrl = productUrl,
                    imageUrl = imageUrl
                ))
            }
            return@withContext products
        } catch (e: Exception) {
            Log.e("ApifyWB", "Ошибка: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    // ==================== OZON (демо) ====================
    fun getOzonDemo(query: String): List<Product> = listOf(
        Product(
            marketplace = "Ozon",
            name = query,
            price = 91209,
            originalPrice = 115551,
            discountPercent = 21,
            rating = 4.6f,
            reviewsCount = 355,
            deliveryDays = 2,
            color = Color(0xFF005BFF),
            productUrl = "https://www.ozon.ru/product/iphone-15-pro-128gb-153079148/",
            imageUrl = "https://ir.ozone.ru/s3/multimedia-1-8/c1000/7303632675.jpg"
        )
    )

    // ==================== ЯНДЕКС МАРКЕТ (демо) ====================
    fun getYandexDemo(query: String): List<Product> = listOf(
        Product(
            marketplace = "Яндекс Маркет",
            name = query,
            price = 94169,
            originalPrice = 0,
            discountPercent = 0,
            rating = 4.5f,
            reviewsCount = 765,
            deliveryDays = 3,
            color = Color(0xFFFFD600),
            productUrl = "https://market.yandex.ru/search?text=${query.replace(" ", "+")}",
            imageUrl = ""
        )
    )
}

@Composable
fun PriceRadarApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    var isOnboardingComplete by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            val preferences = context.dataStore.data.first()
            isOnboardingComplete = preferences[booleanPreferencesKey("onboarding_complete")] ?: false
        }
    }

    if (isOnboardingComplete == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF9D5BFF))
        }
    } else {
        NavHost(
            navController = navController,
            startDestination = if (isOnboardingComplete == true) "search" else "onboarding"
        ) {
            composable("onboarding") { OnboardingScreen(onComplete = { navController.navigate("profile") }) }
            composable("profile") { ProfileScreen(onComplete = { navController.navigate("search") }) }
            composable("search") { SearchScreen(onSearch = { query -> navController.navigate("results/$query") }) }
            composable(
                "results/{query}",
                arguments = listOf(navArgument("query") { type = NavType.StringType })
            ) { backStackEntry ->
                val query = backStackEntry.arguments?.getString("query") ?: ""
                ResultsScreen(query = query, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A1A)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🛍️", fontSize = 80.sp)
        Spacer(Modifier.height(24.dp))
        Text("Добро пожаловать в\nСравни-ка", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Ваш персональный аналитик цен на маркетплейсах. Настройте профиль за секунду!", fontSize = 16.sp, color = Color(0xFFB0B0B0), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(48.dp))
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D5BFF)), shape = RoundedCornerShape(50)) {
            Text("Настроить профиль", fontSize = 18.sp, color = Color.White)
        }
    }
}

@Composable
fun ProfileScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedGender by remember { mutableStateOf("Не указывать") }
    var age by remember { mutableStateOf(25f) }
    var showPhoneInput by remember { mutableStateOf(false) }
    var phoneNumber by remember { mutableStateOf("") }
    val genders = listOf("Мужской", "Женский", "Не указывать")

    LaunchedEffect(Unit) {
        scope.launch {
            val preferences = context.dataStore.data.first()
            selectedGender = preferences[stringPreferencesKey("gender")] ?: "Не указывать"
            age = preferences[floatPreferencesKey("age")] ?: 25f
            phoneNumber = preferences[stringPreferencesKey("phone")] ?: ""
            if (phoneNumber.isNotEmpty()) showPhoneInput = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A1A)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Укажите ваш пол", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(bottom = 24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            genders.forEach { gender ->
                FilterChip(
                    selected = selectedGender == gender,
                    onClick = { selectedGender = gender },
                    label = { Text(gender, color = Color.White) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF9D5BFF), disabledContainerColor = Color(0xFF2A2A3E)),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(48.dp))
        Text("Сколько вам лет?", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text("${age.toInt()} лет", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9D5BFF))
        Slider(value = age, onValueChange = { age = it }, valueRange = 10f..95f, steps = 85, modifier = Modifier.padding(horizontal = 16.dp), colors = SliderDefaults.colors(thumbColor = Color(0xFF9D5BFF), activeTrackColor = Color(0xFF9D5BFF)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("10 лет", fontSize = 14.sp, color = Color(0xFFB0B0B0))
            Text("50 лет", fontSize = 14.sp, color = Color(0xFFB0B0B0))
            Text("95 лет", fontSize = 14.sp, color = Color(0xFFB0B0B0))
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Телефон (необязательно)", fontSize = 16.sp, color = Color.White)
            TextButton(onClick = { showPhoneInput = !showPhoneInput }) { Text(if (showPhoneInput) "Скрыть" else "Я хочу привязать телефон", color = Color(0xFF9D5BFF)) }
        }
        if (showPhoneInput) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it }, label = { Text("+7 XXX XXX XX XX", color = Color.Gray) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF9D5BFF), unfocusedBorderColor = Color.Gray, focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = {
            scope.launch {
                context.dataStore.edit { settings ->
                    settings[booleanPreferencesKey("onboarding_complete")] = true
                    settings[stringPreferencesKey("gender")] = selectedGender
                    settings[floatPreferencesKey("age")] = age
                    if (showPhoneInput && phoneNumber.isNotBlank()) settings[stringPreferencesKey("phone")] = phoneNumber else settings.remove(stringPreferencesKey("phone"))
                }
                onComplete()
            }
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D5BFF)), shape = RoundedCornerShape(50)) {
            Text("Сохранить профиль", fontSize = 18.sp, color = Color.White)
        }
        Button(onClick = {
            scope.launch {
                context.dataStore.edit { settings ->
                    settings.remove(stringPreferencesKey("gender"))
                    settings.remove(floatPreferencesKey("age"))
                    settings.remove(stringPreferencesKey("phone"))
                }
                selectedGender = "Не указывать"
                age = 25f
                phoneNumber = ""
                showPhoneInput = false
            }
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3E)), shape = RoundedCornerShape(50)) {
            Text("Сбросить данные", fontSize = 18.sp, color = Color.White)
        }
    }
}

@Composable
fun SearchScreen(onSearch: (String) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A1A)).padding(24.dp)) {
        Text("Сравнить цены\nв один клик", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 36.sp)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Поиск товара на маркетплейсах...", color = Color.Gray) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF9D5BFF)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF9D5BFF), unfocusedBorderColor = Color.Gray, focusedTextColor = Color.White, unfocusedTextColor = Color.White), singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { if (searchQuery.isNotBlank()) onSearch(searchQuery) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D5BFF)), shape = RoundedCornerShape(50)) { Text("Сравнить цены", fontSize = 18.sp, color = Color.White) }
        Spacer(Modifier.height(24.dp))
        Text("Популярное", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("iPhone 15 Pro", "Xiaomi Redmi Note 13", "Dyson Styler").forEach { item ->
                FilterChip(selected = false, onClick = { onSearch(item) }, label = { Text(item, color = Color.White) }, colors = FilterChipDefaults.filterChipColors(containerColor = Color(0xFF2A2A3E)))
            }
        }
        Spacer(Modifier.height(32.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("🔍 Интеллектуальный поиск цен", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Введите наименование смартфона, техники или обуви. Мы сопоставим цены на площадках Wildberries, Ozon и Яндекс Маркет", fontSize = 14.sp, color = Color(0xFFB0B0B0))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(query: String, onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val api = remember { ApifyApi() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val wbDeferred = async { api.searchWildberries(query) }
                val ozonProducts = api.getOzonDemo(query)
                val yandexProducts = api.getYandexDemo(query)

                val wbProducts = wbDeferred.await()
                val allProducts = mutableListOf<Product>()
                allProducts.addAll(wbProducts)
                allProducts.addAll(ozonProducts)
                allProducts.addAll(yandexProducts)

                if (allProducts.isEmpty()) errorMessage = "Ничего не найдено"
                else products = allProducts
            } catch (e: Exception) {
                errorMessage = "Ошибка: ${e.message}"
                Log.e("App", "Ошибка поиска", e)
            } finally {
                isLoading = false
            }
        }
    }

    val cheapest = products.minByOrNull { it.price }
    val fastest = products.minByOrNull { it.deliveryDays }
    val highestRated = products.maxByOrNull { it.rating }
    val minPrice = products.minOfOrNull { it.price } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Результаты сравнения", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A1A)).padding(paddingValues)) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF9D5BFF))
                errorMessage != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 48.sp)
                    Text(errorMessage!!, color = Color.Red, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Назад") }
                }
                products.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (cheapest != null) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cheapest.productUrl)))
                                    },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF9D5BFF).copy(alpha = 0.15f)),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🏆 ЛУЧШАЯ ЦЕНА 🏆", fontSize = 14.sp, color = Color(0xFF9D5BFF))
                                        Text(String.format(Locale.getDefault(), "%,d", cheapest.price) + " ₽", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                        Text("Выгодное предложение на ${cheapest.marketplace}", fontSize = 14.sp, color = Color.White)
                                        if (cheapest.discountPercent > 0) Text("-${cheapest.discountPercent}%", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                        Spacer(Modifier.height(12.dp))
                                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cheapest.productUrl))) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D5BFF)), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                                            Text("Перейти к покупке →", fontSize = 16.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricCard("Минимум ₽", String.format(Locale.getDefault(), "%,d", minPrice) + " ₽", cheapest?.marketplace ?: "")
                                MetricCard("Скорость", "${fastest?.deliveryDays ?: 0} дн.", fastest?.marketplace ?: "")
                                MetricCard("Оценка", "${highestRated?.rating}★", highestRated?.marketplace ?: "")
                            }
                        }
                        item { Text("Все предложения", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 8.dp)) }
                        items(products) { product ->
                            ProductCard(product = product, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(product.productUrl))) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF9D5BFF))
        }
    }
}

@Composable
fun ProductCard(product: Product, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = product.imageUrl,
                contentDescription = product.name,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A0A1A)),
                contentScale = ContentScale.Crop,
                error = painterResource(android.R.drawable.ic_menu_gallery)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(product.color))
                    Spacer(Modifier.width(6.dp))
                    Text(product.marketplace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = product.color)
                    if (product.discountPercent > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("-${product.discountPercent}%", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(product.name, fontSize = 14.sp, color = Color.White, maxLines = 2)
                Text("★ ${product.rating} (${product.reviewsCount} отзывов)", fontSize = 12.sp, color = Color(0xFFB0B0B0))
            }
            Column(horizontalAlignment = Alignment.End) {
                if (product.originalPrice > product.price) {
                    Text(text = String.format(Locale.getDefault(), "%,d", product.originalPrice) + " ₽", fontSize = 12.sp, color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                }
                Text(text = String.format(Locale.getDefault(), "%,d", product.price) + " ₽", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                Text("> ${product.deliveryDays} дня", fontSize = 12.sp, color = Color(0xFFB0B0B0))
            }
        }
    }
}