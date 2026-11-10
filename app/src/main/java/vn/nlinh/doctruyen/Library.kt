package vn.nlinh.doctruyen

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Một cuốn truyện trong Ngăn tủ. */
data class Book(
    val id: String,              // = link trang truyện (không có "/" cuối)
    val title: String,
    val storyUrl: String,
    val chapterUrl: String = "", // chương đang đọc (rỗng = chưa đọc)
    val chapterTitle: String = "",
    val index: Int = 0,          // đoạn đang đọc trong chương
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Trạng thái tải offline của từng sách (chỉ để hiển thị). */
data class DownloadInfo(
    val running: Boolean = false,
    val text: String = "",
)

object Library {

    private val CHAPTER_IN_URL = Regex("(?i)^(.*?)/?(chuong|chapter|chap)-\\d+.*$")

    /** .../ten-truyen/chuong-51/ → .../ten-truyen/   (null nếu không phải link chương) */
    fun storyUrlOf(url: String): String? {
        val m = CHAPTER_IN_URL.find(url.substringBefore('#').substringBefore('?')) ?: return null
        val base = m.groupValues[1]
        if (base.count { it == '/' } < 3) return null // chỉ còn tên miền → không tách được
        return "$base/"
    }

    fun isChapterUrl(url: String) = storyUrlOf(url) != null

    fun bookId(storyUrl: String) = storyUrl.substringBefore('#').substringBefore('?').trimEnd('/')

    /** Đoán link chương 1 khi chưa biết: .../ten-truyen/ → .../ten-truyen/chuong-1/ */
    fun guessFirstChapter(storyUrl: String, sampleChapterUrl: String? = null): String {
        if (!sampleChapterUrl.isNullOrBlank()) {
            val m = Regex("(?i)(chuong|chapter|chap)-(\\d+)").findAll(sampleChapterUrl).lastOrNull()
            if (m != null) return sampleChapterUrl.replaceRange(m.groups[2]!!.range, "1")
        }
        return storyUrl.trimEnd('/') + "/chuong-1/"
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    // =====================================================================
    //  Ngăn tủ
    // =====================================================================

    val books = MutableStateFlow<List<Book>>(emptyList())
    val downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())

    private var shelfFile: File? = null
    private var offlineRoot: File? = null

    @Synchronized
    fun init(context: Context) {
        if (shelfFile != null) return
        val dir = context.applicationContext.filesDir
        shelfFile = File(dir, "ngan_tu.json")
        offlineRoot = File(dir, "offline").apply { mkdirs() }
        books.value = loadShelf()
    }

    private fun loadShelf(): List<Book> = runCatching {
        val f = shelfFile ?: return emptyList()
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Book(
                id = o.getString("id"),
                title = o.optString("title"),
                storyUrl = o.optString("storyUrl"),
                chapterUrl = o.optString("chapterUrl"),
                chapterTitle = o.optString("chapterTitle"),
                index = o.optInt("index"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveShelf(list: List<Book>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("title", b.title); put("storyUrl", b.storyUrl)
                put("chapterUrl", b.chapterUrl); put("chapterTitle", b.chapterTitle)
                put("index", b.index); put("updatedAt", b.updatedAt)
            })
        }
        runCatching { shelfFile?.writeText(arr.toString()) }
    }

    private fun commit(list: List<Book>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        books.value = sorted
        saveShelf(sorted)
    }

    fun find(id: String): Book? = books.value.firstOrNull { it.id == id }

    /**
     * Thêm truyện (khi duyệt web trong app). Trả về true nếu là truyện mới.
     * Truyện đã có thì giữ nguyên chỗ đang đọc.
     */
    @Synchronized
    fun addBook(storyUrl: String, title: String, firstChapterUrl: String = ""): Boolean {
        val id = bookId(storyUrl)
        val current = books.value
        val old = current.firstOrNull { it.id == id }
        if (old != null) {
            if (old.title.isBlank() && title.isNotBlank()) {
                commit(current.map { if (it.id == id) it.copy(title = title) else it })
            }
            return false
        }
        val name = title.ifBlank { prettySlug(storyUrl) }
        commit(current + Book(id = id, title = name, storyUrl = storyUrl, chapterUrl = firstChapterUrl))
        return true
    }

    /** Gọi mỗi khi đang đọc một chương: cập nhật (hoặc tự thêm) sách vào Ngăn tủ. */
    @Synchronized
    fun updateProgress(chapter: Chapter, index: Int) {
        val storyUrl = storyUrlOf(chapter.url) ?: return
        val id = bookId(storyUrl)
        val current = books.value
        val old = current.firstOrNull { it.id == id }
        val updated = (old ?: Book(id = id, title = "", storyUrl = storyUrl)).copy(
            title = old?.title?.takeIf { it.isNotBlank() } ?: chapter.storyTitle.ifBlank { prettySlug(storyUrl) },
            chapterUrl = chapter.url,
            chapterTitle = chapter.title,
            index = index,
            updatedAt = System.currentTimeMillis(),
        )
        commit(current.filter { it.id != id } + updated)
    }

    @Synchronized
    fun removeBook(id: String) {
        commit(books.value.filter { it.id != id })
        bookDir(id)?.deleteRecursively()
    }

    private fun prettySlug(storyUrl: String): String =
        storyUrl.trimEnd('/').substringAfterLast('/')
            .replace('-', ' ').replaceFirstChar { it.uppercase() }

    // =====================================================================
    //  Chương offline: filesDir/offline/<sách>/<chương>.json
    // =====================================================================

    private fun bookDir(bookId: String): File? = offlineRoot?.let { File(it, sha1(bookId)) }

    private fun chapterFile(url: String, visual: Boolean): File? {
        val story = storyUrlOf(url) ?: return null
        val dir = bookDir(bookId(story)) ?: return null
        return File(dir, sha1(url.trimEnd('/')) + if (visual) ".v.json" else ".json")
    }

    /** Xoá các chương đã lưu của một truyện (vd khi đổi cách đọc web) */
    fun clearOffline(bookId: String) {
        bookDir(bookId)?.deleteRecursively()
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    /** Số chương đã lưu (mọi kiểu) */
    fun offlineCount(bookId: String): Int =
        bookDir(bookId)?.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    /** Số chương lưu theo thứ tự HTML cũ (có thể lộn xộn) */
    fun legacyOfflineCount(bookId: String): Int =
        bookDir(bookId)?.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".v.json") }?.size ?: 0

    fun saveOffline(ch: Chapter) {
        val f = chapterFile(ch.url, ch.visualOrder) ?: return
        f.parentFile?.mkdirs()
        val o = JSONObject().apply {
            put("url", ch.url); put("title", ch.title); put("storyTitle", ch.storyTitle)
            put("next", ch.nextUrl ?: ""); put("prev", ch.prevUrl ?: "")
            put("paragraphs", JSONArray(ch.paragraphs))
        }
        runCatching {
            f.writeText(o.toString())
            // Đã có bản đúng thứ tự → xoá bản cũ
            if (ch.visualOrder) chapterFile(ch.url, false)?.delete()
        }
    }

    /**
     * Đọc chương đã lưu. requireVisual = true (web đã bật đọc theo thứ tự hiển thị):
     * chỉ nhận bản đúng thứ tự; bản cũ coi như chưa có để tải lại.
     */
    fun loadOffline(url: String, requireVisual: Boolean = false): Chapter? {
        chapterFile(url, true)?.takeIf { it.exists() }?.let { f -> readChapter(f, true)?.let { return it } }
        if (requireVisual) return null
        return chapterFile(url, false)?.takeIf { it.exists() }?.let { readChapter(it, false) }
    }

    private fun readChapter(f: File, visual: Boolean): Chapter? = runCatching {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("paragraphs")
        Chapter(
            url = o.getString("url"),
            title = o.optString("title"),
            paragraphs = (0 until arr.length()).map { arr.getString(it) },
            nextUrl = o.optString("next").ifBlank { null },
            prevUrl = o.optString("prev").ifBlank { null },
            storyTitle = o.optString("storyTitle"),
            visualOrder = visual,
        )
    }.getOrNull()

    fun setDownload(bookId: String, info: DownloadInfo?) {
        downloads.update { m -> if (info == null) m - bookId else m + (bookId to info) }
    }
}
