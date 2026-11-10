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

    /** lines = các dòng chữ theo đúng thứ tự hiện trên màn hình (bỏ chữ bị ẩn) */
    data class Result(val html: String, val finalUrl: String, val lines: List<String> = emptyList())

    suspend fun load(url: String, timeoutMs: Long = 45_000): Result = withTimeout(timeoutMs) {
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
                    val html = decodeJs(json)
                    wv.evaluateJavascript(VISUAL_LINES_JS) { linesJson ->
                        val lines = runCatching {
                            val arr = JSONArray(decodeJs(linesJson))
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())
                        finish(Result(html, wv.url ?: url, lines))
                    }
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

    companion object {
        /**
         * Lấy chữ theo thứ tự HIỂN THỊ trên màn hình (sắp theo toạ độ dọc), bỏ các đoạn bị ẩn.
         * Chống trường hợp web xáo trộn đoạn văn trong HTML rồi dùng CSS sắp lại,
         * hoặc chèn đoạn ẩn để chống sao chép.
         */
        val VISUAL_LINES_JS = """
            (function(){
              var SKIP = /^(NAV|HEADER|FOOTER|SCRIPT|STYLE|NOSCRIPT|BUTTON|SELECT|FORM|ASIDE|SVG|IFRAME|TEMPLATE)${'$'}/;
              var INLINE = /^(B|I|EM|STRONG|SPAN|FONT|U|SMALL|SUP|SUB|MARK|Q|CITE|LABEL)${'$'}/;
              function hiddenStyle(el){
                var s = getComputedStyle(el);
                return s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) === 0 ||
                       parseFloat(s.fontSize) < 5 || s.color === 'transparent' || s.color === 'rgba(0, 0, 0, 0)';
              }
              function ownText(el){
                var parts = [], cur = '';
                for (var k = 0; k < el.childNodes.length; k++) {
                  var n = el.childNodes[k];
                  if (n.nodeType === 3) cur += n.textContent;
                  else if (n.nodeType === 1 && n.tagName === 'BR') { parts.push(cur); cur = ''; }
                  else if (n.nodeType === 1 && INLINE.test(n.tagName) && !n.querySelector('p,div,li') && !hiddenStyle(n)) cur += (n.innerText || n.textContent);
                }
                parts.push(cur);
                return parts.map(function(t){ return t.replace(/\s+/g,' ').trim(); })
                            .filter(function(t){ return t.length > 0; });
              }
              var items = [], seq = 0, withRect = 0;
              function walk(el){
                if (SKIP.test(el.tagName) || hiddenStyle(el)) return;
                var t = ownText(el);
                if (t.length) {
                  var r = el.getBoundingClientRect();
                  if (r.height >= 2 && r.width >= 2) withRect++;
                  items.push({y: r.top + window.scrollY, x: r.left, o: seq++, t: t.join('\n')});
                }
                var kids = Array.prototype.slice.call(el.children).filter(function(c){
                  return c.tagName !== 'BR' && !(INLINE.test(c.tagName) && !c.querySelector('p,div,li'));
                });
                // Khối flex/grid: sắp con theo CSS "order" (không cần tính vị trí trên màn hình)
                var d = getComputedStyle(el).display;
                if (d.indexOf('flex') >= 0 || d.indexOf('grid') >= 0) {
                  kids = kids.map(function(c, i){ return {c: c, i: i, o: parseInt(getComputedStyle(c).order, 10) || 0}; })
                             .sort(function(a, b){ return (a.o - b.o) || (a.i - b.i); })
                             .map(function(x){ return x.c; });
                }
                for (var i = 0; i < kids.length; i++) walk(kids[i]);
              }
              if (document.body) walk(document.body);
              // Có toạ độ thật (trang đã dàn trang) → sắp theo vị trí trên màn hình
              if (items.length && withRect >= items.length * 0.8) {
                items.sort(function(a, b){ return (a.y - b.y) || (a.x - b.x) || (a.o - b.o); });
              }
              return JSON.stringify(items.map(function(o){ return o.t; }).join('\n').split('\n'));
            })()
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context.applicationContext)
        // WebView chạy ngầm có kích thước 0x0 → web "tải khi cuộn tới" không hiện chữ.
        // Cho nó kích thước màn hình điện thoại và luôn chạy JavaScript.
        val w = 1080
        val h = 2400
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, w, h)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.onResume()
        wv.resumeTimers()
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
