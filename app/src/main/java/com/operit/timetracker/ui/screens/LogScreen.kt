package com.operit.timetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.operit.timetracker.service.AppLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(navController: NavController) {
    var logContent by remember { mutableStateOf("加载中...") }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(refreshTrigger) {
        logContent = AppLogger.getRecentLogs(200)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("监控日志") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    TextButton(onClick = {
                        AppLogger.clearLogs()
                        logContent = "日志已清空"
                    }) {
                        Text("清空")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = logContent,
                    modifier = Modifier.padding(8.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}