package vn.nlinh.doctruyen

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.text.Normalizer

data class Chapter(
    val url: String,
    val title: String,
    val paragraphs: List<String>,
    val nextUrl: String?,
    val prevUrl: String?,
    /** Tên truyện (để hiện trong Ngăn tủ) */
    val storyTitle: String = "",
    /** true = các đoạn lấy theo thứ tự HIỂN THỊ trên màn hình (chắc chắn đúng thứ tự) */
    val visualOrder: Boolean = false,
)

/** Tải một trang chương truyện và tách: tiêu đề, nội dung, link chương sau/trước. */
object ChapterFetcher {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Vùng nội dung hay gặp trên các web truyện Việt. */
    private val CONTENT_SELECTORS = listOf(
        "#chapter-c", ".chapter-c", "#chapter-content", ".chapter-content",
        "#content-chapter", ".content-chapter", ".chapter_content", "#chapter_content",
        ".reading-content", ".box-chap", "#js-read__content", ".entry-content",
    )

    private val NEXT_WORDS = setOf(
        "tiếp", "tiếp theo", "chương sau", "chương tiếp", "chương tiếp theo",
        "chap sau", "chap tiếp", "sau", "next", "next chapter",
    )
    private val PREV_WORDS = setOf(
        "trước", "chương trước", "chap trước", "lùi", "prev", "previous", "previous chapter",
    )

    /** Ký tự "biến dạng" mà web truyện hay dùng để chống copy (vd: gϊếŧ → giết). */
    private val CHAR_FIX = mapOf(
        'ϊ' to 'i', 'ί' to 'i', 'ι' to 'i', 'ŧ' to 't', 'ɠ' to 'g', 'ɱ' to 'm',
        'ʋ' to 'v', 'ɦ' to 'h', 'ƙ' to 'k', 'ɴ' to 'n', 'ʟ' to 'l', 'ȿ' to 's',
        'ɑ' to 'a', 'ο' to 'o', 'е' to 'e', 'а' to 'a', 'о' to 'o', 'с' to 'c',
        ' ' to ' ',
    )

    /** Trang báo 404: thật sự hết truyện. */
    class EndOfStoryException(msg: String) : Exception(msg)

    /** Tải được trang nhưng không thấy nội dung (web chặn / cần JavaScript / cấu trúc lạ). */
    class NoContentException(msg: String) : Exception(msg)

    /** Thư mục lưu trang vừa tải để gửi báo lỗi. */
    @Volatile var debugDir: java.io.File? = null

    /** Cách 1: tải trực tiếp (nhanh). */
    fun fetch(url: String, cookie: String? = null, userAgent: String? = null): Chapter {
        val conn = Jsoup.connect(url)
            .userAgent(userAgent ?: USER_AGENT)
        // Dùng chung cookie đăng nhập với trình duyệt trong app
        if (!cookie.isNullOrBlank()) conn.header("Cookie", cookie)
        val response = conn
            .timeout(25_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .execute()
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            throw EndOfStoryException("Không còn chương tiếp theo (lỗi ${response.statusCode()}).")
        }
        if (response.statusCode() >= 400) {
            throw NoContentException("Web trả về lỗi ${response.statusCode()}")
        }
        val html = response.body()
        return fromHtml(html, response.url().toString(), "tải trực tiếp")
    }

