package vn.nlinh.doctruyen

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Tải một chương từ web (dùng chung cho đọc và tải offline). Gọi trên luồng chính.
 *  Cách 1: tải trực tiếp, kèm cookie đăng nhập + User-Agent của trình duyệt trong app.
 *  Cách 2: mở bằng trình duyệt ẩn, chờ JavaScript chạy xong rồi đọc (thử 2 lần).
 */
class ChapterLoader(private val context: Context, private val webLoader: WebPageLoader) {

    suspend fun fetch(url: String, onWebView: (() -> Unit)? = null): Chapter {
        var directError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
                return withContext(Dispatchers.IO) { ChapterFetcher.fetch(url, cookie, ua) }
            } catch (e: ChapterFetcher.EndOfStoryException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChapterFetcher.NoContentException) {
                directError = e
                break
            } catch (e: Exception) {
                directError = e
                delay(1500L * (attempt + 1))
            }
        }
        onWebView?.invoke()
        var webError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val page = webLoader.load(url)
                return withContext(Dispatchers.Default) {
                    ChapterFetcher.fromHtml(page.html, page.finalUrl, "trình duyệt ẩn")
                }
            } catch (e: TimeoutCancellationException) {
                webError = ChapterFetcher.NoContentException("trang tải quá lâu")
            } catch (e: ChapterFetcher.NoContentException) {
                // Lần 2 thường qua được trang kiểm tra (đã có cookie)
                webError = e
            }
        }
        throw webError ?: directError ?: ChapterFetcher.NoContentException("không tải được trang")
    }
}
