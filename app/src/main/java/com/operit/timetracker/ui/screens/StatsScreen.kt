package com.operit.timetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.operit.timetracker.data.CategoryStat
import com.operit.timetracker.ui.viewmodel.TaskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: TaskViewModel, navController: NavController) {
    val dailyStats by viewModel.dailyStats.collectAsState()
    val weeklyStats by viewModel.weeklyStats.collectAsState()
    val totalStats by viewModel.totalStats.collectAsState()
    
    // 每次进入屏幕时重新加载数据
    LaunchedEffect(Unit) {
        viewModel.loadStats()
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
                .padding(16.dp)
        ) {
            // 今日统计
            StatsCard(
                title = "今日统计",
                stats = dailyStats,
                viewModel = viewModel
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 本周统计
            StatsCard(
                title = "本周统计",
                stats = weeklyStats,
                viewModel = viewModel
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 总计统计
            StatsCard(
                title = "总计统计",
                stats = totalStats,
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun StatsCard(title: String, stats: List<CategoryStat>, viewModel: TaskViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            if (stats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无数据",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val totalDuration = stats.sumOf { it.totalDuration }
                
                LazyColumn {
                    items(stats.take(5)) { stat ->
                        StatItem(
                            stat = stat,
                            totalDuration = totalDuration,
                            viewModel = viewModel
                        )
                    }
                }
                
                if (stats.size > 5) {
                    Text(
                        text = "...还有${stats.size - 5}个类别",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(stat: CategoryStat, totalDuration: Long, viewModel: TaskViewModel) {
    val percentage = if (totalDuration > 0) {
        (stat.totalDuration * 100) / totalDuration
    } else {
        0
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stat.category,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = viewModel.formatDurationText(stat.totalDuration),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        
        Text(
            text = "${percentage}%",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}