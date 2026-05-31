package com.operit.timetracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.util.Log
import androidx.core.app.NotificationCompat
import com.operit.timetracker.R
import com.operit.timetracker.data.DataStore
import com.operit.timetracker.data.TimeRecord
import kotlinx.coroutines.*
import java.io.File

class AppMonitorService : Service() {
    
    companion object {
        private const val TAG = "AppMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "app_monitor_channel"
        private const val CHECK_INTERVAL_MS = 2_000L // 2秒检查一次，更快响应
        private const val NOTIFICATION_UPDATE_MS = 1_000L // 1秒更新通知
        
        // App名称缓存，避免频繁查询PackageManager
        private val appNameCache = mutableMapOf<String, String>()
        
        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }
        
        /**
         * 检查服务是否正在运行
         */
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (AppMonitorService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }
    
    private lateinit var dataStore: DataStore
    private lateinit var usageStatsManager: UsageStatsManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 屏幕状态监听
    private var screenStateReceiver: ScreenStateReceiver? = null
    
    // App分类映射 - 完整版（来自Linux端 + 补充）
    private val appCategoryMap = mapOf(
        // ========== 社交/刷手机 ==========
        "com.tencent.mm" to "社交", // 微信
        "com.tencent.mobileqq" to "社交", // QQ
        "com.sina.weibo" to "社交", // 微博
        "com.xingin.xhs" to "社交", // 小红书
        "com.tencent.karaoke" to "社交", // 全民K歌
        "org.telegram.messenger" to "社交", // Telegram
        "com.twitter.android" to "社交", // X/Twitter
        "com.soft.blued" to "社交", // Blued
        
        // ========== 刷视频/娱乐 ==========
        "com.ss.android.ugc.aweme" to "娱乐", // 抖音
        "com.smile.gifmaker" to "娱乐", // 快手
        "tv.danmaku.bili" to "娱乐", // B站
        "com.bilibili.app.in" to "娱乐", // B站国际版
        "com.youku.phone" to "娱乐", // 优酷
        "com.qiyi.video" to "娱乐", // 爱奇艺
        "com.baidu.youavideo" to "娱乐", // 百度视频
        "com.miui.video" to "娱乐", // 小米视频
        "com.google.android.youtube" to "娱乐", // YouTube
        "org.schabi.newpipe" to "娱乐", // NewPipe
        "com.bilibili.bilibililive" to "娱乐", // B站直播姬
        
        // ========== 音乐 ==========
        "com.netease.cloudmusic" to "娱乐", // 网易云音乐
        "com.kugou.android" to "娱乐", // 酷狗音乐
        "com.kuwo.player" to "娱乐", // 酷我音乐
        "com.tencent.qqmusic" to "娱乐", // QQ音乐
        "com.miui.player" to "娱乐", // 小米音乐
        
        // ========== 阅读/小说 ==========
        "io.legado.app.release" to "阅读", // 阅读
        "com.dragon.read" to "阅读", // 番茄小说
        "com.tencent.weread" to "阅读", // 微信读书
        "com.duokan.reader" to "阅读", // 多看阅读
        "com.jjwxc.reader" to "阅读", // 晋江小说
        "com.positron_it.zlib" to "阅读", // Z-Lib
        "com.chaozh.iReader.widget" to "阅读", // 电子书
        "is.follow" to "阅读", // Folo
        
        // ========== 游戏 ==========
        "com.tencent.tmgp.sgame" to "游戏", // 王者荣耀
        "com.tencent.tmgp.sgamece" to "游戏", // 王者荣耀CE
        "com.miHoYo.Yuanshen" to "游戏", // 原神
        "jp.pokemon.pokemonsleep" to "游戏", // 宝可梦睡眠
        "com.hypergryph.endfield.bilibili" to "游戏", // 明日方舟终末地
        "com.nianticproject.ingress" to "游戏", // Ingress
        "com.prineside.tdi2" to "游戏", // Infinitode 2/王国保卫战
        "com.threeminutegames.lifeline.google" to "游戏", // Lifeline
        "com.threeminutegames.lifeline2.goog" to "游戏", // Lifeline 2
        "com.threeminutegames.lifelinesilentnight.goog" to "游戏", // Lifeline Silent Night
        "com.threeminutegames.lifelineflatlinegoog" to "游戏", // Flatline
        "com.threeminutegames.lifelinehalftoinfinitygoog" to "游戏", // Infinity
        "com.threeminutegames.lifelinewhiteoutgoog" to "游戏", // Whiteout
        "com.threeminutegames.lifelinecrisislinegoog" to "游戏", // Crisis Line
        "com.threeminutegames.lifelinebyit.goog" to "游戏", // 生命线在你身边
        "com.fizzd.connectedworlds" to "游戏", // A Dance of Fire and Ice
        "de.stollenmayer.philipp.Pop_1_1_Android" to "游戏", // Okay?
        "com.valvesoftware.android.steam.community" to "游戏", // Steam
        "com.hermes.mk.asia" to "游戏", // 世界計畫
        "com.hermes.mk" to "游戏", // 初音未来
        "com.tencent.nrc" to "游戏", // 洛克王国
        "com.groundspeak.geocaching.intro" to "游戏", // Geocaching
        
        // ========== AI工具 ==========
        "com.ai.assistance.operit" to "AI", // Operit AI
        "com.openai.chatgpt" to "AI", // ChatGPT
        "com.google.android.apps.bard" to "AI", // Gemini
        "com.tencent.hunyuan.app.chat" to "AI", // 元宝
        "com.crirp.zhipu" to "AI", // 智谱清言
        "com.larus.nova" to "AI", // 豆包
        "com.aliyun.tongyi" to "AI", // 千问
        "com.antgroup.leopard.android" to "AI", // 灵光
        
        // ========== 工作/效率 ==========
        "com.tencent.wemeet.app" to "工作", // 腾讯会议
        "com.alibaba.android.rimet" to "工作", // 钉钉
        "com.ss.android.lark" to "工作", // 飞书
        "com.tencent.androidqqmail" to "工作", // QQ邮箱
        "com.google.android.apps.docs.editors.sheets" to "工作", // Google表格
        "com.google.android.apps.docs.editors.slides" to "工作", // Google幻灯片
        "com.google.android.apps.docs.editors.docs" to "工作", // Google文档
        "cn.wps.moffice_eng.xiaomi.lite" to "工作", // WPS小米版
        "cn.wps.moffice_eng" to "工作", // WPS Office
        "cn.wps.note" to "工作", // WPS便签
        "com.lemon.lv" to "工作", // 剪映
        "com.bilibili.studio" to "工作", // 必剪
        "com.intsig.camscanner" to "工作", // 扫描全能王
        "com.google.android.gm" to "工作", // Gmail
        "com.android.email" to "工作", // 电子邮件
        "com.tencent.mp" to "工作", // 公众号助手
        
        // ========== 学习 ==========
        "com.eusoft.eudic" to "学习", // 欧路词典
        "com.eusoft.ting.en" to "学习", // 每日英语听力
        "com.duolingo" to "学习", // 多邻国
        "com.shici" to "学习", // 诗词吾爱
        "com.bf.words_recite" to "学习", // 黑马背单词
        "cn.com.langeasy.LangEasyLexis" to "学习", // 不背单词
        "com.maimemo.android.momo" to "学习", // 墨墨背单词
        "com.sun.schulte" to "学习", // 注意力训练
        "me.i38.anki" to "学习", // 碎片记忆/Anki
        "com.hustzp.com.xichuangzhu" to "学习", // 西窗烛
        "com.tipsoon.android" to "学习", // 简讯
        "com.db.translate.app" to "学习", // DB翻译
        "com.google.android.apps.translate" to "学习", // 翻译
        "com.metasolearnwhat" to "学习", // 今天学点啥
        
        // ========== 购物 ==========
        "com.taobao.taobao" to "购物", // 淘宝
        "com.jingdong.app.mall" to "购物", // 京东
        "com.xunmeng.pinduoduo" to "购物", // 拼多多
        "com.taobao.idlefish" to "购物", // 闲鱼
        "com.sankuai.meituan" to "购物", // 美团
        "com.smzdm.client.android" to "购物", // 什么值得买
        "com.xiaomi.shop" to "购物", // 小米商城
        "com.xiaomi.youpin" to "购物", // 小米有品
        
        // ========== 生活服务 ==========
        "com.eg.android.AlipayGphone" to "生活", // 支付宝
        "com.taobao.trip" to "生活", // 飞猪旅行
        "com.MobileTicket" to "生活", // 铁路12306
        "com.openrice.android" to "生活", // OpenRice
        "com.xiaomi.smarthome" to "生活", // 米家
        "com.mi.health" to "生活", // 小米运动健康
        "com.duokan.phone.remotecontroller" to "生活", // 万能遥控
        "com.miui.weather2" to "生活", // 天气
        "com.android.calendar" to "生活", // 日历
        "com.miui.findmy" to "生活", // 查找设备
        
        // ========== 交通/出行 ==========
        "com.autonavi.minimap" to "交通", // 高德地图
        "com.umetrip.android.msky.app" to "交通", // 航旅纵横
        "com.baidu.carlife.vivo" to "交通", // 百度CarLife
        "com.google.android.apps.maps" to "交通", // Google地图
        
        // ========== 金融/理财 ==========
        "cn.gov.pbc.dcep" to "金融", // 数字人民币
        "cmb.pb" to "金融", // 招商银行
        "com.cmbchina.ccd.pluto.cmbActivity" to "金融", // 掌上生活
        "com.ecitic.bank.mobile" to "金融", // 中信银行
        "com.chinamworld.main" to "金融", // 建设银行
        "com.chinamworld.bocmbci" to "金融", // 中国银行
        "com.cebbank.mobile.cemb" to "金融", // 光大银行
        "com.czbank.mbank" to "金融", // 浙商银行
        "com.yitong.mbank.psbc" to "金融", // 邮储银行
        "com.unionpay" to "金融", // 云闪付
        "com.google.android.apps.walletnfcref" to "金融", // Google钱包
        
        // ========== 工具 ==========
        "com.android.chrome" to "工具", // Chrome
        "alook.browser" to "工具", // Alook浏览器
        "com.google.android.apps.nbu.files" to "工具", // 文件极客
        "com.alphainventor.filemanager" to "工具", // 文件管理器
        "bin.mt.plus" to "工具", // MT管理器
        "ru.zdevs.zarchiver" to "工具", // ZArchiver
        "com.xiaomi.scanner" to "工具", // AI扫描
        "com.google.android.apps.authenticator2" to "工具", // Authenticator
        "com.google.ar.core" to "工具", // AR Core
        
        // ========== 系统/忽略 ==========
        "com.miui.home" to null, // 桌面
        "com.android.launcher" to null, // 桌面
        "com.android.systemui" to null, // 系统界面
        "com.android.settings" to null, // 设置
        "com.miui.gallery" to null, // 相册
        "com.miui.securitymanager" to null, // 手机管家
        "com.miui.securitycenter" to null, // 安全中心
        "com.miui.mediaeditor" to null, // 编辑
        "com.miui.calculator" to null, // 计算器
        "com.miui.notes" to null, // 笔记
        "com.android.deskclock" to null, // 时钟
        "com.miui.compass" to null, // 指南针
        "com.miui.screenrecorder" to null, // 录屏
        "com.android.soundrecorder" to null, // 录音机
        "com.android.camera" to null, // 相机
        "com.xiaomi.market" to null, // 应用商店
        "com.android.vending" to null, // Google Play
        "com.coolapk.market" to null, // 酷安
        "com.miui.themestore" to null, // 主题商店
        "com.miui.cleanmaster" to null, // 垃圾清理
        "com.miui.newhome" to null, // 内容中心
        "com.miui.newmidrive" to null, // 云盘
        "com.mfashiongallery.emag" to null, // 小米画报
        "com.xiaomi.vipaccount" to null, // 小米社区
        "com.xiaomi.minigame" to null, // 小游戏
        "com.miui.miservice" to null, // 服务与反馈
        "com.miui.huanji" to null, // 换机
        "com.miui.voiceassistProxy" to null, // 小爱
        "com.xiaomi.mibrain.speech" to null, // 系统语音
        "com.baidu.input_mi" to null, // 百度输入法
        "com.tencent.wetype" to null, // 微信输入法
        "com.iflytek.inputmethod.miui" to null, // 讯飞输入法
        "com.funtouch.uiengine" to null, // 锁屏样式
        "com.operit.timetracker" to null, // 时间记录助手(自身)
        "com.java.myapplication" to null, // 测试应用
    )
    
