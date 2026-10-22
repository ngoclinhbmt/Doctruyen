package vn.nlinh.doctruyen

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Cài đặt + vị trí đang đọc, lưu lâu dài trong máy. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("doctruyen", Context.MODE_PRIVATE)

    var savedUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var savedIndex: Int
        get() = sp.getInt("index", 0)
        set(v) = sp.edit().putInt("index", v).apply()

    var savedTitle: String
        get() = sp.getString("title", "") ?: ""
        set(v) = sp.edit().putString("title", v).apply()

    var voiceName: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()

    /** Tốc độ đọc 0.5 – 3.0 */
    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    /** Cao độ 0.5 – 2.0 */
    var pitch: Float
        get() = sp.getFloat("pitch", 1.0f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    fun saveProgress(url: String, index: Int, title: String) {
        sp.edit().putString("url", url).putInt("index", index).putString("title", title).apply()
    }
}

/** Trạng thái hiển thị, dịch vụ cập nhật – màn hình chính theo dõi. */
data class UiState(
    val status: String = "Sẵn sàng",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val active: Boolean = false,
    val title: String = "",
    val url: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val sleepInfo: String = "",
)

object ReaderState {
    val state = MutableStateFlow(UiState())
}
