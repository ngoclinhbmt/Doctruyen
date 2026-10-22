package vn.nlinh.doctruyen

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Cách 2: mở trang bằng trình duyệt ẩn (WebView) giống Chrome, chờ JavaScript chạy xong
 * rồi lấy HTML. Dùng cho web chặn tải trực tiếp hoặc chỉ hiện chữ sau khi chạy JavaScript.
 * Phải gọi trên luồng chính (Main).
 */
class WebPageLoader(private val context: Context) {

    data class Result(val html: String, val finalUrl: String)

    suspend fun load(url: String, timeoutMs: Long = 35_000): Result = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val wv = createWebView()
            var polling = false
            var finished = false
            var lastLen = -1

            fun finish(result: Result) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                wv.stopLoading()
                wv.destroy()
                if (cont.isActive) cont.resume(result)
            }

            fun grabHtml() {
                wv.evaluateJavascript("document.documentElement.outerHTML") { json ->
                    finish(Result(decodeJs(json), wv.url ?: url))
                }
            }

            fun poll(attempt: Int) {
                if (finished) return
                wv.evaluateJavascript(
                    "(function(){var b=document.body;return (b?b.innerText.length:0)+'|'+document.title;})()"
                ) { json ->
                    val raw = decodeJs(json)
                    val len = raw.substringBefore('|').toIntOrNull() ?: 0
                    val title = raw.substringAfter('|', "").lowercase()
                    val challenge = title.contains("just a moment") || title.contains("checking") ||
                        title.contains("attention required") || title.contains("vui lòng chờ")
                    val stable = len == lastLen
                    lastLen = len
                    if ((!challenge && len > 1500 && stable) || attempt >= 25) {
                        grabHtml()
                    } else {
                        handler.postDelayed({ poll(attempt + 1) }, 1000)
                    }
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (!polling) {
                        polling = true
                        handler.postDelayed({ poll(0) }, 1500)
                    }
                }
            }
            wv.loadUrl(url)

            cont.invokeOnCancellation {
                handler.post {
                    if (!finished) {
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        runCatching { wv.stopLoading(); wv.destroy() }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context.applicationContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
        }
        return wv
    }

    /** evaluateJavascript trả về chuỗi dạng JSON ("...") → giải mã thành chuỗi thường. */
    private fun decodeJs(json: String?): String {
        if (json.isNullOrEmpty() || json == "null") return ""
        return runCatching { JSONArray("[$json]").get(0).toString() }.getOrDefault(json)
    }
}
