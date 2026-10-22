# Đọc Truyện – app Android tự đọc truyện và tự sang chương

## Tính năng
- Dán link chương (hoặc **Chia sẻ** link từ Chrome / Samsung Internet sang app)
- Đọc to bằng giọng TTS tiếng Việt của máy, chọn được giọng, tốc độ, cao độ
- Đọc xong tự sang chương sau, vẫn đọc khi **tắt màn hình**
- Tự nhớ chương và đoạn đang đọc dở → mai mở app bấm **Đọc tiếp**
- Hẹn giờ tắt (15–90 phút hoặc hết chương này)
- Điều khiển trên thanh thông báo: Trước / Dừng / Sau
- Tự dừng khi có cuộc gọi hoặc khi rút tai nghe
- Tự sửa chữ chống copy kiểu "gϊếŧ" → "giết"

## Build APK bằng GitHub (không cần máy tính mạnh)
1. Tạo repository mới trên GitHub (Public hoặc Private).
2. Giải nén file zip, tải **toàn bộ** thư mục lên repo (giữ nguyên cấu trúc thư mục,
   kể cả thư mục `.github`). Nên dùng trình duyệt trên máy tính: kéo thả cả thư mục vào
   trang *Add file → Upload files*.
3. Vào tab **Actions** → chờ dòng **Build APK** có dấu ✓ xanh (khoảng 3–6 phút).
4. Bấm vào lần build → mục **Artifacts** → tải **DocTruyen-apk** (file zip) → giải nén → cài APK.

Mỗi lần sửa code và đẩy lên, GitHub tự build lại. Có thể bấm *Run workflow* trong tab Actions để build thủ công.

APK được ký bằng khoá cố định `app/debug.keystore`, nên bản mới cài đè bản cũ được, không mất chỗ đọc đã lưu.

## Build bằng Android Studio (tuỳ chọn)
Mở thư mục project → chờ Gradle sync → **Build → Build APK(s)**.

## Lần đầu sử dụng
1. Cho phép **thông báo** khi app hỏi.
2. Bấm **Cho phép chạy nền (tắt tối ưu pin)** – quan trọng với máy Samsung / Xiaomi / Oppo.
3. Chọn giọng: bấm **Cài giọng** → chọn công cụ *Google Text-to-Speech* → tải dữ liệu
   **Tiếng Việt**. Quay lại app chọn giọng, bấm **Nghe thử**.
4. Dán link chương → **Đọc link này**.

## Nếu đọc sai nội dung hoặc không sang chương
App tự dò vùng nội dung và nút "Tiếp / Chương sau". Nếu một trang web khác cấu trúc lạ,
sửa danh sách `CONTENT_SELECTORS` / `NEXT_WORDS` trong
`app/src/main/java/vn/nlinh/doctruyen/ChapterFetcher.kt`.
