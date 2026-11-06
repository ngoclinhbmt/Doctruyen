package vn.nlinh.doctruyen

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import com.google.android.material.button.MaterialButton

/**
 * Trình duyệt hiện trên màn hình, dùng chung cookie với trình duyệt ẩn của app.
 * Dùng để đăng nhập web truyện hoặc qua trang kiểm tra "Just a moment" một lần,
 * sau đó app tự tải chương được.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var web: WebView
    private lateinit var tvUrl: TextView
    private lateinit var progress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (8 * resources.displayMetrics.density).toInt()

        tvUrl = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            alpha = 0.7f
            setPadding(pad, pad / 2, pad, 0)
        }
        val btnRead = MaterialButton(this).apply {
            text = "Đọc từ trang này"
            setOnClickListener { readCurrent() }
        }
        val btnClose = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Đóng"
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(btnRead, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = pad
            })
            addView(btnClose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val hint = TextView(this).apply {
            text = "Đăng nhập hoặc qua bước kiểm tra nếu web yêu cầu, rồi bấm \"Đọc từ trang này\"."
            textSize = 13f
            setPadding(pad, pad / 2, pad, 0)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        web = WebView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(hint)
            addView(tvUrl)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad / 2))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tvUrl.text = url.orEmpty()
                CookieManager.getInstance().flush()
                // Chờ trang hiện xong (web dùng JavaScript) rồi mới nhận diện truyện
                web.postDelayed({ detectBook() }, 1500)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.alpha = if (newProgress >= 100) 0f else 1f
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        val url = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { "https://www.google.com" }
        tvUrl.text = url
        web.loadUrl(url)
    }

    /**
     * Tự thêm truyện vào Ngăn tủ khi:
     *  - đang mở trang giới thiệu truyện (có nhiều link .../ten-truyen/chuong-N), hoặc
     *  - đang mở một chương truyện.
     */
    private fun detectBook() {
        if (isFinishing) return
        val js = """
            (function(){
              var u = location.href.split('#')[0].split('?')[0];
              var chapRe = /(chuong|chapter|chap)-\d+/i;
              var isChap = chapRe.test(u);
              var base = isChap ? u.replace(/\/?(chuong|chapter|chap)-\d+.*${'$'}/i, '/') : (u.endsWith('/') ? u : u + '/');
              var links = Array.prototype.map.call(document.querySelectorAll('a[href]'), function(a){ return a; });
              var chaps = links.filter(function(a){ return a.href.indexOf(base) === 0 && chapRe.test(a.href); });
              var first = chaps.map(function(a){ return a.href; })
                  .find(function(h){ return /(chuong|chapter|chap)-1(\D|${'$'})/i.test(h); }) || '';
              var storyLink = links.find(function(a){ return a.href.replace(/\/${'$'}/, '') === base.replace(/\/${'$'}/, ''); });
              var h1 = document.querySelector('h1');
              var title = (storyLink && storyLink.innerText.trim().length > 3 && !chapRe.test(storyLink.innerText) ? storyLink.innerText.trim()
                          : (h1 ? h1.innerText.trim() : document.title));
              return JSON.stringify({isChap: isChap, base: base, count: chaps.length, first: first, title: title});
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val o = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
            }.getOrNull() ?: return@evaluateJavascript
            val isChap = o.optBoolean("isChap")
            val count = o.optInt("count")
            val base = o.optString("base")
            // Trang truyện cần có ít nhất 3 link chương; trang chương thì luôn nhận
            if (!isChap && count < 3) return@evaluateJavascript
            if (base.count { it == '/' } < 4) return@evaluateJavascript // chỉ là trang chủ
            val title = o.optString("title").lines().firstOrNull().orEmpty().trim().take(150)
            val first = if (isChap) (web.url ?: "") else o.optString("first")
            Library.init(this)
            if (Library.addBook(base, title, first)) {
                Toast.makeText(this, "📚 Đã thêm vào Ngăn tủ: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readCurrent() {
        val url = web.url ?: return
        CookieManager.getInstance().flush()
        Prefs(this).savedUrl = url
        ReaderService.send(
            this, ReaderService.ACTION_START,
            bundleOf(ReaderService.EXTRA_URL to url, ReaderService.EXTRA_INDEX to 0)
        )
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
