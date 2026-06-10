package com.operit.timetracker.diary

import java.time.LocalDate

interface DiaryAnalyzer {
    suspend fun analyze(text: String, date: LocalDate): DiaryAnalysisResult
}