    // 忽略的包名列表
    private val ignorePackages = setOf(
        "com.android.launcher",
        "com.miui.home",
        "com.android.systemui",
        "com.operit.timetracker" // 自身
    )
    
    // 当前前台应用信息
    private var currentForegroundPackage: String? = null
    private var currentForegroundAppName: String? = null
    
    override fun onCreate() {
        super.onCreate()
        dataStore = DataStore(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        // 初始化日志系统
        AppLogger.init(filesDir)
        AppLogger.i("=== Service onCreate ===")
        AppLogger.i("数据目录: ${filesDir.absolutePath}")
        AppLogger.i("UsageStatsManager: ${usageStatsManager != null}")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 注册屏幕状态监听
        registerScreenStateReceiver()
        
        // 立即更新通知
        updateNotification()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i("=== onStartCommand ===")
        AppLogger.i("intent: $intent, flags: $flags, startId: $startId")
        
        startForeground(NOTIFICATION_ID, createNotification())
        AppLogger.i("startForeground 已调用")
        
        startMonitoring()
        startNotificationUpdater()
        AppLogger.i("监控和通知更新器已启动")
        
        // 启动后立即检测一次
        serviceScope.launch {
            delay(1000)
            try {
                AppLogger.i("=== 首次检测 ===")
                checkCurrentApp()
            } catch (e: Exception) {
                AppLogger.e("首次检测失败", e)
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        stopNotificationUpdater()
        unregisterScreenStateReceiver()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "时间记录",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示当前任务和App使用情况"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(
        category: String? = null,
        appName: String? = null,
        duration: String? = null
    ): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = if (category != null && appName != null) {
            "⏱️ $category · $appName"
        } else if (category != null) {
            "⏱️ $category"
        } else {
            "⏱️ 时间记录助手"
        }
        
        val text = if (duration != null) {
            "已使用 $duration"
        } else {
            "等待检测App..."
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    private fun updateNotification() {
        val currentTask = dataStore.loadCurrentTask()
        
        if (currentTask != null) {
            val duration = System.currentTimeMillis() - currentTask.startTime
            val durationText = formatDuration(duration)
            val appName = getAppName(currentTask.originalInput)
            
            val notification = createNotification(
                category = currentTask.category,
                appName = appName,
                duration = durationText
            )
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else {
            val notification = createNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    private fun startNotificationUpdater() {
        notificationUpdateJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateNotification()
                    delay(NOTIFICATION_UPDATE_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "通知更新异常", e)
                    delay(NOTIFICATION_UPDATE_MS)
                }
            }
        }
    }
    
    private fun stopNotificationUpdater() {
        notificationUpdateJob?.cancel()
    }
    
    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%d:%02d", minutes, seconds % 60)
            else -> String.format("%d秒", seconds)
        }
    }
    
    private fun getAppName(packageName: String): String {
        // 先检查缓存
        appNameCache[packageName]?.let { return it }
        
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(applicationInfo).toString()
            // 缓存结果
            appNameCache[packageName] = appName
            appName
        } catch (e: PackageManager.NameNotFoundException) {
            AppLogger.w("getAppName失败: $packageName -> 回退到包名")
            packageName
        }
    }
    
    private fun registerScreenStateReceiver() {
        screenStateReceiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
    }
    
    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen state receiver", e)
            }
        }
    }
    
    private fun startMonitoring() {
        monitorJob = serviceScope.launch {
            Log.i(TAG, "App监控服务启动")
            
            while (isActive) {
                try {
                    checkCurrentApp()
                    delay(CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "监控异常", e)
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
    }
    
    private fun stopMonitoring() {
        monitorJob?.cancel()
        Log.i(TAG, "App监控服务停止")
    }
    
    private fun checkCurrentApp() {
        AppLogger.d("--- checkCurrentApp 开始 ---")
        
        // 检查是否有锁定任务
        if (isMonitorLocked()) {
            AppLogger.w("监控已锁定，跳过检查")
            return
        }
        
        // 获取当前前台App
        val currentPackage = getCurrentForegroundApp()
        AppLogger.i("前台App: ${currentPackage ?: "null"}")
        
        if (currentPackage != null) {
            currentForegroundPackage = currentPackage
            currentForegroundAppName = getAppName(currentPackage)
            AppLogger.i("App名称: $currentForegroundAppName")
            
            // 检查分类是否需要忽略（null = 忽略）
            val category = mapPackageToCategory(currentPackage)
            if (category == null) {
                AppLogger.d("忽略App: $currentPackage ($currentForegroundAppName)")
                return
            }
            
            // 获取当前记录的任务
            val currentTask = dataStore.loadCurrentTask()
            AppLogger.i("当前任务: ${currentTask?.category ?: "null"} (ID: ${currentTask?.id ?: "null"})")
            AppLogger.i("当前任务包名: ${currentTask?.originalInput ?: "null"}")
            
            // 如果当前没有任务，或者任务类别不同，则切换任务
            if (currentTask == null || currentTask.originalInput != currentPackage) {
                AppLogger.i("需要切换任务!")
                // 停止当前任务
                if (currentTask != null) {
                    AppLogger.i("停止旧任务: ${currentTask.category}")
                    stopCurrentTask(currentTask)
                }
                
                // 开始新任务
                val category = mapPackageToCategory(currentPackage)
                AppLogger.i("开始新任务: $category ($currentPackage)")
                if (category != null) {
                    startNewTask(category, currentPackage)
                } else {
                    AppLogger.d("分类为null，跳过: $currentPackage")
                }
            } else {
                AppLogger.d("任务未变化，继续监控")
            }
        } else {
            AppLogger.w("无法获取前台App，可能处于熄屏状态")
            handleScreenState()
        }
        
        // 打印状态文件内容
        try {
            val stateFile = File(filesDir, "timetracker/state.json")
            AppLogger.d("state.json内容: ${stateFile.readText()}")
        } catch (e: Exception) {
            AppLogger.e("读取state.json失败", e)
        }
    }
    
    private fun getCurrentForegroundApp(): String? {
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * 60 // 扩大到60秒
        
        AppLogger.d("查询UsageStats: ${beginTime} ~ ${endTime}")
        
        try {
            // 先检查权限
            val appOps = getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                AppLogger.e("UsageStats权限未授权！")
                return null
            }
            
            val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
            var currentPackage: String? = null
            var latestEventTime = 0L
            var eventCount = 0
            val recentEvents = mutableListOf<String>()
            
            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)
                eventCount++
                
                val isForegroundEvent = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> true
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> true
                    else -> false
                }
                
                // 记录最近5个事件
                if (eventCount <= 5) {
                    recentEvents.add("type=${event.eventType}, pkg=${event.packageName}")
                }
                
                if (isForegroundEvent && event.timeStamp > latestEventTime) {
                    latestEventTime = event.timeStamp
                    currentPackage = event.packageName
                }
            }
            
            AppLogger.i("UsageStats: 共${eventCount}事件, 最近: ${recentEvents.joinToString("; ")}")
            AppLogger.i("检测结果: 最新前台App=$currentPackage")
            return currentPackage
        } catch (e: SecurityException) {
            AppLogger.e("SecurityException: ${e.message}")
            return null
        } catch (e: Exception) {
            AppLogger.e("获取前台App失败: ${e.message}", e)
            return null
        }
    }
    
    private fun mapPackageToCategory(packageName: String): String? {
        AppLogger.d("mapPackageToCategory: 查询 $packageName")
        
        val mapped = appCategoryMap[packageName]
        AppLogger.d("映射结果: ${mapped ?: "null"}")
        
        if (mapped != null) {
            AppLogger.i("找到映射: $packageName -> $mapped")
            return mapped
        }
        
        // 明确标记为忽略的（null值在map中）
        if (appCategoryMap.containsKey(packageName)) {
            AppLogger.i("包名被标记为忽略: $packageName")
            return null
        }
        
        // 不在映射表里的，用App名称作为分类
        val appName = getAppName(packageName)
        AppLogger.d("App名称: $appName")
        
        val result = if (appName != packageName) appName else "其他"
        AppLogger.i("使用App名称作为分类: $packageName -> $result")
        return result
    }
    
    private fun startNewTask(category: String, packageName: String) {
        AppLogger.i(">>> startNewTask: category=$category, pkg=$packageName")
        
        val now = System.currentTimeMillis()
        val record = TimeRecord(
            id = 0,
            category = category,
            startTime = now,
            endTime = null,
            durationSeconds = 0,
            originalInput = packageName,
            createdAt = now
        )
        
        val saved = dataStore.addRecord(record)
        AppLogger.i("记录已保存: id=${saved.id}")
        
        dataStore.saveCurrentTask(saved)
        AppLogger.i("当前任务已保存到state.json")
        
        // 立即更新通知
        mainHandler.post { updateNotification() }
        AppLogger.i("通知已更新")
        
        Log.i(TAG, "自动开始任务: $category ($packageName)")
    }
    
    private fun stopCurrentTask(task: TimeRecord) {
        val now = System.currentTimeMillis()
        val durationSeconds = (now - task.startTime) / 1000
        
        val updated = task.copy(
            endTime = now,
            durationSeconds = durationSeconds
        )
        
        dataStore.updateRecord(updated)
        dataStore.saveCurrentTask(null)
        
        Log.i(TAG, "自动停止任务: ${task.category} (时长: ${durationSeconds}秒)")
    }
    
    private fun handleScreenState() {
        // 这里可以添加熄屏处理逻辑
    }
    
    private fun isMonitorLocked(): Boolean {
        val state = dataStore.loadMonitorState()
        return state?.locked ?: false
    }
}