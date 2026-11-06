package vn.nlinh.doctruyen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import kotlinx.coroutines.launch

/**
 * Tải các chương của một truyện về máy để đọc offline.
 * Bắt đầu từ một chương, cứ theo link "chương sau" cho đến hết truyện.
 * Mỗi lần chỉ tải một truyện; bấm tải truyện khác thì xếp hàng chờ.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "dl_start"   // EXTRA_BOOK, EXTRA_URL
        const val ACTION_CANCEL = "dl_cancel" // EXTRA_BOOK (rỗng = huỷ tất cả)
        const val EXTRA_BOOK = "book"
        const val EXTRA_URL = "url"

        private const val CHANNEL_ID = "download"
        private const val NOTIF_ID = 2
        /** Nghỉ giữa 2 chương để không làm quá tải web */
        private const val PAUSE_MS = 1200L
        private const val MAX_FAILS = 3

        fun start(context: Context, bookId: String, startUrl: String) {
            val i = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BOOK, bookId)
                .putExtra(EXTRA_URL, startUrl)
            ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context, bookId: String) {
            val i = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_BOOK, bookId)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var loader: ChapterLoader
    private val queue = ArrayDeque<Pair<String, String>>() // (bookId, startUrl)
    private var currentBook: String? = null
    private var worker: Job? = null
    private var bookJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notifText = "Đang chuẩn bị…"
    private var notifTitle = "Tải truyện offline"

    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        loader = ChapterLoader(this, WebPageLoader(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Tải truyện", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent == null) return START_NOT_STICKY
        val book = intent.getStringExtra(EXTRA_BOOK).orEmpty()
        when (intent.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val already = currentBook == book || queue.any { it.first == book }
                if (book.isNotBlank() && url.isNotBlank() && !already) {
                    queue.addLast(book to url)
                    Library.setDownload(book, DownloadInfo(running = true, text = "Đang chờ tải…"))
                }
                if (worker?.isActive != true) runQueue()
            }
            ACTION_CANCEL -> {
                if (book.isBlank()) {
                    queue.forEach { Library.setDownload(it.first, null) }
                    queue.clear()
                    bookJob?.cancel()
                } else {
                    queue.removeAll { it.first == book }
                    Library.setDownload(book, null)
                    if (currentBook == book) bookJob?.cancel()
                }
                if (worker?.isActive != true && queue.isEmpty()) stopAll()
            }
            else -> if (worker?.isActive != true && queue.isEmpty()) stopAll()
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        worker = scope.launch {
            acquireWakeLock()
            while (queue.isNotEmpty()) {
                val (book, url) = queue.removeFirst()
                currentBook = book
                val job = launch { downloadBook(book, url) }
                bookJob = job
                job.join()
                if (job.isCancelled) {
                    Library.setDownload(book, DownloadInfo(false,
                        "Đã dừng tải · có ${Library.offlineCount(book)} chương offline"))
                }
                currentBook = null
                bookJob = null
            }
            stopAll()
        }
    }

    private suspend fun downloadBook(bookId: String, startUrl: String) {
        val title = Library.find(bookId)?.title.orEmpty().ifBlank { "Truyện" }
        notifTitle = "Tải offline: $title"
        var url: String? = startUrl
        var saved = 0
        var fails = 0
        var lastTitle = ""
        val seen = HashSet<String>()
        var result = ""

        while (url != null) {
            if (!seen.add(url.trimEnd('/'))) break // tránh vòng lặp link
            val existing = Library.loadOffline(url)
            if (existing != null) {
                lastTitle = existing.title
                url = existing.nextUrl
                continue
            }
            report(bookId, "Đang tải: ${ChapterFetcher.guessTitleFromUrl(url)} · đã tải thêm $saved chương")
            try {
                val ch = loader.fetch(url)
                Library.saveOffline(ch)
                saved++
                fails = 0
                lastTitle = ch.title
                url = ch.nextUrl
                report(bookId, "Đã tải: ${ch.title} · thêm $saved chương")
                delay(PAUSE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChapterFetcher.EndOfStoryException) {
                result = "Đã tải hết"
                break
            } catch (e: Exception) {
                fails++
                if (fails >= MAX_FAILS) {
                    result = "Dừng ở ${ChapterFetcher.guessTitleFromUrl(url)}: ${e.message ?: "lỗi"}. " +
                        "Mở trang trong app (đăng nhập) rồi bấm Tải tiếp."
                    break
                }
                report(bookId, "Lỗi tải, thử lại (lần $fails/$MAX_FAILS)…")
                delay(5_000L * fails)
            }
        }
        if (result.isEmpty()) result = "Đã tải hết"
        val total = Library.offlineCount(bookId)
        val summary = "$result · có $total chương offline" +
            (if (lastTitle.isNotBlank()) " (tới $lastTitle)" else "")
        Library.setDownload(bookId, DownloadInfo(running = false, text = summary))
        notifText = summary
    }

    private fun report(bookId: String, text: String) {
        Library.setDownload(bookId, DownloadInfo(running = true, text = text))
        notifText = text
        updateNotification()
    }

    // ---------------- Thông báo / dịch vụ ----------------

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_book)
        .setContentTitle(notifTitle)
        .setContentText(notifText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(notifText))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0, "Dừng tải",
            PendingIntent.getService(
                this, 11,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocTruyen:download")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(6 * 60 * 60 * 1000L)
    }

    private fun stopAll() {
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
