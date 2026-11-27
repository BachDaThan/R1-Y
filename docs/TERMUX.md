# Cài firmware R1-Y cho loa bằng điện thoại (Termux)

Hướng dẫn từng bước, làm được **hoàn toàn trên điện thoại Android**, không cần PC.

---

## 0. Cần gì trước

- Điện thoại Android (đã cài **Termux** — tải trên F-Droid hoặc CH Play).
- Loa Phicomm R1 và điện thoại **cùng một WiFi** (hoặc điện thoại nối WiFi `R1-Y` do loa phát).
- Loa đã bật **Gỡ lỗi ADB/USB** (Cài đặt → Tùy chọn nhà phát triển).

> Nếu chưa cài Termux: tải ở **F-Droid** (`https://f-droid.org/packages/com.termux/`).
> Bản trên CH Play đã cũ, không khuyến khích.

---

> **Không muốn gõ lệnh trên điện thoại?** Dùng máy tính với bản
> **`R1Y-Tool.exe`** (tải ở mục Releases) — bấm đúp là có menu, không cần cài gì.
> Trên điện thoại vẫn phải dùng Termux (Android không chạy được file .exe).

## 1. Chuẩn bị Termux (1 lần duy nhất)

Mở Termux, dán từng dòng (giữ ngón tay → **Paste**):

```bash
pkg update -y && pkg install -y git python
```

Cài xong, tải repo về:

```bash
git clone https://github.com/BachDaThan/R1-Y
cd R1-Y
```

Rồi chạy script tự chuẩn bị (tự cài `android-tools` = adb, `curl`, cấp quyền bộ nhớ):

```bash
bash tools/termux_setup.sh
```

Bạn sẽ thấy:

```
✔ Python: Python 3.12.x
✔ adb đã cài
✔ Đã có quyền bộ nhớ
Xong. Chạy tool:  python3 tools/r1y.py
```

> Nếu nó hỏi **"Allow Termux to access files?"** → bấm **Allow**.

---

## 2. Chạy tool

```bash
bash tools/r1y_termux.sh
```

Menu hiện ra:

```
1  Cài firmware cho loa (tải APK từ GitHub, cần adb)
2  Cập nhật firmware (cần adb)
2b Cập nhật qua web — KHÔNG cần adb (loa tự tải + cài)
3  Cấu hình WiFi cho loa (quét + nối WiFi nhà)
4  Bật chế độ cài đặt (loa phát WiFi)
5  Xem trạng thái loa
6  Cấu hình máy chủ AI / chất lượng âm thanh
7  Thoát
```

---

## 3. Chọn cách cài

### Cách A — Có adb (khuyên dùng, đầy đủ nhất)

Chọn **1**, nhập IP loa (hoặc Enter để tự quét):

```
? IP loa (Enter để tự quét): 192.168.2.20
```

Tool sẽ tự: kết nối ADB WiFi → tải APK từ GitHub Releases → cài → cấp quyền →
bật tự khởi động → hỏi có khởi động lại loa không → in IP + link web.

### Cách B — Chưa có adb (nhờ loa tự cài)

Nếu Termux không cài được `android-tools` (một số máy chặn), vẫn cập nhật được:

```bash
python3 tools/r1y.py webinstall --ip 192.168.2.20
```

Loa sẽ **tự tải APK từ GitHub và tự cài** (có root thì cài im lặng; không root thì
hiện trình cài đặt trên loa — bấm Cài).

### Lấy IP loa ở đâu?

- Trên điện thoại: **Cài đặt → WiFi → mạng đang nối → IP** (hoặc app router).
- Đang nối WiFi `R1-Y` của loa → IP loa luôn là **192.168.43.1**.
- Nếu cài bằng adb: `adb devices` sẽ hiện `192.168.x.x:5555`.

---

## 4. Cấu hình WiFi cho loa (khi loa chưa vào WiFi nhà)

Đây là luồng "giữ nút tròn" bạn nói:

1. **Giữ nút tròn trên loa** → loa phát WiFi tên **`R1-Y`** (mật khẩu `r1y12345678`).
2. Điện thoại: **Cài đặt → WiFi → chọn `R1-Y`**.
3. Trong Termux chọn **3** (hoặc chạy):
   ```bash
   python3 tools/r1y.py wifi --ip 192.168.43.1
   ```
4. Tool in danh sách WiFi quét được → chọn số → nhập mật khẩu WiFi nhà.
5. Loa vào WiFi nhà, tự tắt hotspot, tool in **IP mới** → dùng IP đó để cài firmware.

> Nếu ROM khoá nút tròn: bật chế độ cài đặt bằng menu **4**, hoặc mở trình duyệt
> điện thoại tới `http://192.168.43.1:8088` → tab **Nhà** → *Bật chế độ cài đặt*.

---

## 5. Sau khi cài xong

- Giao diện điều khiển loa: **`http://<IP_LOA>:8088`** (cổng cố định **8088**).
  Đang nối WiFi của loa thì là `http://192.168.43.1:8088`.
- Mở bằng trình duyệt điện thoại là được — không cần app riêng.

---

## 6. Lỗi thường gặp (tool đã tự dịch sang tiếng Việt)

### `adb connect` báo "connected" nhưng `adb devices` không có máy

Đây là lỗi hay gặp nhất. Nguyên nhân theo thứ tự thường gặp:

