package com.operit.timetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.operit.timetracker.data.CategoryStat
import com.operit.timetracker.ui.MainViewModel
import com.operit.timetracker.ui.viewmodel.TaskViewModel
import com.operit.timetracker.update.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: TaskViewModel, mainViewModel: MainViewModel, navController: NavController) {
    val currentTask by viewModel.currentTask.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val currentFormattedTime by viewModel.currentFormattedTime.collectAsState()
    val message by viewModel.message.collectAsState()
    
    // 从MainViewModel获取监控状态
    val hasPermission by mainViewModel.hasUsageStatsPermission.collectAsState()
    val isMonitoring by mainViewModel.isMonitoring.collectAsState()
    val monitorState by mainViewModel.monitorState.collectAsState()
    
    var taskInput by remember { mutableStateOf("") }
    
    // 更新检查相关状态
    val context = LocalContext.current
    val updateChecker = remember { UpdateChecker(context) }
    val coroutineScope = rememberCoroutineScope()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var updateError by remember { mutableStateOf<String?>(null) }
    
    // 显示消息
    LaunchedEffect(message) {
        message?.let {
            // 自动清除消息
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("时间记录助手") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { navController.navigate("stats") }) {
                        Icon(Icons.Filled.Star, contentDescription = "统计")
                    }
                    IconButton(onClick = { navController.navigate("history") }) {
                        Icon(Icons.Filled.Menu, contentDescription = "历史记录")
                    }
                    IconButton(
                        onClick = {
                            if (!isCheckingUpdate) {
                                isCheckingUpdate = true
                                updateError = null
                                coroutineScope.launch {
                                    val result = updateChecker.checkForUpdate()
                                    isCheckingUpdate = false
                                    result.onSuccess { info ->
                                        if (info != null) {
                                            updateInfo = info
                                            showUpdateDialog = true
                                        } else {
                                            viewModel.setMessage("✅ 已是最新版本 v${updateChecker.getCurrentVersion()}")
                                        }
                                    }.onFailure { e ->
                                        updateError = e.message
                                        viewModel.setMessage("❌ 检查更新失败: ${e.message}")
                                    }
                                }
                            }
                        },
                        enabled = !isCheckingUpdate
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "检查更新")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // 只有手动任务运行时才显示停止按钮，监控服务运行时不显示
            if (isRunning && !isMonitoring) {
                FloatingActionButton(
                    onClick = { viewModel.stopCurrentTask() },
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "停止任务")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // 消息显示
            message?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // 当前任务显示
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isRunning && currentTask != null) {
                        Text(
                            text = "当前任务",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentTask!!.category,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = currentFormattedTime,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "开始时间: ${viewModel.formatTime(currentTask!!.startTime)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "没有运行中的任务",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "输入任务名称开始记录",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 自动监控状态控制卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "自动监控",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "自动检测前台App并记录使用时长",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 权限状态
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "App使用情况权限",
                            fontSize = 14.sp
                        )
                        
                        Button(
                            onClick = { mainViewModel.requestUsageStatsPermission() },
                            enabled = !hasPermission
                        ) {
                            Text(if (hasPermission) "已授权" else "授权")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 监控服务状态
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "监控服务",
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (isMonitoring) "运行中" else "已停止",
                                fontSize = 12.sp,
                                color = if (isMonitoring) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                        }
                        
                        Switch(
                            checked = isMonitoring,
                            onCheckedChange = { mainViewModel.toggleMonitoring() }
                        )
                    }
                    
                    // 显示当前监控的应用（如果有的话）
                    if (isRunning && currentTask != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "当前监控: ${currentTask!!.category}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 查看日志按钮
                    OutlinedButton(
                        onClick = { navController.navigate("log") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 查看监控日志")
                    }
                }
            }
            
            // 任务输入区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = taskInput,
                    onValueChange = { taskInput = it },
                    label = { Text("任务名称") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isMonitoring // 监控运行时禁用
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        if (taskInput.isNotBlank()) {
                            viewModel.startTask(taskInput.trim())
                            taskInput = ""
                        }
                    },
                    enabled = taskInput.isNotBlank() && !isMonitoring // 监控运行时禁用
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "开始任务")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开始")
                }
            }
            
            // 快捷任务按钮
            Text(
                text = if (isMonitoring) "快捷任务（监控中，手动任务已禁用）" else "快捷任务",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickTaskButton(
                    text = "工作",
                    onClick = { viewModel.startTask("工作") },
                    enabled = !isMonitoring
                )
                QuickTaskButton(
                    text = "学习",
                    onClick = { viewModel.startTask("学习") },
                    enabled = !isMonitoring
                )
                QuickTaskButton(
                    text = "休息",
                    onClick = { viewModel.startTask("休息") },
                    enabled = !isMonitoring
                )
                QuickTaskButton(
                    text = "运动",
                    onClick = { viewModel.startTask("运动") },
                    enabled = !isMonitoring
                )
            }
            
            // 今日统计
            Text(
                text = "今日统计",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            DailyStatsCard(viewModel)
        }
    }
    
    // ========== 更新对话框 ==========
    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
            title = {
                Text("🔄 发现新版本 ${info.version}")
            },
            text = {
                Column {
                    Text(
                        text = info.releaseName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "当前版本: v${updateChecker.getCurrentVersion()}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "发布时间: ${info.publishedAt.take(10)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (info.releaseBody.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "更新日志:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = info.releaseBody.take(500),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "下载中... $downloadProgress%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isDownloading) {
                            isDownloading = true
                            downloadProgress = 0
                            coroutineScope.launch {
                                val result = updateChecker.downloadApk(info) { progress ->
                                    downloadProgress = progress
                                }
                                result.onSuccess { apkFile ->
                                    isDownloading = false
                                    showUpdateDialog = false
                                    try {
                                        updateChecker.installApk(apkFile)
                                    } catch (e: Exception) {
                                        viewModel.setMessage("❌ 安装失败: ${e.message}")
                                    }
                                }.onFailure { e ->
                                    isDownloading = false
                                    viewModel.setMessage("❌ 下载失败: ${e.message}")
                                }
                            }
                        }
                    },
                    enabled = !isDownloading
                ) {
                    Text(if (isDownloading) "下载中..." else "下载并安装")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpdateDialog = false },
                    enabled = !isDownloading
                ) {
                    Text("稍后再说")
                }
            }
        )
    }
}

@Composable
fun QuickTaskButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(4.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Text(text)
    }
}

@Composable
fun DailyStatsCard(viewModel: TaskViewModel) {
    val dailyStats by viewModel.dailyStats.collectAsState()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        if (dailyStats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "今天还没有记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(16.dp)
            ) {
                items(dailyStats) { stat ->
                    DailyStatItem(stat = stat, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun DailyStatItem(stat: CategoryStat, viewModel: TaskViewModel) {
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
            color = MaterialTheme.colorScheme.primary
        )
    }
}