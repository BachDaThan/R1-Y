#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
#  R1-Y — Cài lần đầu trên điện thoại (chạy 1 lần)
#
#  Cách dùng (chọn 1):
#    A) Trong Termux, dán 1 dòng duy nhất:
#         curl -sL https://raw.githubusercontent.com/BachDaThan/R1-Y/main/tools/android/install.sh | bash
#    B) Đã tải repo:  bash tools/android/install.sh
#
#  Script này làm mọi thứ để sau đó bạn CHỈ CẦN CHẠM 1 LẦN:
#    • cài python + git + adb (an toàn, chỉ dùng pkg chính thức)
#    • tải firmware R1-Y về ~/R1-Y
#    • tạo phím tắt "R1-Y" trên thanh phím Termux  ← chạm là chạy
#    • tạo lối tắt ngoài màn hình chính (Termux:Widget)
# ==========================================================
set -u

G='\033[92m'; Y='\033[93m'; R='\033[91m'; B='\033[94m'; C='\033[96m'
N='\033[0m'; D='\033[2m'; BOLD='\033[1m'
ok()   { printf "  ${G}✔${N} %s\n" "$1"; }
info() { printf "  ${B}•${N} %s\n" "$1"; }
warn() { printf "  ${Y}!${N} %s\n" "$1"; }
err()  { printf "  ${R}✘${N} %s\n" "$1"; }
title(){ printf "\n${C}══ %s ══${N}\n" "$1"; }

LOGF="${TMPDIR:-${PREFIX:-/tmp}/tmp}/r1y-setup.log"
mkdir -p "$(dirname "$LOGF")" 2>/dev/null || LOGF="/dev/null"
REPO_URL="https://github.com/BachDaThan/R1-Y"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TOOL_DIR="$HOME_DIR/R1-Y"

pkgq() {  # pkg install im lặng
    pkg install -y "$1" >"$LOGF" 2>&1 || {
        pkg update -y >/dev/null 2>&1 || true
        pkg install -y "$1" >"$LOGF" 2>&1
    }
}

title "R1-Y · Chuẩn bị Termux"
printf "  ${D}Cài xong bạn chỉ cần chạm 1 lần là tool chạy.${N}\n\n"

# ---- 0. kiểm tra đang ở Termux ----
if [ ! -d /data/data/com.termux ] && ! printf '%s' "${PREFIX:-}" | grep -q com.termux; then
    warn "Không phải Termux — vẫn cài được nhưng phần phím tắt sẽ bỏ qua."
fi

# ---- 1. gói cần thiết ----
command -v pkg >/dev/null 2>&1 || { err "Không thấy lệnh 'pkg' — bạn đang không ở trong Termux."; exit 1; }

info "Đang chuẩn bị gói (python, git, adb)…"
for p in python git; do
    if command -v "$p" >/dev/null 2>&1; then ok "$p đã có"
    else pkgq "$p" && ok "$p đã cài" || warn "Không cài được $p"
    fi
done
if command -v adb >/dev/null 2>&1; then ok "adb đã có"
else pkgq android-tools && ok "adb đã cài" || warn "Không cài được adb (vẫn dùng được mục 3b — không cần adb)"
fi
# công cụ phụ, thiếu cũng không sao
for p in curl unzip; do
    command -v "$p" >/dev/null 2>&1 || pkgq "$p" >/dev/null 2>&1 || true
done

# ---- 2. tải firmware ----
if [ -f "$TOOL_DIR/tools/r1y.py" ]; then
    ok "Đã có firmware ở ~/R1-Y"
    git -C "$TOOL_DIR" pull --quiet --rebase >/dev/null 2>&1 && ok "Đã cập nhật bản mới nhất" || true