    /** Phân tích HTML (từ tải trực tiếp hoặc từ trình duyệt ẩn). */
    fun fromHtml(
        html: String, url: String, source: String,
        renderedLines: List<String> = emptyList(),
        requireVisual: Boolean = false,
    ): Chapter {
        saveDebug(html, url, source)
        var chapter = parse(Jsoup.parse(html, url), url)
        // Có chữ theo thứ tự hiển thị (từ trình duyệt ẩn) → ưu tiên dùng, vì đúng thứ tự
        // người đọc thấy và đã bỏ các đoạn bị ẩn
        if (renderedLines.isNotEmpty()) {
            val rendered = cutAtNextChapter(
                clusterLines(renderedLines.map { cleanText(it) }.filter { it.isNotBlank() && !isJunk(it) }),
                url, chapter.title,
            )
            val domLen = chapter.paragraphs.sumOf { it.length }
            if (rendered.sumOf { it.length } >= domLen * 0.5 && rendered.sumOf { it.length } >= 150) {
                chapter = chapter.copy(paragraphs = rendered, visualOrder = true)
            }
        }
        // Web đã bật "đọc theo thứ tự hiển thị": không chấp nhận thứ tự HTML (có thể lộn xộn)
        if (requireVisual && !chapter.visualOrder) {
            throw NoContentException("chưa lấy được thứ tự hiển thị của trang")
        }
        if (chapter.paragraphs.sumOf { it.length } < 150) {
            val text = Jsoup.parse(html).text().lowercase()
            val reason = when {
                text.contains("just a moment") || text.contains("checking your browser") ||
                    text.contains("verify you are human") -> "web đang chặn bằng trang kiểm tra"
                text.contains("đăng nhập") && (text.contains("để đọc") || text.contains("để xem") ||
                    text.contains("mở khóa") || text.contains("vip")) -> "chương cần đăng nhập / mở khóa"
                else -> "không thấy chữ truyện trong trang"
            }
            throw NoContentException(reason)
        }
        return chapter
    }

    private fun saveDebug(html: String, url: String, source: String) {
        val dir = debugDir ?: return
        runCatching {
            java.io.File(dir, "trang_vua_tai.txt")
                .writeText("<!-- URL: $url | cách tải: $source -->\n$html")
        }
    }

    fun parse(doc: Document, url: String): Chapter {
        val title = findTitle(doc)
        val storyTitle = findStoryTitle(doc, url)
        val next = findLink(doc, url, NEXT_WORDS, "next", +1) ?: guessByNumber(url, +1)
        val prev = findLink(doc, url, PREV_WORDS, "prev", -1) ?: guessByNumber(url, -1)

        doc.select("script, style, noscript, iframe, ins, svg, select, nav, header, footer").remove()
        // Chỉ bỏ nút/form nhỏ (có web bọc cả trang trong <form>, hoặc mỗi câu là một nút "nghe")
        doc.select("button, form").toList().filter { it.text().length < 40 }.forEach { it.remove() }

        val content = findContent(doc)
        val primary = content?.let { extractParagraphs(it) } ?: emptyList()
        val fallback = clusterFallback(doc.body())
        val paragraphs = cutAtNextChapter(
            if (primary.sumOf { it.length } < fallback.sumOf { it.length } * 0.6) fallback else primary,
            url, title,
        )
        return Chapter(url, title, paragraphs, next?.takeIf { it != url }, prev?.takeIf { it != url }, storyTitle)
    }

    // ---------------- Nội dung ----------------

