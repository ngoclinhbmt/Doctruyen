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

    class EndOfStoryException(msg: String) : Exception(msg)

    fun fetch(url: String): Chapter {
        val response = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(25_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .execute()
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            throw EndOfStoryException("Không còn chương tiếp theo (lỗi ${response.statusCode()}).")
        }
        if (response.statusCode() >= 400) {
            throw java.io.IOException("Web trả về lỗi ${response.statusCode()}")
        }
        val doc = response.parse()
        val chapter = parse(doc, response.url().toString())
        if (chapter.paragraphs.sumOf { it.length } < 150) {
            throw EndOfStoryException("Không tìm thấy nội dung chương (có thể đã hết truyện).")
        }
        return chapter
    }

    fun parse(doc: Document, url: String): Chapter {
        val title = findTitle(doc)
        val next = findLink(doc, url, NEXT_WORDS, "next") ?: guessByNumber(url, +1)
        val prev = findLink(doc, url, PREV_WORDS, "prev") ?: guessByNumber(url, -1)

        doc.select("script, style, noscript, iframe, ins, svg, form, button, nav, header, footer, select")
            .remove()
        val content = findContent(doc)
        val paragraphs = content?.let { extractParagraphs(it) } ?: emptyList()
        return Chapter(url, title, paragraphs, next?.takeIf { it != url }, prev?.takeIf { it != url })
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
            }
        }
        return score
    }

    private fun extractParagraphs(content: Element): List<String> {
        val el = content.clone()
        el.select("a, ul, ol, h1, h2, h3, .ads, .ad, [class*=banner], [id*=ads]").remove()
        el.select("br").forEach { it.after(TextNode("\n")) }
        el.select("p, div, li").forEach {
            it.prependText("\n")
            it.appendText("\n")
        }
        return el.wholeText()
            .split('\n')
            .map { cleanText(it) }
            .filter { it.isNotBlank() && !isJunk(it) }
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

    private fun findLink(doc: Document, current: String, words: Set<String>, key: String): String? {
        val links = doc.select("a[href]").filter { a ->
            val href = a.attr("href").trim()
            href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript", true)
        }
        // 1) Theo chữ trên nút: "Tiếp", "Chương sau"...
        for (a in links) {
            val text = cleanText(a.text()).lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (text in words) {
                val abs = a.absUrl("href")
                if (abs.isNotEmpty() && abs != current) return abs
            }
        }
        // 2) Theo rel="next"/"prev"
        doc.selectFirst("link[rel=$key], a[rel=$key]")?.absUrl("href")
            ?.takeIf { it.isNotEmpty() && it != current }?.let { return it }
        // 3) Theo id/class có chữ next/prev
        for (a in links) {
            val idc = (a.id() + " " + a.className()).lowercase()
            if (idc.contains(key)) {
                val abs = a.absUrl("href")
                if (abs.isNotEmpty() && abs != current) return abs
            }
        }
        return null
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
