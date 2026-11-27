#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
#  R1-Y — Tự chuẩn bị môi trường Termux cho tool cài firmware
#  Chạy:  bash tools/termux_setup.sh
#
#  Tự cài những gì còn thiếu (python, adb, curl, openssl...) một cách
#  an toàn: chỉ dùng pkg chính thức của Termux, không tải script lạ,
#  không chạy lệnh từ mạng. Nếu không cài được thì vẫn tiếp tục với
#  những gì đã có, không chặn người dùng.
# ==========================================================
set -u

G='\033[92m'; Y='\033[93m'; R='\033[91m'; B='\033[94m'; N='\033[0m'; D='\033[2m'

ok()   { printf "  ${G}✔${N} %s\n" "$1"; }
info() { printf "  ${B}•${N} %s\n" "$1"; }
warn() { printf "  ${Y}!${N} %s\n" "$1"; }
err()  { printf "  ${R}✘${N} %s\n" "$1"; }

printf "\n${B}══ R1-Y · Chuẩn bị Termux ══${N}\n\n"

if [ -z "${PREFIX:-}" ] || [ ! -d "/data/data/com.termux" ]; then
    warn "Không phải Termux — bỏ qua bước cài gói."
    warn "Trên PC hãy dùng: python3 tools/r1y.py"
    exit 0
fi

# ---------------------------------------------------------------- mạng
if ! ping -c1 -W2 8.8.8.8 >/dev/null 2>&1 && ! ping -c1 -W2 1.1.1.1 >/dev/null 2>&1; then
    warn "Chưa có mạng — bỏ qua cài gói, dùng những gì đã có."
fi

# ---------------------------------------------------------------- pkg
install_pkg() {
    # $1 = tên gói, $2 = lệnh kiểm tra (mặc định = tên gói)
    local pkg="$1"; local check="${2:-$1}"
    if command -v "$check" >/dev/null 2>&1; then
        ok "$pkg đã có"
        return 0
    fi
    info "Đang cài $pkg…"
    # -y: không hỏi; ẩn phần lớn log, chỉ hiện khi lỗi
    if pkg install -y "$pkg" >/tmp/r1y-pkg.log 2>&1; then
        if command -v "$check" >/dev/null 2>&1; then
            ok "$pkg đã cài"
            return 0
        fi
    fi
    # thử cập nhật danh sách gói rồi cài lại 1 lần
    pkg update -y >/dev/null 2>&1 || true
    if pkg install -y "$pkg" >/tmp/r1y-pkg.log 2>&1 && command -v "$check" >/dev/null 2>&1; then
        ok "$pkg đã cài (sau khi cập nhật danh sách)"
        return 0
    fi
    warn "Không cài được $pkg — sẽ thử cách khác."
    tail -3 /tmp/r1y-pkg.log 2>/dev/null | sed 's/^/      /'
    return 1
}

# python là bắt buộc
if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    install_pkg python || {
        err "Không có Python. Mở Termux, chạy: pkg install python"
        exit 1
    }
fi
ok "Python: $( (python3 --version 2>/dev/null || python --version 2>/dev/null) | head -1)"

# adb: Termux có gói android-tools
if ! command -v adb >/dev/null 2>&1; then
    install_pkg android-tools adb || warn "Sẽ dùng adb đi kèm nếu tìm thấy."
else
    ok "adb đã có"
fi

# tiện ích mạng (không bắt buộc)
for p in curl openssl-tool; do
    command -v "${p%%-*}" >/dev/null 2>&1 || install_pkg "$p" || true
done

# ---------------------------------------------------------------- quyền lưu trữ
if [ ! -d "$HOME/storage" ]; then
    info "Cấp quyền truy cập bộ nhớ (để tải APK vào Download)…"
    termux-setup-storage >/dev/null 2>&1 || true
    sleep 1
fi
[ -d "$HOME/storage" ] && ok "Đã có quyền bộ nhớ" || warn "Chưa cấp quyền bộ nhớ (không bắt buộc)"

# ---------------------------------------------------------------- kiểm tra nhanh
printf "\n"
command -v python3 >/dev/null 2>&1 && ok "python3: sẵn sàng"
command -v adb     >/dev/null 2>&1 && ok "adb: sẵn sàng" || warn "adb: chưa có (sẽ dùng cách khác)"

printf "\n${G}Xong.${N} Chạy tool:\n  ${D}python3 tools/r1y.py${N}\n\n"
