package vn.nlinh.doctruyen

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import vn.nlinh.doctruyen.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var previewTts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()

    private val sleepOptions = listOf(
        "Không hẹn giờ" to 0,
        "15 phút" to 15,
        "30 phút" to 30,
        "45 phút" to 45,
        "60 phút" to 60,
        "90 phút" to 90,
        "Hết chương này" to -1,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        Library.init(this)

        askNotificationPermission()
        setupTabs()
        setupUrl()
        setupControls()
        setupVoiceSettings()
        setupSleep()
        observeState()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshSaved()
    }

    override fun onDestroy() {
        previewTts?.shutdown()
        super.onDestroy()
    }

    // ---------------- Link ----------------

    private fun setupUrl() {
        b.etUrl.setText(prefs.savedUrl)
        b.btnPaste.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val url = extractUrl(text)
            if (url == null) toast("Bộ nhớ tạm không có link") else b.etUrl.setText(url)
        }
        b.btnStartUrl.setOnClickListener {
            val url = currentUrl() ?: return@setOnClickListener
            ReaderService.send(this, ReaderService.ACTION_START,
                bundleOf(ReaderService.EXTRA_URL to url, ReaderService.EXTRA_INDEX to 0))
        }
        b.btnResumeSaved.setOnClickListener {
            val url = prefs.savedUrl
            if (url.isBlank()) return@setOnClickListener
            b.etUrl.setText(url)
            ReaderService.send(this, ReaderService.ACTION_START,
                bundleOf(ReaderService.EXTRA_URL to url, ReaderService.EXTRA_INDEX to prefs.savedIndex))
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) ?: return
        b.etUrl.setText(url)
        toast("Đã nhận link. Bấm \"Đọc link này\" để bắt đầu.")
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    private fun currentUrl(): String? {
        val url = extractUrl(b.etUrl.text?.toString().orEmpty())
        if (url == null) toast("Hãy dán link chương truyện (bắt đầu bằng https://)")
        return url
    }

    private fun refreshSaved() {
        val url = prefs.savedUrl
        if (url.isBlank()) {
            b.cardSaved.visibility = View.GONE
        } else {
            b.cardSaved.visibility = View.VISIBLE
            val title = prefs.savedTitle.ifBlank { "Chương đã lưu" }
            b.tvSaved.text = "Đang đọc dở: $title – đoạn ${prefs.savedIndex + 1}"
        }
    }

    // ---------------- Điều khiển ----------------

    private fun setupControls() {
        b.btnPlay.setOnClickListener {
            val s = ReaderState.state.value
            when {
                s.playing -> ReaderService.send(this, ReaderService.ACTION_PAUSE)
                s.total > 0 && extractUrl(b.etUrl.text?.toString().orEmpty()) == s.url ->
                    ReaderService.send(this, ReaderService.ACTION_RESUME)
                else -> {
                    val url = currentUrl() ?: return@setOnClickListener
                    val idx = if (url == prefs.savedUrl) prefs.savedIndex else 0
                    ReaderService.send(this, ReaderService.ACTION_START,
                        bundleOf(ReaderService.EXTRA_URL to url, ReaderService.EXTRA_INDEX to idx))
                }
            }
        }
        b.btnNext.setOnClickListener { sendIfActive(ReaderService.ACTION_NEXT) }
        b.btnPrev.setOnClickListener { sendIfActive(ReaderService.ACTION_PREV) }
        b.btnStop.setOnClickListener { sendIfActive(ReaderService.ACTION_STOP) }
        b.btnBattery.setOnClickListener { askIgnoreBattery() }
        b.btnShareDebug.setOnClickListener { shareDebugPage() }
        b.btnOpenBrowser.setOnClickListener {
            val url = extractUrl(b.etUrl.text?.toString().orEmpty()) ?: prefs.savedUrl
            startActivity(Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, url))
        }
    }

    private fun sendIfActive(action: String) {
        if (ReaderState.state.value.active) ReaderService.send(this, action)
        else toast("Chưa đọc chương nào")
    }

    @SuppressLint("SetTextI18n")
    // ---------------- Tab + Ngăn tủ ----------------

    private fun setupTabs() {
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Library.books, Library.downloads) { books, dl -> books to dl }
                    .collect { (books, dl) -> renderShelf(books, dl) }
            }
        }
    }

    private fun showPage(position: Int) {
        b.readerPage.visibility = if (position == 0) View.VISIBLE else View.GONE
        b.shelfPage.visibility = if (position == 1) View.VISIBLE else View.GONE
        if (position == 1) renderShelf(Library.books.value, Library.downloads.value)
    }

    private fun selectTab(position: Int) {
        b.tabs.getTabAt(position)?.select()
        showPage(position)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renderShelf(books: List<Book>, downloads: Map<String, DownloadInfo>) {
        if (b.shelfPage.visibility != View.VISIBLE) return
        b.tvShelfEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        val list = b.shelfList
        list.removeAllViews()
        for (book in books) list.addView(bookCard(book, downloads[book.id]))
    }

    private fun bookCard(book: Book, dl: DownloadInfo?): View {
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle)
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        box.addView(TextView(this).apply {
            text = book.title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val progress = if (book.chapterUrl.isBlank()) "Chưa đọc"
        else "Đang đọc: ${book.chapterTitle.ifBlank { ChapterFetcher.guessTitleFromUrl(book.chapterUrl) }} – đoạn ${book.index + 1}"
        box.addView(TextView(this).apply {
            text = progress
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        })
        val offline = Library.offlineCount(book.id)
        val dlText = dl?.text.orEmpty()
        box.addView(TextView(this).apply {
            text = buildString {
                append("💾 $offline chương offline")
                if (dlText.isNotBlank()) append("\n").append(dlText)
            }
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(2), 0, dp(6))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { w: Float -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { marginEnd = dp(6) } }
        row.addView(MaterialButton(this).apply {
            text = "▶ Đọc tiếp"
            setOnClickListener { readBook(book) }
        }, lp(1.2f))
        val downloading = dl?.running == true
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (downloading) "⏹ Dừng tải" else "⬇ Tải offline"
            setOnClickListener {
                if (downloading) DownloadService.cancel(this@MainActivity, book.id) else askDownload(book)
            }
        }, lp(1.2f))
        row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "🗑"
            setOnClickListener { askDelete(book, offline) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(row)
        card.addView(box)
        return card
    }

    private fun readBook(book: Book) {
        val url = book.chapterUrl.ifBlank { Library.guessFirstChapter(book.storyUrl) }
        val idx = if (book.chapterUrl.isBlank()) 0 else book.index
        b.etUrl.setText(url)
        ReaderService.send(this, ReaderService.ACTION_START,
            bundleOf(ReaderService.EXTRA_URL to url, ReaderService.EXTRA_INDEX to idx))
        selectTab(0)
    }

    private fun askDownload(book: Book) {
        val first = Library.guessFirstChapter(book.storyUrl, book.chapterUrl.ifBlank { null })
        val options = mutableListOf<Pair<String, String>>()
        if (book.chapterUrl.isNotBlank()) {
            options.add("Từ chương đang đọc (${ChapterFetcher.guessTitleFromUrl(book.chapterUrl)}) đến hết" to book.chapterUrl)
        }
        options.add("Từ chương 1 đến hết" to first)
        MaterialAlertDialogBuilder(this)
            .setTitle("Tải offline: ${book.title}")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                DownloadService.start(this, book.id, options[which].second)
                toast("Bắt đầu tải. Xem tiến độ ở thanh thông báo hoặc trong Ngăn tủ.")
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun askDelete(book: Book, offline: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Xoá \"${book.title}\"?")
            .setMessage("Xoá khỏi Ngăn tủ" + if (offline > 0) " và xoá $offline chương đã tải offline." else ".")
            .setPositiveButton("Xoá") { _, _ ->
                if (Library.downloads.value[book.id]?.running == true) {
                    DownloadService.cancel(this, book.id)
                }
                Library.removeBook(book.id)
                Library.setDownload(book.id, null)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ReaderState.state.collect { s ->
                    b.tvStatus.text = if (s.sleepInfo.isNotBlank()) "${s.status} · ${s.sleepInfo}" else s.status
                    b.tvTitle.text = s.title
                    b.tvTitle.visibility = if (s.title.isBlank()) View.GONE else View.VISIBLE
                    b.tvProgress.text = if (s.total > 0) "Đoạn ${s.index + 1}/${s.total}" else ""
                    b.progress.isIndeterminate = s.loading
                    if (!s.loading) {
                        b.progress.setProgressCompat(
                            if (s.total > 0) (s.index + 1) * 100 / s.total else 0, true)
                    }
                    b.tvCurrent.text = s.current
                    b.btnPlay.text = if (s.playing) "⏸ Dừng" else "▶ Đọc"
                    if (s.url.isNotBlank() && s.active && !b.etUrl.hasFocus()) {
                        if (b.etUrl.text?.toString() != s.url) b.etUrl.setText(s.url)
                    }
                    refreshSaved()
                }
            }
        }
    }

    // ---------------- Giọng đọc ----------------

    private fun setupVoiceSettings() {
        val rateProgress = ((prefs.rate - 0.5f) / 0.1f).toInt().coerceIn(0, b.sbRate.max)
        b.sbRate.progress = rateProgress
        b.tvRate.text = "Tốc độ: %.1fx".format(prefs.rate)
        b.sbRate.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.rate = 0.5f + p * 0.1f
            b.tvRate.text = "Tốc độ: %.1fx".format(prefs.rate)
        })

        val pitchProgress = ((prefs.pitch - 0.5f) / 0.1f).toInt().coerceIn(0, b.sbPitch.max)
        b.sbPitch.progress = pitchProgress
        b.tvPitch.text = "Cao độ: %.1f".format(prefs.pitch)
        b.sbPitch.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.pitch = 0.5f + p * 0.1f
            b.tvPitch.text = "Cao độ: %.1f".format(prefs.pitch)
        })

        b.btnTtsSettings.setOnClickListener {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }

        b.btnTest.setOnClickListener {
            val t = previewTts ?: return@setOnClickListener
            t.setSpeechRate(prefs.rate)
            t.setPitch(prefs.pitch)
            voices.firstOrNull { it.name == prefs.voiceName }?.let { t.setVoice(it) }
                ?: t.setLanguage(Locale("vi", "VN"))
            t.speak("Xin chào, đây là giọng đọc truyện. Chương một, tổ chức sát thủ.",
                TextToSpeech.QUEUE_FLUSH, null, "test")
        }

        var tts: TextToSpeech? = null
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runOnUiThread { loadVoices(tts!!) }
            } else {
                runOnUiThread { toast("Không mở được bộ đọc TTS của máy") }
            }
        }
        previewTts = tts
    }

    private fun loadVoices(tts: TextToSpeech) {
        voices = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == "vi" }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))

        val labels = mutableListOf("Mặc định của máy")
        voices.forEachIndexed { i, v ->
            val where = if (v.isNetworkConnectionRequired) "cần mạng" else "offline"
            labels.add("Giọng ${i + 1} ($where) – ${v.name}")
        }
        if (voices.isEmpty()) labels[0] = "Mặc định (máy chưa có giọng tiếng Việt riêng)"

        b.spVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selected = voices.indexOfFirst { it.name == prefs.voiceName }
        b.spVoice.setSelection(if (selected >= 0) selected + 1 else 0, false)
        b.spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.voiceName = if (pos == 0) "" else voices[pos - 1].name
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------------- Hẹn giờ ----------------

    private fun setupSleep() {
        b.spSleep.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, sleepOptions.map { it.first })
        b.spSleep.setSelection(0, false)
        b.spSleep.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!ReaderState.state.value.active) {
                    if (pos != 0) toast("Hãy bắt đầu đọc rồi mới hẹn giờ")
                    b.spSleep.setSelection(0, false)
                    return
                }
                ReaderService.send(this@MainActivity, ReaderService.ACTION_SLEEP,
                    bundleOf(ReaderService.EXTRA_MINUTES to sleepOptions[pos].second))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------------- Quyền ----------------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Đã cho phép chạy nền rồi")
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Gửi HTML của trang vừa tải (để người hỗ trợ xem vì sao không đọc được). */
    private fun shareDebugPage() {
        val file = java.io.File(cacheDir, "trang_vua_tai.txt")
        if (!file.exists()) {
            toast("Chưa có trang nào được tải. Hãy bấm Đọc với link bị lỗi trước.")
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Trang lỗi – Đọc Truyện")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Gửi trang lỗi"))
    }

    // ---------------- Tiện ích ----------------

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
