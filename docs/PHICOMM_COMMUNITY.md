# Ghi chú về bộ tài liệu cộng đồng Phicomm R1 (nhóm “AI Plus Box”)

Anh **Quang Minh** (0397375555) có bộ video + bộ cài *All in One* cho loa R1:
`https://www.youtube.com/playlist?list=PLK-7C8xPvBXDHc2isS4tO023dCYNNry8g`
(PC all-in-one, `R1connectwifi.apk`, trang cấu hình **8081**, cài AI tiếng Việt/Unisound/DLNA).

Tài liệu đó **không phải** một phần của R1-Y và R1-Y không phụ thuộc vào nó.
Trang cấu hình của R1-Y là **cổng 8088** (`http://IP_LOA:8088`), port **không đổi**
để khỏi lệch với hướng dẫn của repo này.

## Phần dùng được (đã áp dụng vào R1-Y)

| Quan sát từ tài liệu cộng đồng | Việc R1-Y làm |
|---|---|
| Loa hay bị kẹt vì tool PC không xoá bản cũ / firmware cũ | v1.7.1: khoá ký cố định + tool tự gỡ khi khác chữ ký |
| Có bản “kiểm tra IP loa” trước khi cài | `tools/r1y.py status --ip <IP>` + tự dò IP, tự thử hotspot `192.168.43.1` |
| Nhóm khuyên reset về gốc khi loa “lag, không vào được 8088” | v1.7.2: máy chủ nhạc không còn để tiến trình `yt-dlp` treo (nguyên nhân chính làm loa chậm dần), resolver bỏ qua nhanh máy chủ bị YouTube chặn |
| Hướng dẫn riêng cho “đổi WiFi / đổi server xiaozhi” | R1-Y làm được ngay trên web 8088 (tab **Nhà** + tab **Trợ lý**), không cần PC |

## Phần KHÔNG dùng

- **Không** cài All in One / APK của nhóm lên loa R1-Y: khác `applicationId`, khác chữ ký,
  hai bộ cùng tồn tại sẽ tranh nhau ADB, hotspot và cổng web → chính là kiểu lỗi
  “pm install lỗi / error: closed” đã gặp. Chọn **một** bộ.
- **Không** dùng chung số điện thoại/mật khẩu giải nén trong repo (dữ liệu cá nhân).
- Tài liệu/video của nhóm có bản quyền riêng — repo chỉ ghi lại *kinh nghiệm kỹ thuật*,
  không chép nội dung, không đóng gói lại file của họ.

## Nếu vẫn muốn dùng bộ của nhóm

Được, nhưng làm đúng thứ tự để không dẫm chân nhau:

1. Gỡ R1-Y trước: `adb shell pm uninstall com.bachdathan.r1y`
2. Cài bộ all-in-one của nhóm, dùng trang **8081** của họ.
3. Muốn quay lại R1-Y: gỡ app của nhóm rồi `python3 tools/r1y.py install --ip <IP>`.

Đừng chạy hai tool cùng lúc trên một loa.

## Nguồn tham khảo khác về loa R1 (đã đối chiếu, có áp dụng)

| Nguồn | Dùng được gì cho R1-Y |
|---|---|
| `github.com/Enter-tainer/phicomm-r1-voice-assistant` (đã chạy trên loa thật) | **STREAM_MUSIC không ra loa ngoài** → phải dùng `STREAM_SYSTEM`. Cách cài `pm install` phải chạy **nền** trên R1. Unisound chiếm micro. Mic R1 rất kém (cần gain lớn). Android 5.1 không có Opus. |
| `heylily.net/firmware` | Chủ yếu là **mạch ESP32** (Lily Box/Touch/CYD…). Mục “Phicomm R1” chỉ là **file tải về cho Windows** — nạp firmware kiểu khác, không dùng cho R1-Y và không có mã nguồn. |
| `nguyentanduyen885/banhmi-r1-releases` | Xác nhận: ẩn Unisound để không giành micro; nút trên đỉnh loa giữ ~5-6 giây để phát WiFi cài đặt; trang điều khiển cũng ở cổng 8088. |

R1-Y **không** dùng lại mã của các dự án đó, chỉ áp dụng *kết luận kỹ thuật* đã kiểm chứng
trên cùng phần cứng:

- `R1YConfig.getAudioRoute()` — mặc định `system` (STREAM_SYSTEM), đổi được trên web.
- `tools/r1y.py adb_install()` — push + `pm install` **chạy nền**, đọc file kết quả.
- `hide_unisound()` trong bước cài.
- `AudioFallback.deviceSupportsOpus()` — không chọn Opus khi máy không có bộ giải mã.
