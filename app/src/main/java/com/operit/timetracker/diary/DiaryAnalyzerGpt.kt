package com.operit.timetracker.diary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 真实日记分析器，调用 OpenAI gpt-4o-mini。
 * 网络失败时返回降级结果，不崩溃。
 */
class DiaryAnalyzerGpt(private val apiKey: String) : DiaryAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val categories = DiaryCategory.entries.joinToString("、") { it.displayName }

    override suspend fun analyze(text: String, date: LocalDate): DiaryAnalysisResult {
        return withContext(Dispatchers.IO) {
            try {
                callApi(text, date)
            } catch (e: Exception) {
                DiaryAnalysisResult(
                    date = date.toString(),
                    summary = "分析失败：${e.message ?: "未知错误"}",
                    tags = listOf("错误"),
                    category = DiaryCategory.LIFE
                )
            }
        }
    }

    private fun callApi(text: String, date: LocalDate): DiaryAnalysisResult {
        val systemPrompt = """你是一个日记分析助手。根据用户一天的时间记录，生成简短的日报摘要。

要求：
1. summary：1-2 句话，概括这一天的主要活动和状态
2. tags：3-5 个关键词标签
3. category：从以下选项中选一个最匹配的主分类：$categories

只返回 JSON，格式如下，不要有其他内容：
{"summary": "...", "tags": ["...", "..."], "category": "..."}"""

        val messagesArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", "$date 时间记录：\n$text"))
        }

        val requestBody = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("messages", messagesArray)
            .put("temperature", 0.7)
            .put("max_tokens", 200)
            .toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("空响应")

        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(body).optJSONObject("error")?.optString("message") ?: body
            } catch (_: Exception) { body }
            throw Exception("API ${response.code}: $errorMsg")
        }

        val json = JSONObject(body)
        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()

        val jsonStr = extractJson(content)
        val parsed = JSONObject(jsonStr)

        val summary = parsed.optString("summary", "无摘要")
        val tags = mutableListOf<String>()
        parsed.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) tags.add(arr.getString(i))
        }
        val category = parsed.optString("category", "生活").let { catName ->
            DiaryCategory.entries.find { it.displayName == catName } ?: DiaryCategory.LIFE
        }

        return DiaryAnalysisResult(
            date = date.toString(),
            summary = summary,
            tags = tags,
            category = category
        )
    }

    private fun extractJson(text: String): String {
        try { JSONObject(text); return text } catch (_: Exception) {}
        val fencePattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        fencePattern.find(text)?.let { return it.groupValues[1] }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        throw Exception("无法从响应中提取 JSON: $text")
    }
}
