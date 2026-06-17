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
import com.operit.timetracker.data.RecordType
import com.operit.timetracker.data.TimeRecord
import kotlinx.coroutines.*
import java.io.File

/**
 * 活跃 App 信息
 */
data class ActiveApp(
    val packageName: String,
    val type: RecordType,
    val lastActiveTime: Long = System.currentTimeMillis()
)

class AppMonitorService : Service() {
    
    companion object {
        private const val TAG = "AppMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "app_monitor_channel"
        const val HEARTBEAT_REQUEST_CODE = 9999
        const val ACTION_HEARTBEAT = "com.operit.timetracker.ACTION_HEARTBEAT"
        private const val CHECK_INTERVAL_MS = 2_000L // 2秒检查一次，更快响应
        private const val NOTIFICATION_UPDATE_MS = 1_000L // 1秒更新通知
        const val ALARM_INTERVAL_MS = 60_000L // AlarmManager 60秒心跳（public，供 HeartbeatReceiver 使用）
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L // WakeLock 10分钟超时
        private const val IDLE_PACKAGE = "__SCREEN_OFF__" // 空闲任务标记包名
        
        // App名称缓存，避免频繁查询PackageManager
        private val appNameCache = mutableMapOf<String, String>()
        
        // 进程级运行状态标记（比 getRunningServices 可靠）
        @Volatile
        var isServiceRunning = false
            private set
        
        // 监控循环健康检测标记
        @Volatile
        var lastMonitorLoopTimeMs = 0L
            private set
        @Volatile
        var monitorLoopCycleCount = 0L
            private set
        
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
         * 检查服务是否正在运行（双重检测：进程级标记 + ActivityManager）
         */
        fun isRunning(context: Context): Boolean {
            // 优先使用进程级标记
            if (isServiceRunning) return true
            
            // 降级到 ActivityManager 兜底（API 26+ 可能不准确）
            return try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                @Suppress("DEPRECATION")
                for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                    if (AppMonitorService::class.java.name == service.service.className) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }
    }
    
