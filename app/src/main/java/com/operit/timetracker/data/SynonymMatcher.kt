package com.operit.timetracker.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 同义词/意图识别系统
 * 
 * 匹配优先级：
 * 1. 同义词精确匹配（最高）
 * 2. 同义词模糊匹配
 * 3. 已有类别精确匹配
 * 4. 已有类别模糊匹配（按时长排序）
 * 5. 创建新类别
 */
class SynonymMatcher(context: Context) {
    
    private val dataDir = File(context.filesDir, "timetracker")
    private val synonymsFile = File(dataDir, "synonyms.json")
    
    // 默认同义词表
    private val defaultSynonyms = mapOf(
        "工作" to listOf("上班", "干活", "办公", "打工", "搬砖", "加班"),
        "睡觉" to listOf("休息", "睡眠", "睡觉觉", "补觉", "午睡", "打盹"),
        "学习" to listOf("看书", "读书", "学习知识", "充电", "复习", "预习"),
        "运动" to listOf("健身", "锻炼", "跑步", "游泳", "打球", "散步"),
        "吃饭" to listOf("用餐", "午饭", "晚饭", "早餐", "进餐", "宵夜"),
        "娱乐" to listOf("玩", "放松", "休闲", "摸鱼"),
        "交通" to listOf("通勤", "坐车", "开车", "地铁", "公交", "打车"),
        "社交" to listOf("聊天", "刷手机", "刷视频", "刷抖音", "看视频", "玩手机"),
        "家务" to listOf("洗碗", "洗衣服", "收拾房间", "打扫", "整理", "做家务")
    )
    
    private val synonyms: Map<String, List<String>> by lazy {
        loadSynonyms()
    }
    
    private fun loadSynonyms(): Map<String, List<String>> {
        return try {
            if (synonymsFile.exists()) {
                val text = synonymsFile.readText()
                val obj = JSONObject(text)
                val map = mutableMapOf<String, List<String>>()
                for (key in obj.keys()) {
                    val array = obj.getJSONArray(key)
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getString(i))
                    }
                    map[key] = list
                }
                map
            } else {
                // 保存默认同义词表
                saveSynonyms(defaultSynonyms)
                defaultSynonyms
            }
        } catch (e: Exception) {
            defaultSynonyms
        }
    }
    
    private fun saveSynonyms(synonyms: Map<String, List<String>>) {
        try {
            if (!dataDir.exists()) dataDir.mkdirs()
            val obj = JSONObject()
            for ((key, values) in synonyms) {
                val array = org.json.JSONArray()
                for (v in values) array.put(v)
                obj.put(key, array)
            }
            synonymsFile.writeText(obj.toString(2))
        } catch (e: Exception) {
            // ignore
        }
    }
    
    /**
     * 找到最佳匹配类别
     * @param userInput 用户输入
     * @param existingCategories 已有类别列表（按时长排序）
     * @return (最佳类别, 是否是新类别)
     */
    fun findBestCategory(
        userInput: String,
        existingCategories: List<Pair<String, Long>> = emptyList()
    ): Pair<String, Boolean> {
        val input = userInput.trim()
        if (input.isEmpty()) return input to true
        
        // 1. 同义词精确匹配
        for ((mainCat, synList) in synonyms) {
            if (input == mainCat) return mainCat to false
            if (synList.any { it == input }) return mainCat to false
        }
        
        // 2. 同义词模糊匹配
        for ((mainCat, synList) in synonyms) {
            for (syn in synList) {
                if (stringSimilarity(input, syn) >= 0.6) {
                    return mainCat to false
                }
            }
            if (stringSimilarity(input, mainCat) >= 0.6) {
                return mainCat to false
            }
        }
        
        // 3. 已有类别精确匹配
        for ((cat, _) in existingCategories) {
            if (input == cat) return cat to false
        }
        
        // 4. 已有类别模糊匹配（按时长排序，长的优先）
        var bestMatch: String? = null
        var bestSimilarity = 0.0
        for ((cat, _) in existingCategories) {
            val sim = stringSimilarity(input, cat)
            if (sim >= 0.6 && sim > bestSimilarity) {
                bestSimilarity = sim
                bestMatch = cat
            }
        }
        if (bestMatch != null) return bestMatch to false
        
        // 5. 完全没找到，创建新类别
        return input to true
    }
    
    /**
     * 添加同义词
     */
    fun addSynonym(category: String, synonym: String) {
        val current = synonyms.toMutableMap()
        val list = (current[category] ?: emptyList()).toMutableList()
        if (!list.contains(synonym)) {
            list.add(synonym)
            current[category] = list
            saveSynonyms(current)
        }
    }
    
    companion object {
        /**
         * 简单字符串相似度（基于 LCS）
         */
        fun stringSimilarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val lcs = longestCommonSubsequence(a, b)
            return lcs.toDouble() / maxOf(a.length, b.length)
        }
        
        private fun longestCommonSubsequence(a: String, b: String): Int {
            val m = a.length
            val n = b.length
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 1..m) {
                for (j in 1..n) {
                    dp[i][j] = if (a[i - 1] == b[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
                }
            }
            return dp[m][n]
        }
    }
}
