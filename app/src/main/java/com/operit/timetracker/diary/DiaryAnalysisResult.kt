package com.operit.timetracker.diary

data class DiaryAnalysisResult(
    val date: String,               // "2026-06-02"
    val summary: String,            // 1-2 句话
    val tags: List<String>,         // 自由标签
    val category: DiaryCategory     // 13 选 1
)

enum class DiaryCategory(val displayName: String) {
    WORK("工作"),
    STUDY("学习"),
    LIFE("生活"),
    REST("休息"),
    ENTERTAINMENT("娱乐"),
    SOCIAL("社交"),
    READING("阅读"),
    GAMING("游戏"),
    SHOPPING("购物"),
    TRANSPORT("交通"),
    FINANCE("金融"),
    TOOLS("工具"),
    IDLE("空闲")
}