    private lateinit var dataStore: DataStore
    private lateinit var usageStatsManager: UsageStatsManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // WakeLock 防止 CPU 休眠
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
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
        "com.operit.timetracker", // 自身
        IDLE_PACKAGE // 空闲任务标记
    )
    
    /**
     * 辅助应用白名单：当正在进行某活动时，打开这些应用不触发切换
     * key = 当前任务类别, value = 允许的包名列表
     */
    private val assistantApps = mapOf(
        "睡觉" to setOf(
            "jp.pokemon.pokemonsleep", // 宝可梦睡眠
            "com.netease.cloudmusic", // 网易云音乐
            "com.kugou.android", // 酷狗音乐
            "com.kuwo.player", // 酷我音乐
            "tv.danmaku.bili", // B站（助眠音频）
            "com.bilibili.app.in", // B站国际版
            "com.mi.health", // 小米健康
            "com.huawei.health" // 华为健康
        ),
        "学习" to setOf(
            "com.eusoft.eudic", // 欧路词典
            "com.eusoft.ting.en", // 每日英语听力
            "com.duolingo", // 多邻国
            "com.shici", // 诗词
            "com.bf.words_recite", // 背单词
            "cn.com.langeasy.LangEasyLexis" // 不背单词
        ),
        "工作" to setOf(
            "com.tencent.androidqqmail", // QQ邮箱
            "com.google.android.apps.docs.editors.sheets", // Google表格
            "com.google.android.apps.docs.editors.slides", // Google幻灯片
            "com.google.android.apps.docs.editors.docs", // Google文档
            "cn.wps.moffice_eng.xiaomi.lite", // WPS
            "cn.wps.note" // WPS笔记
        )
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
        
        // 标记服务正在运行
        isServiceRunning = true
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 获取 WakeLock 防止 CPU 休眠
        acquireWakeLock()
        
        // 注册屏幕状态监听
        registerScreenStateReceiver()
        
        // 设置 AlarmManager 定期心跳（兜底保活）
        scheduleAlarmHeartbeat()
        
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
        isServiceRunning = false
        stopMonitoring()
        stopNotificationUpdater()
        unregisterScreenStateReceiver()
        cancelAlarmHeartbeat()
        releaseWakeLock()
        serviceScope.cancel()
        
        // 最后一搏：延迟 1 秒后尝试重启服务
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                AppLogger.i("Service onDestroy 后尝试自重启")
                start(this)
            } catch (e: Exception) {
                AppLogger.e("自重启失败", e)
            }
        }, 1000)
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
        // 防重入：先取消旧的通知更新协程
        notificationUpdateJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
        
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
        // 空闲任务标记不是真实包名
        if (packageName == IDLE_PACKAGE) return "空闲"
        
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
        screenStateReceiver = ScreenStateReceiver(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
        AppLogger.i("屏幕状态监听已动态注册")
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
        // 防重入：先取消旧的监控协程，避免多个协程并发读写 state.json
        monitorJob?.let {
            if (it.isActive) {
                AppLogger.w("监控协程已在运行，取消旧的再启动新的")
                it.cancel()
            }
        }
        
        monitorJob = serviceScope.launch {
            Log.i(TAG, "App监控服务启动")
            AppLogger.i("[WATCHDOG] 监控循环已启动")
            var wakelockRenewCounter = 0
            
            while (isActive) {
                try {
                    checkCurrentApp()
                    
                    // 更新健康检测标记
                    lastMonitorLoopTimeMs = System.currentTimeMillis()
                    monitorLoopCycleCount++
                    if (monitorLoopCycleCount % 30 == 0L) { // 每60秒打一次日志
                        AppLogger.i("[WATCHDOG] 监控循环运行中: cycle=$monitorLoopCycleCount, last=${lastMonitorLoopTimeMs}")
                    }
                    
                    // 每 3 分钟续期一次 WakeLock（在超时前续上，留足够余量）
                    // 直接在 IO 协程中调用，不绕主线程，避免主线程阻塞导致续期丢失
                    // HyperOS 可能提前回收 WakeLock，缩短续期间隔提高存活率
                    wakelockRenewCounter++
                    if (wakelockRenewCounter >= 90) { // 90 * 2s = 180s = 3min
                        wakelockRenewCounter = 0
                        renewWakeLock()
                    }
                    
                    delay(CHECK_INTERVAL_MS)
                } catch (e: CancellationException) {
                    // 协程被取消，记录并退出
                    AppLogger.e("[WATCHDOG] 监控循环被取消！", e)
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("[WATCHDOG] 监控异常，继续运行: ${e.message}", e)
                    lastMonitorLoopTimeMs = System.currentTimeMillis()
                    delay(CHECK_INTERVAL_MS)
                }
            }
            
            // 如果 while 退出了（isActive = false），记录
            AppLogger.e("[WATCHDOG] 监控循环正常退出！isActive=false")
        }
        
        // 注册协程完成回调
        monitorJob?.invokeOnCompletion { cause ->
            AppLogger.e("[WATCHDOG] monitorJob 完成: cause=${cause?.message ?: "正常"}")
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
        
        // 如果当前是空闲任务（熄屏），等 USER_PRESENT 解锁后再处理
        // 但如果屏幕实际是亮的（服务重启后 USER_PRESENT 已错过），自动恢复
        val currentTask = dataStore.loadCurrentTask()
        if (currentTask != null && currentTask.originalInput == IDLE_PACKAGE) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) {
                // 屏幕亮着但还在空闲状态 → USER_PRESENT 已错过，自动恢复
                AppLogger.i("屏幕已亮但仍在空闲状态，自动恢复检测（USER_PRESENT 已错过）")
                stopCurrentTask(currentTask)
                // 继续往下执行正常检测流程
            } else {
                AppLogger.d("当前为空闲状态，等待用户解锁")
                return
            }
        }
        
        // 获取所有活跃 App（前台 + 小窗 + 后台前台服务）
        val activeApps = getActiveApps()
        
        if (activeApps.isNotEmpty()) {
            // 找到主 app（最新的前台 app）
            val primaryApp = activeApps.firstOrNull { it.type == RecordType.PRIMARY }
            // 找到伴随 app（后台前台服务）
            val companionApps = activeApps.filter { it.type == RecordType.COMPANION }
            // 找到辅助 app（小窗）
            val auxiliaryApps = activeApps.filter { it.type == RecordType.AUXILIARY }
            
            AppLogger.i("活跃App: 主=${primaryApp?.packageName ?: "无"}, 伴随=${companionApps.size}, 辅助=${auxiliaryApps.size}")
            
            // 处理主 app
            if (primaryApp != null) {
                handlePrimaryApp(primaryApp.packageName)
            } else {
                // 没有主 app，可能是屏幕亮着但 API 被过滤
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (pm.isInteractive) {
                    AppLogger.w("屏幕亮着但获取不到主App，可能是API被HyperOS过滤")
                } else {
                    handleScreenState()
                }
            }
            
            // 处理伴随 app（后台前台服务）
            for (companionApp in companionApps) {
                handleCompanionApp(companionApp.packageName)
            }
            
            // 处理辅助 app（小窗）
            for (auxiliaryApp in auxiliaryApps) {
                handleAuxiliaryApp(auxiliaryApp.packageName)
            }
            
            // 停止不再活跃的伴随/辅助任务
            cleanupInactiveCompanionAndAuxiliary(activeApps)
            
        } else {
            // 无法获取活跃 App
            AppLogger.w("无法获取活跃App")
            
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) {
                AppLogger.w("屏幕亮着但获取不到活跃App，可能是API被HyperOS过滤")
            } else {
                handleScreenState()
            }
        }
        
        // 打印状态文件内容
        try {
            val stateFile = File(filesDir, "timetracker/state.json")
            AppLogger.d("state.json内容: ${stateFile.readText()}")
        } catch (e: Exception) {
            AppLogger.e("读取state.json失败", e)
        }
    }
    
    /**
     * 处理主 app（前台）
     */
    private fun handlePrimaryApp(packageName: String) {
        currentForegroundPackage = packageName
        currentForegroundAppName = getAppName(packageName)
        
        val category = mapPackageToCategory(packageName)
        if (category == null) {
            AppLogger.d("忽略App: $packageName ($currentForegroundAppName)")
            val currentTask = dataStore.loadCurrentTask()
            if (currentTask != null && currentTask.originalInput != IDLE_PACKAGE && currentTask.recordType == RecordType.PRIMARY) {
                AppLogger.i("用户进入忽略App，停止当前主任务: ${currentTask.category}")
                stopCurrentTask(currentTask)
            }
            return
        }
        
        val currentTask = dataStore.loadCurrentTask()
        
        if (currentTask == null || currentTask.originalInput != packageName || currentTask.recordType != RecordType.PRIMARY) {
            if (currentTask != null && isAssistantApp(currentTask.category, packageName)) {
                AppLogger.d("辅助应用，不切换: $packageName (当前: ${currentTask.category})")
                return
            }
            
            AppLogger.i("需要切换主任务!")
            if (currentTask != null && currentTask.recordType == RecordType.PRIMARY) {
                AppLogger.i("停止旧主任务: ${currentTask.category}")
                stopCurrentTask(currentTask)
            }
            
            AppLogger.i("开始新主任务: $category ($packageName)")
            checkAndCleanOolongTask(category)
            startNewTask(category, packageName, RecordType.PRIMARY)
        } else {
            AppLogger.d("主任务未变化，继续监控")
        }
    }
    
    /**
     * 处理伴随 app（后台前台服务，如音乐、导航）
     */
    private fun handleCompanionApp(packageName: String) {
        val category = mapPackageToCategory(packageName) ?: return
        
        // 检查是否已经在记录
        val records = dataStore.loadRecords()
        val existingRecord = records.lastOrNull { 
            it.originalInput == packageName && 
            it.recordType == RecordType.COMPANION && 
            it.endTime == null 
        }
        
        if (existingRecord == null) {
            AppLogger.i("开始伴随任务: $category ($packageName)")
            startNewTask(category, packageName, RecordType.COMPANION)
        } else {
            AppLogger.d("伴随任务已存在: $category ($packageName)")
        }
    }
    
    /**
     * 处理辅助 app（小窗）
     */
    private fun handleAuxiliaryApp(packageName: String) {
        val category = mapPackageToCategory(packageName) ?: return
        
        // 检查是否已经在记录
        val records = dataStore.loadRecords()
        val existingRecord = records.lastOrNull { 
            it.originalInput == packageName && 
            it.recordType == RecordType.AUXILIARY && 
            it.endTime == null 
        }
        
        if (existingRecord == null) {
            AppLogger.i("开始辅助任务: $category ($packageName)")
            startNewTask(category, packageName, RecordType.AUXILIARY)
        } else {
            AppLogger.d("辅助任务已存在: $category ($packageName)")
        }
    }
    
    /**
     * 清理不再活跃的伴随/辅助任务
     */
    private fun cleanupInactiveCompanionAndAuxiliary(activeApps: List<ActiveApp>) {
        val records = dataStore.loadRecords()
        val activePackageNames = activeApps.map { it.packageName }.toSet()
        
        // 停止不再活跃的伴随任务
        records.filter { 
            it.recordType == RecordType.COMPANION && 
            it.endTime == null && 
            it.originalInput !in activePackageNames 
        }.forEach { record ->
            AppLogger.i("停止不活跃的伴随任务: ${record.category} (${record.originalInput})")
            stopCurrentTask(record)
        }
        
        // 停止不再活跃的辅助任务
        records.filter { 
            it.recordType == RecordType.AUXILIARY && 
            it.endTime == null && 
            it.originalInput !in activePackageNames 
        }.forEach { record ->
            AppLogger.i("停止不活跃的辅助任务: ${record.category} (${record.originalInput})")
            stopCurrentTask(record)
        }
    }
    
    private fun getCurrentForegroundApp(): String? {
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * 120 // 2 分钟窗口，覆盖解锁延迟
        
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
            
            // 方法1: queryEvents (标准方式)
            val result = queryForegroundByEvents(beginTime, endTime)
            if (result != null) {
                return result
            }
            
            // 方法2: queryUsageStats (降级方案，HyperOS 可能过滤 queryEvents 但不过滤 queryUsageStats)
            AppLogger.w("queryEvents 返回空，尝试 queryUsageStats 降级方案")
            val result2 = queryForegroundByUsageStats(beginTime, endTime)
            if (result2 != null) {
                return result2
            }
            
            // 方法3: ActivityManager (最后兜底，已废弃但在部分 ROM 上仍然有效)
            AppLogger.w("queryUsageStats 也返回空，尝试 ActivityManager 兜底")
            return queryForegroundByActivityManager()
            
        } catch (e: SecurityException) {
            AppLogger.e("SecurityException: ${e.message}")
            return null
        } catch (e: Exception) {
            AppLogger.e("获取前台App失败: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 方法1: 通过 UsageEvents 查询前台 App
     */
    private fun queryForegroundByEvents(beginTime: Long, endTime: Long): String? {
        try {
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
                
                if (eventCount <= 5) {
                    recentEvents.add("type=${event.eventType}, pkg=${event.packageName}")
                }
                
                if (isForegroundEvent && event.timeStamp > latestEventTime) {
                    latestEventTime = event.timeStamp
                    currentPackage = event.packageName
                }
            }
            
            AppLogger.i("queryEvents: 共${eventCount}事件, 最近: ${recentEvents.joinToString("; ")}")
            if (currentPackage != null) {
                AppLogger.i("检测结果: 最新前台App=$currentPackage")
            }
            return currentPackage
        } catch (e: Exception) {
            AppLogger.e("queryEvents 异常: ${e.message}")
            return null
        }
    }
    
    /**
     * 获取所有活跃 App（前台 + 小窗 + 后台前台服务）
     */
    private fun getActiveApps(): List<ActiveApp> {
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * 120 // 2 分钟窗口
        
        try {
            // 检查权限
            val appOps = getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                AppLogger.e("UsageStats权限未授权！")
                return emptyList()
            }
            
            val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
            val activeApps = mutableMapOf<String, ActiveApp>()
            var eventCount = 0
            
            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)
                eventCount++
                
                when (event.eventType) {
                    // 前台/小窗 app
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        // 跳过桌面和系统界面
                        if (event.packageName == "com.miui.home" || 
                            event.packageName == "com.android.systemui") {
                            continue
                        }
                        // 跳过自己
                        if (event.packageName == packageName) {
                            continue
                        }
                        
                        // 多个 ACTIVITY_RESUMED 可能表示小窗模式
                        // 主 app 是最新的一個，其他的是小窗
                        val existing = activeApps[event.packageName]
                        if (existing == null || event.timeStamp > existing.lastActiveTime) {
                            activeApps[event.packageName] = ActiveApp(
                                packageName = event.packageName,
                                type = RecordType.AUXILIARY, // 先标记为辅助，后面根据顺序调整
                                lastActiveTime = event.timeStamp
                            )
                        }
                    }
                    // 后台前台服务
                    UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                        // 跳过自己
                        if (event.packageName == packageName) {
                            continue
                        }
                        activeApps[event.packageName] = ActiveApp(
                            packageName = event.packageName,
                            type = RecordType.COMPANION,
                            lastActiveTime = event.timeStamp
                        )
                    }
                }
            }
            
            AppLogger.i("getActiveApps: 共${eventCount}事件, 活跃App: ${activeApps.size}")
            
            // 按时间排序，最新的标记为 PRIMARY
            val sortedApps = activeApps.values.sortedByDescending { it.lastActiveTime }
            return sortedApps.mapIndexed { index, app ->
                if (index == 0 && app.type == RecordType.AUXILIARY) {
                    // 最新的一个是主 app
                    app.copy(type = RecordType.PRIMARY)
                } else {
                    app
                }
            }
            
        } catch (e: Exception) {
            AppLogger.e("getActiveApps 异常: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * 方法2: 通过 queryUsageStats 查询前台 App（降级方案）
     * HyperOS 可能过滤 queryEvents 但不一定会过滤 queryUsageStats
     */
    private fun queryForegroundByUsageStats(beginTime: Long, endTime: Long): String? {
        try {
            val usageStatsList = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_BEST,
                beginTime,
                endTime
            )
            
            if (usageStatsList.isNullOrEmpty()) {
                AppLogger.w("queryUsageStats 返回空列表")
                return null
            }
            
            // 找到最近有活动的 App
            var latestPackage: String? = null
            var latestTime = 0L
            
            for (stats in usageStatsList) {
                if (stats.lastTimeUsed > latestTime) {
                    latestTime = stats.lastTimeUsed
                    latestPackage = stats.packageName
                }
            }
            
            AppLogger.i("queryUsageStats: 共${usageStatsList.size}个App, 最近: $latestPackage")
            if (latestPackage != null) {
                AppLogger.i("检测结果: 最新前台App=$latestPackage")
            }
            return latestPackage
        } catch (e: Exception) {
            AppLogger.e("queryUsageStats 异常: ${e.message}")
            return null
        }
    }
    
    /**
     * 方法3: 通过 ActivityManager 查询前台 App（最后兜底）
     * 已废弃但在部分 ROM 上仍然有效
     */
    @Suppress("DEPRECATION")
    private fun queryForegroundByActivityManager(): String? {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.getRunningTasks(1)
            
            if (tasks.isNullOrEmpty()) {
                AppLogger.w("ActivityManager.getRunningTasks 返回空")
                return null
            }
            
            val topActivity = tasks[0].topActivity
            val packageName = topActivity?.packageName
            
            AppLogger.i("ActivityManager兜底: 最新前台App=$packageName")
            return packageName
        } catch (e: Exception) {
            AppLogger.e("ActivityManager兜底异常: ${e.message}")
            return null
        }
    }
    
    /**
     * 检查是否是辅助应用（当前活动下不应触发切换的应用）
     */
    private fun isAssistantApp(currentCategory: String, newPackage: String): Boolean {
        val allowedPackages = assistantApps[currentCategory] ?: return false
        return allowedPackages.contains(newPackage)
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
    
    private fun startNewTask(category: String, packageName: String, recordType: RecordType = RecordType.PRIMARY) {
        AppLogger.i(">>> startNewTask: category=$category, pkg=$packageName, type=$recordType")
        
        val now = System.currentTimeMillis()
        val record = TimeRecord(
            id = 0,
            category = category,
            startTime = now,
            endTime = null,
            durationSeconds = 0,
            originalInput = packageName,
            recordType = recordType,
            createdAt = now
        )
        
        val saved = dataStore.addRecord(record)
        AppLogger.i("记录已保存: id=${saved.id}")
        
        // 只保存主任务到 state.json（向后兼容）
        if (recordType == RecordType.PRIMARY) {
            dataStore.saveCurrentTask(saved)
            AppLogger.i("主任务已保存到state.json")
        }
        
        // 立即更新通知
        mainHandler.post { updateNotification() }
        AppLogger.i("通知已更新")
        
        Log.i(TAG, "自动开始任务: $category ($packageName) [${recordType.name}]")
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
    
    /**
     * 检查并清理乌龙任务
     * 条件：上一任务 <60 秒 + 5 分钟内 + 名字相似
     */
    private fun checkAndCleanOolongTask(newCategory: String) {
        val records = dataStore.loadRecords()
        if (records.isEmpty()) return
        
        val lastRecord = records.last()
        
        // 条件1：持续时间 < 60 秒
        if (lastRecord.durationSeconds >= 60) return
        
        // 条件2：刚结束（5 分钟内）
        val endTime = lastRecord.endTime ?: return
        if (System.currentTimeMillis() - endTime > 5 * 60 * 1000) return
        
        // 条件3：名字相似
        val similarity = stringSimilarity(lastRecord.category, newCategory)
        if (similarity < 0.6) return
        
        // 确认是乌龙，删除
        dataStore.deleteRecord(lastRecord.id)
        AppLogger.i("乌龙任务已清理: ${lastRecord.category} (${lastRecord.durationSeconds}秒) → $newCategory (相似度: ${(similarity * 100).toInt()}%)")
    }
    
    /**
     * 简单字符串相似度（0.0 ~ 1.0）
     * 基于最长公共子序列 / 较长字符串长度
     */
    private fun stringSimilarity(a: String, b: String): Double {
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
    
    private fun handleScreenState() {
        // 熄屏状态下前台App检测不可靠，记录日志即可
        AppLogger.d("屏幕熄灭状态，跳过前台App检测")
    }
    
    /**
     * 屏幕熄灭时调用：停止当前任务，开始记录「空闲」
     */
    fun onScreenOff() {
        AppLogger.i("屏幕熄灭")
        val currentTask = dataStore.loadCurrentTask()
        
        // 检查是否是持续性任务（手动开始的睡眠、吃饭、工作等）
        // 持续性任务在锁屏时不中断，继续记录
        if (isMonitorLocked()) {
            AppLogger.i("当前处于持续性任务（${currentTask?.category}），锁屏不中断")
            return
        }
        
        AppLogger.i("开始记录空闲时间")
        if (currentTask != null && currentTask.originalInput != IDLE_PACKAGE) {
            stopCurrentTask(currentTask)
        }
        // 开始空闲任务（如果还没有的话）
        if (dataStore.loadCurrentTask() == null) {
            startNewTask("空闲", IDLE_PACKAGE)
        }
    }
    
    /**
     * 用户解锁时调用：停止空闲任务，恢复正常检测
     */
    fun onUserPresent() {
        AppLogger.i("用户解锁，结束空闲时间")
        val currentTask = dataStore.loadCurrentTask()
        if (currentTask != null && currentTask.originalInput == IDLE_PACKAGE) {
            stopCurrentTask(currentTask)
        }
        // 等待 500ms 让系统记录前台 App，再检测
        serviceScope.launch {
            try {
                delay(500)
                checkCurrentApp()
            } catch (e: Exception) {
                AppLogger.e("解锁后检测失败", e)
            }
        }
    }
    
    /**
     * 屏幕亮起时被调用，仅记录日志（不触发检测，等 USER_PRESENT）
     */
    fun onScreenOn() {
        AppLogger.i("屏幕亮起（等待用户解锁）")
    }
    
    // ========== WakeLock 管理 ==========
    
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "TimeTracker::MonitorWakeLock"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS) // 带超时，防止 ROM 强制回收记电池异常
            }
            AppLogger.i("WakeLock 已获取，超时 ${WAKELOCK_TIMEOUT_MS / 1000}秒")
        } catch (e: Exception) {
            AppLogger.e("获取 WakeLock 失败", e)
        }
    }
    
    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            acquireWakeLock()
        } catch (e: Exception) {
            AppLogger.e("续期 WakeLock 失败", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    AppLogger.i("WakeLock 已释放")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            AppLogger.e("释放 WakeLock 失败", e)
        }
    }
    
    // ========== AlarmManager 心跳（兜底保活） ==========
    // 使用 setExactAndAllowWhileIdle 绕过 Doze 模式
    // setRepeating 在 Android 12+ 是 inexact 的，HyperOS 会吞掉
    // setExactAndAllowWhileIdle 不支持 repeating，需要每次触发后重新调度
    
    private fun scheduleAlarmHeartbeat() {
        try {
            // 先检查精确闹钟权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    AppLogger.e("精确闹钟权限未授权！请用户手动开启")
                    // 可以弹窗引导用户去设置页开启
                    return
                }
            }
            
            scheduleExactAlarm()
            AppLogger.i("AlarmManager 心跳已设置 (setExactAndAllowWhileIdle)")
        } catch (e: Exception) {
            AppLogger.e("设置 AlarmManager 失败", e)
        }
    }
    
    /**
     * 调度一次精确闹钟，HeartbeatReceiver 触发后会重新调度下一次
     */
    fun scheduleExactAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, com.operit.timetracker.service.HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, HEARTBEAT_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = android.os.SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: Exception) {
            AppLogger.e("调度精确闹钟失败", e)
        }
    }
    
    private fun cancelAlarmHeartbeat() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, com.operit.timetracker.service.HeartbeatReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, 9999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            AppLogger.i("AlarmManager 心跳已取消")
        } catch (e: Exception) {
            AppLogger.e("取消 AlarmManager 失败", e)
        }
    }
    
    private fun isMonitorLocked(): Boolean {
        val state = dataStore.loadMonitorState()
        return state?.locked ?: false
    }
}