else
    info "Đang tải firmware R1-Y…"
    if command -v git >/dev/null 2>&1; then
        rm -rf "$TOOL_DIR" 2>/dev/null
        git clone --quiet --depth 1 "$REPO_URL" "$TOOL_DIR" >"$LOGF" 2>&1 && ok "Đã tải về ~/R1-Y" || warn "Tải bằng git lỗi"
    fi
    if [ ! -f "$TOOL_DIR/tools/r1y.py" ] && command -v curl >/dev/null 2>&1; then
        curl -sL "$REPO_URL/archive/refs/heads/main.zip" -o "$HOME_DIR/r1y.zip" && \
        unzip -q -o "$HOME_DIR/r1y.zip" -d "$HOME_DIR" && \
        rm -rf "$TOOL_DIR" && mv "$HOME_DIR/R1-Y-main" "$TOOL_DIR" && ok "Đã tải về ~/R1-Y (zip)"
    fi
fi

[ -f "$TOOL_DIR/tools/r1y.py" ] || { err "Không tải được firmware. Kiểm tra mạng rồi chạy lại."; exit 1; }

# ---- 3. file chạy 1 chạm ----
LAUNCH="$HOME_DIR/r1-y.sh"
cat > "$LAUNCH" << 'LAUNCH_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# R1-Y — chạm 1 lần là chạy (do tools/android/install.sh tạo)
exec bash "$HOME/R1-Y/tools/r1y-termux.sh" "$@"
LAUNCH_EOF
chmod +x "$LAUNCH"
ok "Đã tạo lối tắt: ~/r1-y.sh"

# ---- 4. phím tắt trên thanh phím Termux (chạm là chạy) ----
mkdir -p "$HOME_DIR/.termux"
if [ -f "$HOME_DIR/.termux/termux.properties" ]; then
    if grep -q "R1-Y" "$HOME_DIR/.termux/termux.properties" 2>/dev/null; then
        ok "Phím tắt R1-Y đã có"
    else
        {
            printf '\n# R1-Y: chạm để mở tool cài firmware\n'
            printf 'extra-keys = [["R1-Y","UP","CTRL","ALT","LEFT","DOWN","RIGHT"]]\n'
        } >> "$HOME_DIR/.termux/termux.properties"
        ok "Đã thêm phím R1-Y vào thanh phím Termux"
    fi
else
    printf 'extra-keys = [["R1-Y","UP","CTRL","ALT","LEFT","DOWN","RIGHT"]]\n' > "$HOME_DIR/.termux/termux.properties"
    ok "Đã tạo phím R1-Y trên thanh phím Termux"
fi

# ---- 5. lối tắt ngoài màn hình chính (Termux:Widget) ----
SHORTCUTS="$HOME_DIR/.shortcuts"
mkdir -p "$SHORTCUTS"
cat > "$SHORTCUTS/R1-Y" << 'SH_EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec bash "$HOME/R1-Y/tools/r1y-termux.sh"
SH_EOF
chmod +x "$SHORTCUTS/R1-Y"
ok "Đã tạo lối tắt ngoài màn hình chính (cần app Termux:Widget)"

# ---- 6. hướng dẫn ----
printf "\n${G}  ════════ XONG! Dùng thế này ════════${N}\n\n"
printf "  ${BOLD}Cách 1 — nhanh nhất (1 chạm)${N}\n"
printf "     Mở Termux → chạm phím ${BOLD}R1-Y${N} trên thanh phím ở dưới.\n"
printf "     ${D}(lần đầu vào Termux, bấm phím Menu/ba chấm → Restart nếu chưa thấy)${N}\n\n"
printf "  ${BOLD}Cách 2 — từ màn hình chính${N}\n"
printf "     Cài app ${BOLD}Termux:Widget${N} (F-Droid) → giữ màn hình chính → Tiện ích → Termux\n"
printf "     → chọn ${BOLD}R1-Y${N}. Chạm là chạy.\n\n"
printf "  ${BOLD}Cách 3 — gõ tay${N}\n"
printf "     %sbash ~/r1-y.sh%s\n\n" "$C" "$N"
printf "  ${D}Bỏ qua được bước gõ lệnh — không cần nhớ gì cả.${N}\n\n"
