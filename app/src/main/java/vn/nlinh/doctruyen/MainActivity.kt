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
import kotlinx.coroutines.launch
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

        askNotificationPermission()
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
    }

    private fun sendIfActive(action: String) {
        if (ReaderState.state.value.active) ReaderService.send(this, action)
        else toast("Chưa đọc chương nào")
    }

    @SuppressLint("SetTextI18n")
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
