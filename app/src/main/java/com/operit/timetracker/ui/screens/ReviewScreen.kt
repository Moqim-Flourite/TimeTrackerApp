package com.operit.timetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.operit.timetracker.diary.DiaryAnalysisResult
import com.operit.timetracker.diary.DiaryAnalyzerGpt
import com.operit.timetracker.diary.DiaryAnalyzerStub
import com.operit.timetracker.diary.ApiKeyManager
import com.operit.timetracker.diary.DiaryTextBuilder
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(navController: NavController) {
    val context = LocalContext.current
    val keyManager = remember { ApiKeyManager(context) }
    val hasKey = keyManager.hasApiKey()
    val analyzer = remember(hasKey) {
        if (hasKey) DiaryAnalyzerGpt(keyManager.getApiKey())
        else DiaryAnalyzerStub()
    }
    val textBuilder = remember { DiaryTextBuilder(context) }
    var results by remember { mutableStateOf<List<DiaryAnalysisResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        val today = LocalDate.now()
        val daysToLoad = 7
        val loaded = mutableListOf<DiaryAnalysisResult>()
        for (i in 0 until daysToLoad) {
            val date = today.minusDays(i.toLong())
            val text = textBuilder.buildTextForDate(date)
            val result = analyzer.analyze(text, date)
            loaded.add(result)
        }
        results = loaded
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日记回顾") },
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
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("暂无日记分析", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(results) { result ->
                    TimelineItem(
                        result = result,
                        isExpanded = expandedStates[result.date] == true,
                        onToggleExpand = {
                            expandedStates[result.date] = expandedStates[result.date] != true
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TimelineItem(
    result: DiaryAnalysisResult,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val nodeColor = MaterialTheme.colorScheme.primary

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(nodeColor)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(lineColor)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            DiaryCard(
                result = result,
                isExpanded = isExpanded,
                onToggleExpand = onToggleExpand
            )
        }
    }
}

@Composable
fun DiaryCard(
    result: DiaryAnalysisResult,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    var needsExpand by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(result.date, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SuggestionChip(onClick = {}, label = { Text(result.category.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold) })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                result.summary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                onTextLayout = { textLayoutResult ->
                    needsExpand = textLayoutResult.lineCount > 4
                }
            )
            if (needsExpand) {
                TextButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp)
                    )
                    Text(text = if (isExpanded) "收起" else "展开", fontSize = 13.sp)
                }
            }
            if (result.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.tags.forEach { tag ->
                        SuggestionChip(onClick = {}, label = { Text(tag, fontSize = 12.sp) })
                    }
                }
            }
        }
    }
}