1. **ADB chưa được cho phép (authorization).** Lần đầu kết nối, loa sẽ hiện hộp
   thoại *"Cho phép gỡ lỗi USB?"* — phải bấm **Cho phép** (tick *Luôn cho phép*).
   Bấm nhầm **Từ chối** thì phải xoá key rồi làm lại:
   ```bash
   adb kill-server
   rm -rf ~/.android/adbkey ~/.android/adbkey.pub
   adb start-server
   adb connect 192.168.2.20:5555
   ```
2. **Loa chưa bật Gỡ lỗi ADB.** Cài đặt → Giới thiệu (bấm 7 lần) → Tùy chọn nhà
   phát triển → bật **Gỡ lỗi ADB/USB**. Sau khi bật, **khởi động lại loa** một lần.
3. **Cổng 5555 chưa mở.** Một số ROM chỉ mở 5555 sau khi cắm USB một lần, hoặc
   cần lệnh (qua USB): `adb tcpip 5555`.
4. **Điện thoại dùng mạng 4G/different VLAN.** Tắt 4G, bảo đảm cả hai cùng WiFi.

Phân biệt nhanh bằng phản hồi của adb:

| adb trả lời | Nghĩa |
|---|---|
| `connected to ...` + `adb devices` trống | Đã kết nối TCP nhưng **chưa authorize** → bấm Cho phép trên loa |
| `failed to authenticate` | Key bị từ chối → xoá `~/.android/adbkey` rồi làm lại |
| `Connection refused` / `unable to connect` | Cổng 5555 **chưa mở** → bật Gỡ lỗi ADB + khởi động lại loa |
| `offline` | Loa treo ADB → tắt/bật lại Gỡ lỗi ADB |

> Không giải quyết được? Bỏ qua adb, dùng luôn:
> `python3 tools/r1y.py webinstall --ip 192.168.2.20`

### Các lỗi khác

| Tool báo | Nghĩa là | Làm gì |
|---|---|---|
| `Loa chưa phản hồi — kiểm tra cùng WiFi` | Không kết nối được tới loa | Kiểm tra 2 máy cùng WiFi, IP đúng |
| `Loa chưa cho phép máy này — bấm Cho phép trên loa` | ADB chưa authorize | Nhìn màn hình loa, bấm **Cho phép** (nhớ tick *luôn cho phép*) |
| `Thiếu công cụ cần thiết — tool sẽ thử tự cài lại` | Chưa có adb/git | Chạy lại `bash tools/termux_setup.sh` |
| `Không có quyền truy cập — chạy termux-setup-storage` | Chưa cấp quyền bộ nhớ | `termux-setup-storage` rồi Allow |
| `No such file or directory` khi cài gói | Termux không có `/tmp` | Đã sửa; chạy `git pull` để lấy bản mới |
| `Máy chủ không có dữ liệu này` | Repo chưa có Release | Dùng `--apk <file>` hoặc chờ bản phát hành |
| `Cài đặt bị từ chối — tool đã thử gỡ bản cũ` | Xung đột chữ ký APK | Tool tự gỡ rồi cài lại; nếu vẫn lỗi báo lại |

### ADB không thấy loa?

```bash
adb kill-server
adb start-server
adb connect 192.168.2.20:5555
adb devices
```

Nếu vẫn `offline`: trên loa tắt/bật lại **Gỡ lỗi ADB**, hoặc cắm USB một lần
(một số ROM phải authorize qua USB trước).

---

## 7. Lệnh nhanh (không cần menu)

```bash
# cài / cập nhật có adb
python3 tools/r1y.py install --ip 192.168.2.20 --yes
python3 tools/r1y.py update  --ip 192.168.2.20 --yes

# không cần adb
python3 tools/r1y.py webinstall --ip 192.168.2.20

# cấu hình WiFi cho loa (loa đang phát hotspot)
python3 tools/r1y.py wifi --ip 192.168.43.1

# xem trạng thái
python3 tools/r1y.py status --ip 192.168.2.20
```

---

## 8. Ký APK release (cho chủ repo)

APK release **phải được ký** thì loa mới cài được, nếu không Android báo
*“Ứng dụng chưa được cài đặt”*.

Repo đã cấu hình sẵn: `build.gradle` ký release bằng **debug keystore** (CI tự
sinh `~/.android/debug.keystore`), nên APK trong Releases **cài được ngay**.

Muốn dùng **key riêng của bạn**:

```bash
# 1. Tạo keystore (giữ file này cẩn thận — mất là không update được nữa)
keytool -genkeypair -v -keystore r1y-release.jks \
  -alias r1y -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <MAT_KHAU> -keypass <MAT_KHAU> -dname "CN=R1-Y,O=BachDaThan,C=VN"
```

2. Trong GitHub → **Settings → Secrets and variables → Actions**, thêm:
   - `R1Y_KEYSTORE` — nội dung file `.jks` đã base64 (`base64 -w0 r1y-release.jks`)
   - `R1Y_STORE_PASS`, `R1Y_KEY_PASS`, `R1Y_KEY_ALIAS`

3. Trong workflow `build.yml`, giải mã keystore trước bước build:
   ```yaml
   - run: echo "${{ secrets.R1Y_KEYSTORE }}" | base64 -d > $RUNNER_TEMP/r1y.jks
     env: {}
   ```
   và đặt biến `R1Y_KEYSTORE=$RUNNER_TEMP/r1y.jks`.

> ⚠️ Key khác nhau thì **không cài đè** lên bản cũ được — phải gỡ app trước
> (`python3 tools/r1y.py uninstall`).

## 9. Cập nhật tool về sau

```bash
cd ~/R1-Y
git pull
```

Repo có CI tự build; bản mới nhất nằm ở mục **Releases** trên GitHub.
