package com.operit.timetracker.diary

import java.time.LocalDate

class DiaryAnalyzerStub : DiaryAnalyzer {

    private val mockResults = listOf(
        DiaryAnalysisResult(
            date = "2026-06-02",
            summary = "推进了 TimeTrackerApp 确认界面的方案设计，和团队对齐了数据模型改造方案。",
            tags = listOf("项目推进", "团队协作", "方案设计"),
            category = DiaryCategory.WORK
        ),
        DiaryAnalysisResult(
            date = "2026-06-01",
            summary = "读完了《置身事内》第三章，对土地财政的逻辑有了新的理解。",
            tags = listOf("阅读", "思考", "经济学"),
            category = DiaryCategory.READING
        ),
        DiaryAnalysisResult(
            date = "2026-05-31",
            summary = "下午跑了 5 公里，配速比上周快了 10 秒，膝盖没什么不适。",
            tags = listOf("运动", "跑步", "健康"),
            category = DiaryCategory.LIFE
        ),
        DiaryAnalysisResult(
            date = "2026-05-30",
            summary = "和老友吃了顿火锅，聊了很多近况，心情放松了不少。",
            tags = listOf("社交", "放松", "朋友"),
            category = DiaryCategory.SOCIAL
        ),
        DiaryAnalysisResult(
            date = "2026-05-29",
            summary = "通勤路上听了两期播客，一期聊 AI 一期聊城市规划，都有收获。",
            tags = listOf("通勤", "播客", "学习"),
            category = DiaryCategory.TRANSPORT
        ),
        DiaryAnalysisResult(
            date = "2026-05-28",
            summary = "晚上打了两把游戏，手感一般，但解压效果不错。",
            tags = listOf("游戏", "放松", "休闲"),
            category = DiaryCategory.ENTERTAINMENT
        )
    )

    override suspend fun analyze(text: String, date: LocalDate): DiaryAnalysisResult {
        val dateStr = date.toString()
        return mockResults.find { it.date == dateStr }
            ?: DiaryAnalysisResult(
                date = dateStr,
                summary = "日记内容摘要（stub 占位）",
                tags = listOf("占位"),
                category = DiaryCategory.LIFE
            )
    }
}
