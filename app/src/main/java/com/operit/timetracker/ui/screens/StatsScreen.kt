package com.operit.timetracker.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.operit.timetracker.data.AppStat
import com.operit.timetracker.data.CategoryStat
import com.operit.timetracker.ui.viewmodel.TaskViewModel

// 饼图颜色池
private val pieColors = listOf(
    Color(0xFF4CAF50), // 绿
    Color(0xFF2196F3), // 蓝
    Color(0xFFFF9800), // 橙
    Color(0xFFE91E63), // 粉
    Color(0xFF9C27B0), // 紫
    Color(0xFF00BCD4), // 青
    Color(0xFFFF5722), // 深橙
    Color(0xFF795548), // 棕
    Color(0xFF607D8B), // 蓝灰
    Color(0xFFFFEB3B), // 黄
    Color(0xFF3F51B5), // 靛蓝
    Color(0xFF8BC34A), // 浅绿
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: TaskViewModel, navController: NavController) {
    val dailyStats by viewModel.dailyStats.collectAsState()
    val weeklyStats by viewModel.weeklyStats.collectAsState()
    val totalStats by viewModel.totalStats.collectAsState()

    val dailyAppStats by viewModel.dailyAppStats.collectAsState()
    val weeklyAppStats by viewModel.weeklyAppStats.collectAsState()
    val totalAppStats by viewModel.totalAppStats.collectAsState()

    // 每次进入屏幕时重新加载数据
    LaunchedEffect(Unit) {
        viewModel.loadStats()
    }

    // Tab 状态
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("今日", "本周", "总计")

    // 当前选中时间段的数据
    val currentCategoryStats = when (selectedTab) {
        0 -> dailyStats
        1 -> weeklyStats
        else -> totalStats
    }
    val currentAppStats = when (selectedTab) {
        0 -> dailyAppStats
        1 -> weeklyAppStats
        else -> totalAppStats
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计数据") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab 切换
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 分类饼图
                item {
                    CategoryPieChartSection(stats = currentCategoryStats, viewModel = viewModel)
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // App 使用排名
                item {
                    Text(
                        text = "📱 App 使用排行",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (currentAppStats.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无数据",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Top 1: 最大使用时长的 App 特别展示
                    item {
                        TopAppCard(
                            appStat = currentAppStats.first(),
                            viewModel = viewModel,
                            rank = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 其余 App 列表
                    items(currentAppStats.drop(1).take(19)) { stat ->
                        AppStatRow(
                            appStat = stat,
                            viewModel = viewModel,
                            rank = currentAppStats.indexOf(stat) + 1,
                            maxDuration = currentAppStats.first().totalDuration
                        )
                    }
                }

                // 底部留白
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

// ========== 分类饼图区域 ==========

@Composable
fun CategoryPieChartSection(stats: List<CategoryStat>, viewModel: TaskViewModel) {
    val totalDuration = stats.sumOf { it.totalDuration }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📊 分类使用分布",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (stats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无数据",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 饼图
                Box(
                    modifier = Modifier.size(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PieChart(
                        data = stats.map { it.category to it.totalDuration.toFloat() },
                        modifier = Modifier.fillMaxSize()
                    )
                    // 中心文字
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = viewModel.formatDurationText(totalDuration),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "总计",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 图例
                stats.take(8).forEachIndexed { index, stat ->
                    val percentage = if (totalDuration > 0) {
                        (stat.totalDuration * 100) / totalDuration
                    } else 0

                    LegendItem(
                        color = pieColors[index % pieColors.size],
                        label = stat.category,
                        duration = viewModel.formatDurationText(stat.totalDuration),
                        percentage = "${percentage}%"
                    )
                }

                if (stats.size > 8) {
                    Text(
                        text = "...还有${stats.size - 8}个类别",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ========== 饼图绘制 ==========

@Composable
fun PieChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.second.toDouble() }.toFloat()
    if (total <= 0f) return

    var animationPlayed by remember { mutableStateOf(false) }
    val animateSize by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "pie_animation"
    )

    LaunchedEffect(Unit) {
        animationPlayed = true
    }

    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val radius = canvasSize / 2
        val strokeWidth = canvasSize * 0.25f // 环形宽度
        val topLeft = Offset(
            (size.width - canvasSize) / 2 + strokeWidth / 2,
            (size.height - canvasSize) / 2 + strokeWidth / 2
        )
        val arcSize = Size(canvasSize - strokeWidth, canvasSize - strokeWidth)

        var startAngle = -90f // 从顶部开始

        data.forEachIndexed { index, (_, value) ->
            val sweepAngle = (value / total) * 360f * animateSize
            drawArc(
                color = pieColors[index % pieColors.size],
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweepAngle
        }
    }
}

// ========== 图例行 ==========

@Composable
fun LegendItem(color: Color, label: String, duration: String, percentage: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = duration,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = percentage,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ========== Top App 大卡片 ==========

@Composable
fun TopAppCard(appStat: AppStat, viewModel: TaskViewModel, rank: Int) {
    val appName = viewModel.getAppName(appStat.packageName)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🏆",
                    fontSize = 24.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = appStat.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = viewModel.formatDurationText(appStat.totalDuration),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "使用时间最长的 App",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

// ========== App 排名行 ==========

@Composable
fun AppStatRow(
    appStat: AppStat,
    viewModel: TaskViewModel,
    rank: Int,
    maxDuration: Long
) {
    val appName = viewModel.getAppName(appStat.packageName)
    val progress = if (maxDuration > 0) {
        appStat.totalDuration.toFloat() / maxDuration.toFloat()
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 排名
                Text(
                    text = "$rank",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (rank) {
                        2 -> Color(0xFFC0C0C0) // 银
                        3 -> Color(0xFFCD7F32) // 铜
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(24.dp)
                )

                // App 名称
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = appStat.packageName,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 时长
                Text(
                    text = viewModel.formatDurationText(appStat.totalDuration),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small),
                color = pieColors[(rank - 1) % pieColors.size],
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
