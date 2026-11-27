#!/bin/bash
# Cập nhật firmware R1-Y từ GitHub Releases (BachDaThan/R1-Y) qua cùng WiFi.
# Dùng:  ./update.sh [IP_LOA]
# Ví dụ: ./update.sh 192.168.2.20
# Luồng: kiểm tra bản mới nhất -> tải APK -> cài qua ADB WiFi -> khởi động lại app.
# (Trên loa cũng có nút Cập nhật trong giao diện http://IP_LOA:8088)

set -e

REPO="BachDaThan/R1-Y"
PACKAGE_NAME="com.bachdathan.r1y"
R1_IP="${1:-}"

info()  { echo -e "[INFO] $1"; }
warn()  { echo -e "[WARN] $1"; }
error() { echo -e "[ERROR] $1"; }

if ! command -v adb &> /dev/null; then
    error "Chưa có adb."
    exit 1
fi
if ! command -v curl &> /dev/null; then
    error "Chưa có curl."
    exit 1
fi

if [ -n "$R1_IP" ]; then
    info "Kết nối loa $R1_IP qua WiFi..."
    adb connect "$R1_IP:5555" || true
    sleep 2
fi

if ! adb devices | grep -q "device$"; then
    error "Chưa thấy loa. Chạy: adb connect <IP_LOA>:5555"
    exit 1
fi

info "Kiểm tra bản mới nhất trên GitHub ($REPO)..."
LATEST=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest")
TAG=$(echo "$LATEST" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tag_name',''))")
APK_URL=$(echo "$LATEST" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for a in d.get('assets',[]):
    if a.get('name','').endswith('.apk'):
        print(a['browser_download_url']); break
")
if [ -z "$TAG" ] || [ -z "$APK_URL" ]; then
    error "Không lấy được bản release (chưa có release nào?)."
    exit 1
fi
info "Bản mới nhất: $TAG"

CURRENT=$(adb shell dumpsys package "$PACKAGE_NAME" 2>/dev/null | grep versionName | head -1 | sed 's/.*=//' | tr -d '\r' || echo "")
info "Bản trên loa: ${CURRENT:-chưa cài}"

TMP_APK="/tmp/R1Y-${TAG}.apk"
info "Tải APK..."
curl -fL -o "$TMP_APK" "$APK_URL"

info "Cài lên loa..."
adb push "$TMP_APK" /data/local/tmp/lilyr1.apk
adb shell pm install -t -r /data/local/tmp/lilyr1.apk
adb shell rm /data/local/tmp/lilyr1.apk 2>/dev/null || true

info "Khởi động lại app..."
adb shell am start -n "$PACKAGE_NAME/.ui.MainActivity"

rm -f "$TMP_APK"
info "=== Cập nhật xong $TAG! ==="
info "Mở http://<IP_LOA>:8088 để kiểm tra."
