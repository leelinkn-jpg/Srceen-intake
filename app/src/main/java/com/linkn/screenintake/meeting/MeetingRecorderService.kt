package com.linkn.screenintake.meeting

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.linkn.screenintake.R
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.capture.ScreenIntakeAccessibilityService
import com.linkn.screenintake.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

data class MeetingLiveState(val busy: Boolean = false, val recording: Boolean = false,
    val id: String? = null, val message: String = "", val elapsedMs: Long = 0, val level: Float = 0f)

/** 用户在可见页面启动；只在录音/上传期间显示前台通知，不抢音频焦点、不切会议音频路由。 */
class MeetingRecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var working = false
    private var microphoneMode = false
    private lateinit var repo: MeetingRepository

    override fun onCreate() {
        super.onCreate()
        repo = MeetingRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "会议录音与同步", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { stopRequested.set(true); if (!working) stopSelf() }
            CANCEL -> { cancelRequested.set(true); if (!working) stopSelf() }
            RECORD, EXPORT -> {
                if (working) return START_NOT_STICKY
                working = true
                microphoneMode = intent.action == RECORD
                stopRequested.set(false); cancelRequested.set(false)
                mutableState.value = MeetingLiveState(busy = true, recording = microphoneMode, message = "准备中…")
                try {
                    startForeground(NOTIFICATION_ID, notification("准备中…"),
                        if (microphoneMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e: Exception) {
                    mutableState.value = MeetingLiveState(message = "启动失败，请回到会议记录页面并允许麦克风权限")
                    working = false; stopSelf(); return START_NOT_STICKY
                }
                val action = intent.action
                val requestedId = intent.getStringExtra("id")
                val testOnly = intent.getBooleanExtra("test", false)
                scope.launch {
                    var current: MeetingRecord? = null
                    try {
                        if (action == RECORD) {
                            require(ContextCompat.checkSelfPermission(this@MeetingRecorderService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
                            require(ScreenIntakeAccessibilityService.isRunning()) { "请先开启秒记无障碍服务，再进行会中收音测试" }
                            val folder = ScreenIntakeApp.instance.settingsStore.folderUri
                            require(folder.isNotBlank()) { "请先在设置中选择保存文件夹" }
                            current = repo.create(folder, testOnly)
                            current = record(current)
                            microphoneMode = false
                            startForeground(NOTIFICATION_ID, notification("正在保存录音…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                            current = repo.export(current)
                            if (!testOnly && (current.audibleMs < 1000 || current.silencedMs > 0L)) {
                                current = current.copy(error = "录音存在静音或收音异常；原始录音已同步，Mini 仍可继续处理")
                                repo.save(current)
                            }
                        } else {
                            current = repo.read(requireNotNull(requestedId))
                            current = repo.export(current)
                        }
                        mutableState.value = mutableState.value.copy(recording = false, level = 0f,
                            message = if (current?.status == "ready") "已收到 Mini 转录结果" else "录音已同步，等待 Mac mini 转录")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MeetingRecorderService,
                                if (current?.status == "ready") "会议转录已完成" else "录音已保存，等待 Mini 转录",
                                Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        val reason = if (cancelRequested.get()) "保存已取消，可稍后重新同步" else e.message ?: "任务中断，可稍后重试"
                        current?.let {
                            val latest = runCatching { repo.read(it.id) }.getOrDefault(it)
                            val duration = runCatching { WavAudio.repair(repo.audio(it.id)) }.getOrDefault(latest.durationMs)
                            val failed = latest.copy(status = if (latest.status == "recording") "interrupted" else "failed",
                                durationMs = maxOf(duration, latest.durationMs), error = reason, synced = false)
                            runCatching { repo.save(failed); repo.saveTranscript(failed) }
                        }
                        mutableState.value = mutableState.value.copy(recording = false, level = 0f, message = reason)
                    } finally {
                        ScreenIntakeAccessibilityService.showMeetingRecordingStatus(null)
                        withContext(Dispatchers.Main + NonCancellable) {
                            // 等销毁回调再开放下一次操作，避免旧任务收尾时停止刚启动的新任务。
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
            else -> if (!working) stopSelf()
        }
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission", "WakelockTimeout")
    private fun record(initial: MeetingRecord): MeetingRecord {
        val min = AudioRecord.getMinBufferSize(WavAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "设备不支持当前录音格式" }
        // 企业微信等 VoIP 会话会以 VOICE_COMMUNICATION 占用普通 MIC。
        // 无障碍服务已启用时，VOICE_RECOGNITION 是 Android 为语音辅助场景
        // 提供的共享收音路径；它采集的是实际麦克风声，不是受保护的通话下行音频。
        // 部分 ROM 会拒绝 VOICE_RECOGNITION。先尝试它；初始化失败时退回 MIC，
        // 至少保证录音流程能启动，并继续用 isClientSilenced 给出真实结果。
        fun build(source: Int) = AudioRecord.Builder().setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(WavAudio.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 2, 6400)).setPrivacySensitive(false).build()
        var sourceName = "语音识别收音"
        var audio = build(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audio.release() }
            audio = build(MediaRecorder.AudioSource.MIC)
            sourceName = "兼容麦克风收音"
        }
        recorder = audio
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenintake:meeting")
        var current = initial
        val startClock = android.os.SystemClock.elapsedRealtime()
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            wakeLock.acquire(MAX_RECORD_MS + 60000)
            runCatching { audio.startRecording() }
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING && sourceName == "语音识别收音") {
                // 有些设备直到 startRecording 才拒绝 VOICE_RECOGNITION；此时立即回退，
                // 不能让用户只看到一个没有反馈的按钮。
                runCatching { audio.stop() }; audio.release()
                audio = build(MediaRecorder.AudioSource.MIC)
                sourceName = "兼容麦克风收音"
                recorder = audio
                audio.startRecording()
            }
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统未允许开始录音，请确认麦克风权限和无障碍服务仍开启" }
            val data = ByteArray(3200)
            val autoGain = WavAudio.SpeechAutoGain()
            var bytes = 0L
            var lastUpdate = -1000L
            RandomAccessFile(repo.audio(initial.id), "rw").use { out ->
                out.setLength(0); out.write(WavAudio.header(0))
                while (!stopRequested.get()) {
                    scope.ensureActive()
                    check(android.os.SystemClock.elapsedRealtime() - startClock < MAX_RECORD_MS + 60000) {
                        "已达到单次录音时长上限，录音已保留"
                    }
                    check(ScreenIntakeAccessibilityService.isRunning()) { "无障碍服务已关闭，录音已停止" }
                    val count = audio.read(data, 0, data.size, AudioRecord.READ_BLOCKING)
                    check(count >= 0) { "麦克风读取失败（$count），已保留之前录音" }
                    if (count == 0) continue
                    val rawRms = WavAudio.rms(data, count)
                    autoGain.process(data, count)
                    out.write(data, 0, count); bytes += count
                    val elapsed = bytes * 1000 / WavAudio.BYTES_PER_SECOND
                    val rms = WavAudio.rms(data, count)
                    val config = audio.activeRecordingConfiguration
                    val silenced = config?.isClientSilenced == true
                    val frameMs = count * 1000L / WavAudio.BYTES_PER_SECOND
                    current = current.copy(durationMs = elapsed,
                        silencedMs = current.silencedMs + if (silenced) frameMs else 0,
                        audibleMs = current.audibleMs + if (!silenced && rawRms > 0.002) frameMs else 0,
                        inputDevice = "$sourceName（动态增益） · " +
                            (config?.audioDevice?.productName?.toString() ?: "系统默认麦克风"))
                    if (elapsed - lastUpdate >= 1000) {
                        lastUpdate = elapsed
                        out.seek(0); out.write(WavAudio.header(bytes)); out.seek(WavAudio.HEADER_SIZE + bytes)
                        repo.save(current)
                        val status = when {
                            silenced -> "系统正在静音录音，本段没有有效声音"
                            rms < 0.002 -> "声音较弱，请检查外放和收音"
                            else -> "收到声音 · 仍需试听确认双方发言"
                        }
                        mutableState.value = MeetingLiveState(true, true, initial.id, status, elapsed,
                            (rms * 8).coerceIn(0.0, 1.0).toFloat())
                        updateNotification("${MeetingRecord.clock(elapsed)} · $status")
                        ScreenIntakeAccessibilityService.showMeetingRecordingStatus(
                            "会议 ${MeetingRecord.clock(elapsed)}\n${if (silenced) "⚠ 被系统静音" else "录音中"} · 点此结束")
                    }
                    if (elapsed >= (if (initial.testOnly) 60000 else MAX_RECORD_MS)) break
                }
                out.seek(0); out.write(WavAudio.header(bytes)); out.fd.sync()
            }
            require(current.durationMs >= 500) { "录音太短，请重新录制" }
            current = current.copy(status = if (initial.testOnly) "recorded" else "pending_local")
            repo.save(current)
            return current
        } finally {
            runCatching { audio.stop() }; audio.release(); recorder = null
            if (wakeLock.isHeld) wakeLock.release()
            ScreenIntakeAccessibilityService.showMeetingRecordingStatus(null)
            mutableState.value = mutableState.value.copy(recording = false, level = 0f)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 8101,
            Intent(this, MainActivity::class.java).setAction(OPEN_MEETINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = if (microphoneMode) STOP else CANCEL
        val stop = PendingIntent.getService(this, 8102, Intent(this, javaClass).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (microphoneMode) "会议录音" else "会议录音同步")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_media_pause, if (microphoneMode) "结束录音" else "取消保存", stop).build()
    }
    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelRequested.set(true); stopRequested.set(true)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        stopRequested.set(true); cancelRequested.set(true)
        runCatching { recorder?.stop() }
        scope.cancel()
        working = false
        ScreenIntakeAccessibilityService.showMeetingRecordingStatus(null)
        mutableState.value = mutableState.value.copy(busy = false, recording = false, level = 0f)
        super.onDestroy()
    }
    companion object {
        const val RECORD = "com.linkn.screenintake.meeting.RECORD"
        const val STOP = "com.linkn.screenintake.meeting.STOP"
        const val EXPORT = "com.linkn.screenintake.meeting.EXPORT"
        const val CANCEL = "com.linkn.screenintake.meeting.CANCEL"
        const val OPEN_MEETINGS = "com.linkn.screenintake.meeting.OPEN"
        private const val CHANNEL = "meeting_recording"
        private const val NOTIFICATION_ID = 8100
        private const val MAX_RECORD_MS = 3 * 60 * 60 * 1000L
        private val mutableState = MutableStateFlow(MeetingLiveState())
        val state = mutableState.asStateFlow()
        fun start(context: Context, test: Boolean = false) {
            // 服务调度前就给界面明确反馈，避免按钮点击后看起来没有任何反应。
            mutableState.value = MeetingLiveState(busy = true, recording = true, message = "正在请求录音…")
            ContextCompat.startForegroundService(context,
                Intent(context, MeetingRecorderService::class.java).setAction(RECORD).putExtra("test", test))
        }
        fun work(context: Context, action: String, id: String) = ContextCompat.startForegroundService(context,
            Intent(context, MeetingRecorderService::class.java).setAction(action).putExtra("id", id))
    }
}
