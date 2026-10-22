package vn.nlinh.doctruyen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dịch vụ chạy nền: tải chương → đọc từng đoạn bằng TTS → hết chương tự sang chương sau.
 * Chạy dạng "foreground service" nên vẫn đọc khi tắt màn hình.
 */
class ReaderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "start"     // EXTRA_URL, EXTRA_INDEX
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val ACTION_SLEEP = "sleep"     // EXTRA_MINUTES: 0 = tắt, -1 = hết chương này

        const val EXTRA_URL = "url"
        const val EXTRA_INDEX = "index"
        const val EXTRA_MINUTES = "minutes"

        private const val CHANNEL_ID = "reader"
        private const val NOTIF_ID = 1
        private const val MAX_CHUNK = 1500

        fun send(context: Context, action: String, extras: Bundle? = null) {
            val i = Intent(context, ReaderService::class.java).setAction(action)
            if (extras != null) i.putExtras(extras)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingAction: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private val cache = HashMap<String, Chapter>()

    private var chapter: Chapter? = null
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var playing = false
    private var generation = 0
    private var errorCount = 0
    private var appliedVoice = ""

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    private var sleepAtMs = 0L
    private var sleepEndOfChapter = false
    private val sleepRunnable = Runnable {
        sleepAtMs = 0
        pause()
        setState { copy(status = "Đã tự tắt theo hẹn giờ", sleepInfo = "") }
    }

    // ---------------- Vòng đời ----------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            setState { copy(status = "Không khởi động được bộ đọc (TTS). Hãy cài Google Text-to-Speech.") }
            return
        }
        val res = tts.setLanguage(Locale("vi", "VN"))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            setState { copy(status = "Máy chưa có giọng tiếng Việt. Bấm \"Cài giọng\" để tải.") }
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { onChunkDone(utteranceId.orEmpty(), ok = true) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { onChunkDone(utteranceId.orEmpty(), ok = false) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { onChunkDone(utteranceId.orEmpty(), ok = false) }
            }
        })
        ttsReady = true
        pendingAction?.invoke()
        pendingAction = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Luôn phải gọi startForeground ngay khi được khởi động.
        goForeground()
        if (intent == null) return START_NOT_STICKY
        val action = intent.action ?: return START_NOT_STICKY
        val run: () -> Unit = {
            when (action) {
                ACTION_START -> {
                    val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                    val idx = intent.getIntExtra(EXTRA_INDEX, 0)
                    if (url.isNotBlank()) loadChapter(url, idx, autoplay = true)
                }
                ACTION_TOGGLE -> if (playing) pause() else resume()
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_NEXT -> chapter?.let { goToChapter(it.nextUrl, "Đã là chương cuối") }
                ACTION_PREV -> chapter?.let { goToChapter(it.prevUrl, "Đã là chương đầu") }
                ACTION_STOP -> stopAll()
                ACTION_SLEEP -> setSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
                else -> {}
            }
        }
        if (ttsReady || action == ACTION_STOP || action == ACTION_SLEEP) {
            run()
        } else {
            pendingAction = run
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { tts.stop(); tts.shutdown() }
        releaseWakeLock()
        abandonFocus()
        unregisterNoisy()
        setState { UiState(status = "Đã dừng", url = prefs.savedUrl) }
        super.onDestroy()
    }

    // ---------------- Tải chương ----------------

    private fun goToChapter(url: String?, emptyMsg: String) {
        if (url == null) {
            setState { copy(status = emptyMsg) }
            return
        }
        loadChapter(url, 0, autoplay = true)
    }

    private fun loadChapter(url: String, startIndex: Int, autoplay: Boolean) {
        stopSpeaking()
        loadJob?.cancel()
        setState { copy(loading = true, active = true, status = "Đang tải chương…", url = url) }
        updateNotification()
        loadJob = scope.launch {
            try {
                val ch = cache.remove(url) ?: fetchWithRetry(url)
                chapter = ch
                chunks = buildChunks(ch)
                index = startIndex.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
                errorCount = 0
                prefs.saveProgress(ch.url, index, ch.title)
                setState {
                    copy(
                        loading = false, title = ch.title, url = ch.url,
                        index = index, total = chunks.size,
                        current = chunks.getOrElse(index) { "" },
                        status = if (autoplay) "Đang đọc" else "Tạm dừng",
                    )
                }
                if (autoplay) play() else updateNotification()
                prefetch(ch.nextUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChapterFetcher.EndOfStoryException) {
                finishStory(e.message ?: "Hết truyện")
            } catch (e: Exception) {
                playing = false
                releaseWakeLock()
                setState {
                    copy(loading = false, playing = false,
                        status = "Lỗi tải chương: ${e.message ?: e.javaClass.simpleName}. Bấm Đọc để thử lại.")
                }
                updateNotification()
            }
        }
    }

    private suspend fun fetchWithRetry(url: String): Chapter {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return withContext(Dispatchers.IO) { ChapterFetcher.fetch(url) }
            } catch (e: ChapterFetcher.EndOfStoryException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(2000L * (attempt + 1))
            }
        }
        throw last ?: java.io.IOException("Không tải được")
    }

    private fun prefetch(url: String?) {
        if (url == null || cache.containsKey(url)) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ChapterFetcher.fetch(url) } }
                .onSuccess { cache.clear(); cache[url] = it }
        }
    }

    private fun buildChunks(ch: Chapter): List<String> {
        val out = ArrayList<String>()
        out.add(ch.title.ifBlank { "Chương mới" } + ".")
        for (p in ch.paragraphs) {
            if (p.length <= MAX_CHUNK) {
                out.add(p)
                continue
            }
            // Đoạn quá dài: cắt theo câu
            val sb = StringBuilder()
            for (sentence in p.split(Regex("(?<=[.!?…])\\s+"))) {
                if (sb.length + sentence.length > MAX_CHUNK && sb.isNotEmpty()) {
                    out.add(sb.toString()); sb.clear()
                }
                if (sentence.length > MAX_CHUNK) {
                    sentence.chunked(MAX_CHUNK).forEach { out.add(it) }
                } else {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(sentence)
                }
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }
        return out
    }

    // ---------------- Đọc ----------------

    private fun play() {
        if (chunks.isEmpty()) return
        if (!requestFocus()) {
            setState { copy(status = "Không lấy được loa (đang có cuộc gọi?)") }
            return
        }
        playing = true
        acquireWakeLock()
        registerNoisy()
        speakCurrent()
    }

    private fun speakCurrent() {
        if (!playing || index >= chunks.size) return
        applyVoiceSettings()
        val text = chunks[index]
        prefs.saveProgress(chapter?.url ?: "", index, chapter?.title ?: "")
        setState {
            copy(playing = true, loading = false, index = index, total = chunks.size,
                current = text, status = "Đang đọc")
        }
        updateNotification()
        generation++
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$generation:$index")
    }

    private fun onChunkDone(utteranceId: String, ok: Boolean) {
        if (utteranceId != "$generation:$index" || !playing) return
        if (!ok) {
            errorCount++
            if (errorCount >= 5) {
                pause()
                setState { copy(status = "Bộ đọc bị lỗi nhiều lần. Thử đổi giọng khác (giọng \"cần mạng\" sẽ lỗi khi mất mạng).") }
                return
            }
        } else {
            errorCount = 0
        }
        index++
        if (index < chunks.size) {
            speakCurrent()
        } else {
            onChapterFinished()
        }
    }

    private fun onChapterFinished() {
        val next = chapter?.nextUrl
        if (next == null) {
            finishStory("Hết truyện")
            return
        }
        if (sleepEndOfChapter) {
            sleepEndOfChapter = false
            playing = false
            releaseWakeLock()
            loadChapter(next, 0, autoplay = false)
            setState { copy(sleepInfo = "") }
            return
        }
        loadChapter(next, 0, autoplay = true)
    }

    private fun finishStory(msg: String) {
        playing = false
        setState { copy(loading = false, playing = false, status = msg) }
        tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "end")
        releaseWakeLock()
        updateNotification()
    }

    private fun applyVoiceSettings() {
        tts.setSpeechRate(prefs.rate)
        tts.setPitch(prefs.pitch)
        val name = prefs.voiceName
        if (name != appliedVoice) {
            appliedVoice = name
            if (name.isEmpty()) {
                tts.setLanguage(Locale("vi", "VN"))
            } else {
                runCatching { tts.voices?.firstOrNull { it.name == name } }
                    .getOrNull()?.let { tts.setVoice(it) }
            }
        }
    }

    private fun stopSpeaking() {
        generation++
        runCatching { tts.stop() }
    }

    private fun pause() {
        playing = false
        stopSpeaking()
        releaseWakeLock()
        unregisterNoisy()
        chapter?.let { prefs.saveProgress(it.url, index, it.title) }
        setState { copy(playing = false, status = "Tạm dừng") }
        updateNotification()
    }

    private fun resume() {
        if (chunks.isEmpty()) {
            val url = prefs.savedUrl
            if (url.isNotBlank()) loadChapter(url, prefs.savedIndex, autoplay = true)
            return
        }
        if (index >= chunks.size) index = chunks.size - 1
        play()
    }

    private fun stopAll() {
        pause()
        setSleep(0)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- Hẹn giờ ----------------

    private fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        sleepAtMs = 0
        sleepEndOfChapter = false
        val info = when {
            minutes > 0 -> {
                sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
                handler.postDelayed(sleepRunnable, minutes * 60_000L)
                "Tự tắt sau $minutes phút"
            }
            minutes < 0 -> {
                sleepEndOfChapter = true
                "Tự tắt khi hết chương này"
            }
            else -> ""
        }
        setState { copy(sleepInfo = info) }
    }

    // ---------------- Âm thanh ----------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (playing) pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playing) {
                    resumeOnFocusGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    /** Rút tai nghe → tạm dừng. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocTruyen:reader")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ---------------- Thông báo ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Đọc truyện", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, ReaderService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, code, i, flags)
        } else {
            PendingIntent.getService(this, code, i, flags)
        }
    }

    private fun buildNotification(): Notification {
        val s = ReaderState.state.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val progress = if (s.total > 0) " · ${s.index + 1}/${s.total}" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(s.title.ifBlank { "Đọc Truyện" })
            .setContentText(s.status + progress)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "⏮ Trước", actionIntent(ACTION_PREV, 1))
            .addAction(0, if (playing) "⏸ Dừng" else "▶ Đọc", actionIntent(ACTION_TOGGLE, 2))
            .addAction(0, "Sau ⏭", actionIntent(ACTION_NEXT, 3))
            .setDeleteIntent(actionIntent(ACTION_STOP, 4))
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    private inline fun setState(crossinline f: UiState.() -> UiState) {
        ReaderState.state.update { it.f() }
    }
}
