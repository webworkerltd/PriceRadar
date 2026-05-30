package com.example.priceradar


import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
    val productUrl: String
)

@Composable
fun PriceRadarApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "search") {
        composable("search") {
            SearchScreen(onSearch = { query ->
                navController.navigate("results/$query")
            })
        }
        composable(
            "results/{query}",
            arguments = listOf(navArgument("query") { type = NavType.StringType })
        ) { backStackEntry ->
            val query = backStackEntry.arguments?.getString("query") ?: ""
            ResultsScreen(query = query, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
fun SearchScreen(onSearch: (String) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A1A))
            .padding(24.dp)
    ) {
        Text(
            text = "Сравнить цены\nв один клик",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            lineHeight = 36.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск товара на маркетплейсах...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF9D5BFF)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF9D5BFF),
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { if (searchQuery.isNotBlank()) onSearch(searchQuery) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D5BFF)),
            shape = RoundedCornerShape(50)
        ) {
            Text("Сравнить цены", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Популярное",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("iPhone 15 Pro", "Xiaomi Redmi Note 13", "Dyson Styler").forEach { item ->
                FilterChip(
                    selected = false,
                    onClick = { onSearch(item) },
                    label = { Text(item, color = Color.White) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF2A2A3E),
                        labelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔍 Интеллектуальный поиск цен", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Введите наименование смартфона, техники или обуви. Мы сопоставим цены на площадках Wildberries, Ozon и Яндекс Маркет",
                    fontSize = 14.sp,
                    color = Color(0xFFB0B0B0)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(query: String, onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    val context = LocalContext.current

    LaunchedEffect(query) {
        isLoading = true
        delay(1500)
        products = listOf(
            Product(
                marketplace = "Wildberries",
                name = query,
                price = 70899,
                originalPrice = 95809,
                discountPercent = 26,
                rating = 4.7f,
                reviewsCount = 449,
                deliveryDays = 2,
                color = Color(0xFF9D5BFF),
                productUrl = "https://www.wildberries.ru/catalog/153079148/detail.aspx"
            ),
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
                productUrl = "https://www.ozon.ru/product/apple-iphone-15-pro-128gb-chernyy-titan-153079148/"
            ),
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
                productUrl = "https://market.yandex.ru/product--apple-iphone-15-pro/1884414043"
            )
        )
        isLoading = false
    }

    val cheapest = products.minByOrNull { it.price }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Результаты сравнения", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A1A))
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFF9D5BFF))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (cheapest != null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cheapest.productUrl))
                                        context.startActivity(intent)
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF9D5BFF).copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("🏆 ЛУЧШАЯ ЦЕНА 🏆", fontSize = 14.sp, color = Color(0xFF9D5BFF))
                                    Text(
                                        text = String.format(Locale.getDefault(), "%,.0f", cheapest.price) + " ₽",
                                        fontSize = 42.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                    Text("Выгодное предложение на маркетплейсе ${cheapest.marketplace}", fontSize = 14.sp, color = Color.White)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cheapest.productUrl))
                                            context.startActivity(intent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D5BFF)),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Перейти к покупке →", fontSize = 16.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Все предложения",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(products) { product ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(product.productUrl))
                                    context.startActivity(intent)
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(product.color)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(product.marketplace, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = product.color)
                                        if (product.discountPercent > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("-${product.discountPercent}%", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(product.name, fontSize = 14.sp, color = Color.White, maxLines = 2)
                                    Text("★ ${product.rating} (${product.reviewsCount} отзывов)", fontSize = 12.sp, color = Color(0xFFB0B0B0))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (product.originalPrice > product.price) {
                                        Text(
                                            text = String.format(Locale.getDefault(), "%,.0f", product.originalPrice.toDouble()) + " ₽",
                                            fontSize = 12.sp,
                                            color = Color.Gray,
                                            textDecoration = TextDecoration.LineThrough
                                        )
                                    }
                                    Text(
                                        text = String.format(Locale.getDefault(), "%,.0f", product.price.toDouble()) + " ₽",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                    Text("> ${product.deliveryDays} дня", fontSize = 12.sp, color = Color(0xFFB0B0B0))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}