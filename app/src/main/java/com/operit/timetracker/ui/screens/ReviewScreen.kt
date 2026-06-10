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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.operit.timetracker.diary.DiaryAnalysisResult
import com.operit.timetracker.diary.DiaryAnalyzerStub
import com.operit.timetracker.diary.DiaryTextBuilder
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(navController: NavController) {
    val context = LocalContext.current
    val analyzer = remember { DiaryAnalyzerStub() }
    val textBuilder = remember { DiaryTextBuilder(context) }
    var results by remember { mutableStateOf<List<DiaryAnalysisResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

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
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(results) { result -> DiaryCard(result = result) }
            }
        }
    }
}

@Composable
fun DiaryCard(result: DiaryAnalysisResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(result.date, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SuggestionChip(onClick = {}, label = { Text(result.category.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold) })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(result.summary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
