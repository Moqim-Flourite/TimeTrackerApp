package com.operit.timetracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.operit.timetracker.data.sync.SyncRepository
import com.operit.timetracker.data.sync.SyncWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val syncRepository = remember { SyncRepository(context) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState())
        ) {
            // ====== WiFi 同步 ======
            SyncSection(syncRepository = syncRepository, scope = scope)
        }
    }
}

@Composable
private fun SyncSection(
    syncRepository: SyncRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current

    // 状态
    var syncEnabled by remember { mutableStateOf(syncRepository.config.enabled) }
    var serverHost by remember { mutableStateOf(syncRepository.config.serverHost) }
    var serverPort by remember { mutableStateOf(syncRepository.config.serverPort.toString()) }
    var authToken by remember { mutableStateOf(syncRepository.config.authToken) }
    var showToken by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(syncRepository.getSyncStatusSummary()) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    Text(
        "WiFi 数据同步",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "每天自动将时间记录同步到电脑端，用于后续数据分析。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(16.dp))

    // 启用开关
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("启用同步", style = MaterialTheme.typography.bodyLarge)
            Text(
                "每天通过 WiFi 同步数据到电脑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = syncEnabled,
            onCheckedChange = { enabled ->
                syncEnabled = enabled
                syncRepository.config.enabled = enabled
                if (enabled && syncRepository.config.isPaired) {
                    SyncWorker.schedulePeriodicSync(context)
                } else {
                    SyncWorker.cancelPeriodicSync(context)
                }
                statusText = syncRepository.getSyncStatusSummary()
            }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 配对区域
    AnimatedVisibility(visible = syncEnabled) {
        Column {
            if (!syncRepository.config.isPaired) {
                // 未配对 - 显示配对表单
                Text(
                    "配对电脑端",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = serverHost,
                    onValueChange = { serverHost = it },
                    label = { Text("电脑 IP 地址") },
                    placeholder = { Text("192.168.x.x") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = { Text("端口") },
                    placeholder = { Text("8080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = authToken,
                    onValueChange = { authToken = it },
                    label = { Text("共享 Token") },
                    placeholder = { Text("电脑端启动时生成的 token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(if (showToken) "隐藏" else "显示")
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val port = serverPort.toIntOrNull() ?: 8080
                            syncRepository.pair(authToken.trim(), serverHost.trim(), port)
                            SyncWorker.schedulePeriodicSync(context)
                            statusText = syncRepository.getSyncStatusSummary()
                            syncMessage = "配对成功"
                        },
                        enabled = serverHost.isNotBlank() && authToken.isNotBlank()
                    ) {
                        Text("配对")
                    }

                    // mDNS 自动发现按钮
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                syncMessage = "正在搜索..."
                                val discovered = syncRepository.discoverServer()
                                if (discovered != null) {
                                    serverHost = discovered.first
                                    serverPort = discovered.second.toString()
                                    syncMessage = "找到: ${discovered.first}:${discovered.second}"
                                } else {
                                    syncMessage = "未找到电脑端，请手动输入 IP"
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("自动搜索")
                    }
                }
            } else {
                // 已配对 - 显示状态和手动同步
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "同步状态",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        val serverUrl = syncRepository.config.getServerUrl()
                        if (serverUrl.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "服务器: $serverUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    isSyncing = true
                                    syncMessage = null
                                    SyncWorker.triggerManualSync(context)
                                    scope.launch {
                                        // 等待一小段时间让 WorkManager 开始执行
                                        kotlinx.coroutines.delay(1000)
                                        isSyncing = false
                                        statusText = syncRepository.getSyncStatusSummary()
                                        syncMessage = "同步已触发，后台执行中"
                                    }
                                },
                                enabled = !isSyncing
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("同步中...")
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("立即同步")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    syncRepository.config.clear()
                                    syncRepository.clearSyncLog()
                                    SyncWorker.cancelPeriodicSync(context)
                                    // 重置本地状态
                                    scope.launch {
                                        // 触发重组
                                        syncEnabled = false
                                        serverHost = ""
                                        serverPort = "8080"
                                        authToken = ""
                                        statusText = "同步未启用"
                                        syncMessage = "已解除配对"
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("解除配对")
                            }
                        }
                    }
                }
            }

            // 消息提示
            syncMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