    private fun findContent(doc: Document): Element? {
        for (sel in CONTENT_SELECTORS) {
            val el = doc.selectFirst(sel) ?: continue
            if (directTextScore(el) > 300) return el
        }
        // Dự phòng: khối có nhiều chữ "trực tiếp" nhất (đoạn <p> con hoặc chữ ngăn bởi <br>)
        var best: Element? = null
        var bestScore = 0
        for (el in doc.select("div, article, section, td, main")) {
            val score = directTextScore(el)
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best
    }

    private fun directTextScore(el: Element): Int {
        var score = el.textNodes().sumOf { it.text().trim().length }
        for (child in el.children()) {
            when (child.tagName()) {
                "p", "span", "i", "em", "b", "strong", "font" -> score += child.text().length
                // Mỗi đoạn văn là một <div> riêng (không chứa khối con)
                "div" -> if (child.children().none { it.isBlock } && child.text().length < 3000) {
                    score += child.text().length
                }
            }
        }
        return score
    }

    private fun extractParagraphs(content: Element): List<String> {
        val el = content.clone()
        el.select("a, ul, ol, h1, h2, h3, .ads, .ad, [class*=banner], [id*=ads]").remove()
        // Bỏ đoạn bị ẩn bằng style ngay trong thẻ (chống sao chép)
        el.select("[style]").toList().filter { isHiddenStyle(it.attr("style")) }.forEach { it.remove() }
        // Web xáo trộn đoạn rồi sắp lại bằng CSS "order: N" → sắp lại theo order
        reorderByCssOrder(el)
        el.select("br").forEach { it.after(TextNode("\n")) }
        el.select("p, div, li, section, article, main, td, tr, blockquote, h4, h5, h6").forEach {
            it.prependText("\n")
            it.appendText("\n")
        }
        return el.wholeText()
            .split('\n')
            .map { cleanText(it) }
            .filter { it.isNotBlank() && !isJunk(it) }
    }

    /**
     * Dự phòng chung cho mọi web: lấy toàn bộ chữ trên trang theo dòng, tìm cụm dài nhất
     * gồm nhiều câu văn dài liền nhau (xen kẽ vài dòng ngắn như lời thoại).
     */
    private fun clusterFallback(body: Element?): List<String> {
        if (body == null) return emptyList()
        return clusterLines(extractParagraphs(body))
    }

    private fun clusterLines(lines: List<String>): List<String> {
        val longLen = 30
        var bestStart = -1
        var bestEnd = -1
        var bestScore = 0
        var i = 0
        while (i < lines.size) {
            if (lines[i].length < longLen) { i++; continue }
            var j = i
            var gap = 0
            var score = 0
            var last = i
            while (j < lines.size) {
                if (lines[j].length >= longLen) {
                    score += lines[j].length
                    last = j
                    gap = 0
                } else {
                    gap++
                    if (gap > 8) break
                }
                j++
            }
            if (score > bestScore) {
                bestScore = score
                bestStart = i
                bestEnd = last
            }
            i = last + 1
        }
        return if (bestStart >= 0) lines.subList(bestStart, bestEnd + 1) else emptyList()
    }

    private val ORDER_RE = Regex("(?i)(?:^|;)\\s*order\\s*:\\s*(-?\\d+)")

    private fun isHiddenStyle(style: String): Boolean {
        val st = style.lowercase().replace(" ", "")
        return st.contains("display:none") || st.contains("visibility:hidden") ||
            st.contains("font-size:0") || st.contains("opacity:0;") || st.endsWith("opacity:0") ||
            st.contains("color:transparent")
    }

    private fun reorderByCssOrder(root: Element) {
        for (parent in listOf(root) + root.select("*").toList()) {
            val kids = parent.children().toList()
            if (kids.size < 3) continue
            val orders = kids.map { k -> ORDER_RE.find(k.attr("style"))?.groupValues?.get(1)?.toIntOrNull() }
            if (orders.count { it != null } < kids.size * 0.6) continue
            kids.zip(orders).sortedBy { it.second ?: 0 }.forEach { parent.appendChild(it.first) }
        }
    }

    /**
     * Web cuộn liên tục (vd tusach247) để sẵn chương sau ngay dưới chương hiện tại.
     * Cắt bỏ từ dòng "Chương tiếp theo" / "Chương N+1" trở đi.
     */
    private fun cutAtNextChapter(paragraphs: List<String>, url: String, title: String): List<String> {
        val cur = Regex("(?i)(chuong|chapter|chap)-(\\d+)").findAll(url).lastOrNull()
            ?.groupValues?.get(2)?.toIntOrNull()
            ?: Regex("(?i)chương\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val nextRe = if (cur != null) Regex("(?i)^(chương|chapter|chap)\\s*0*${cur + 1}(\\D|$)") else null
        for (i in 1 until paragraphs.size) {
            val p = paragraphs[i].lowercase()
            if (p == "chương tiếp theo" || p.startsWith("chương tiếp theo") && p.length < 40 ||
                (nextRe != null && nextRe.containsMatchIn(paragraphs[i]) && paragraphs[i].length < 120)
            ) return paragraphs.subList(0, i)
        }
        return paragraphs
    }

    private fun isJunk(line: String): Boolean {
        val l = line.lowercase()
        if (l in NEXT_WORDS || l in PREV_WORDS) return true
        return l.contains("quảng cáo") && l.length < 200 ||
            l.startsWith("nhấn mở bình luận") ||
            l.contains("bạn đang đọc truyện") ||
            l.contains("đọc truyện online tại") ||
            l.contains("http://") || l.contains("https://")
    }

    fun cleanText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in Normalizer.normalize(raw, Normalizer.Form.NFC)) {
            sb.append(CHAR_FIX[c] ?: c)
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ---------------- Tiêu đề ----------------

    private val CHAPTER_RE = Regex("(?i)(chương|chapter|chap|hồi)\\s*\\d+")

    /** Tên truyện: chữ của link trỏ về trang truyện, hoặc thẻ h1 không phải tên chương. */
    private fun findStoryTitle(doc: Document, url: String): String {
        val base = Library.storyUrlOf(url)?.trimEnd('/')
        if (base != null) {
            for (a in doc.select("a[href]")) {
                if (a.absUrl("href").trimEnd('/') != base) continue
                val t = cleanText(a.text())
                if (t.length in 4..150 && !CHAPTER_RE.containsMatchIn(t)) return t
            }
        }
        for (el in doc.select("h1, .story-title, .truyen-title")) {
            val t = cleanText(el.text())
            if (t.length in 4..150 && !CHAPTER_RE.containsMatchIn(t)) return t
        }
        return ""
    }

    private fun findTitle(doc: Document): String {
        val chapRe = Regex("(?i)(chương|chapter|chap|hồi)\\s*\\d+")
        for (el in doc.select(".chapter-title, h1, h2, h3")) {
            val t = cleanText(el.text())
            if (t.isNotEmpty() && t.length < 200 && chapRe.containsMatchIn(t)) return t
        }
        val m = chapRe.find(doc.title())
        return m?.value ?: cleanText(doc.title()).take(80)
    }

    // ---------------- Link chương sau / trước ----------------

    private fun findLink(
        doc: Document, current: String, words: Set<String>, key: String, delta: Int,
    ): String? {
        val links = doc.select("a[href]").toList().filter { a ->
            val href = a.attr("href").trim()
            href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript", true)
        }
        fun norm(t: String) = cleanText(t).lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        fun ok(a: Element): String? = a.absUrl("href").takeIf { it.isNotEmpty() && it != current }

        // 1) Theo chữ trên nút: "Tiếp", "Chương sau"...
        for (a in links) {
            if (norm(a.text()) in words) ok(a)?.let { return it }
        }
        // 2) Nút ghi số chương: đang ở chương 1 → tìm link "Chương 2"
        val curNum = chapterNumber(current)
        if (curNum != null) {
            val target = curNum + delta
            val numRe = Regex("^(chương|chuong|chapter|chap|ch)\\s*(\\d+)")
            for (a in links) {
                val m = numRe.find(norm(a.text())) ?: continue
                if (m.groupValues[2].toIntOrNull() == target) ok(a)?.let { return it }
            }
        }
        // 3) Theo rel="next"/"prev"
        doc.selectFirst("link[rel=$key], a[rel=$key]")?.absUrl("href")
            ?.takeIf { it.isNotEmpty() && it != current }?.let { return it }
        // 4) Theo id/class có chữ next/prev
        for (a in links) {
            val idc = (a.id() + " " + a.className()).lowercase()
            if (idc.contains(key)) ok(a)?.let { return it }
        }
        return null
    }

    private fun chapterNumber(url: String): Int? =
        Regex("(chuong|chapter|chap|c)-(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(url).lastOrNull()?.groupValues?.get(2)?.toIntOrNull()

    /** "…/chuong-51/" → "Chương 51" (để hiện tiến độ) */
    fun guessTitleFromUrl(url: String?): String {
        val m = Regex("(?i)(chuong|chapter|chap)-(\\d+)").findAll(url.orEmpty()).lastOrNull()
        return if (m != null) "Chương ${m.groupValues[2]}" else "chương"
    }

    /** Dự phòng: .../chuong-5/ → .../chuong-6/ */
    fun guessByNumber(url: String, delta: Int): String? {
        val re = Regex("(chuong|chapter|chap|c)-(\\d+)", RegexOption.IGNORE_CASE)
        val m = re.findAll(url).lastOrNull() ?: return null
        val n = m.groupValues[2].toInt() + delta
        if (n < 1) return null
        val g = m.groups[2]!!.range
        return url.replaceRange(g, n.toString())
    }
}
