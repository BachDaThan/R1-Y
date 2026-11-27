#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
#  R1-Y — Cài / cấu hình / cập nhật firmware loa Phicomm R1
#  Dành cho ĐIỆN THOẠI (Android + Termux)
#
#  Dùng:
#    • Bấm 1 cái: mở Termux → chạm vào phím "R1-Y" trên thanh phím
#      (widget "Termux:Widget" ngoài màn hình chính cũng dùng file này)
#    • Hoặc gõ:  bash ~/r1-y.sh
#
#  Tự lo hết: tải firmware về, cài vào loa, cấu hình WiFi, đổi firmware.
#  Mọi lỗi được dịch sang tiếng Việt dễ hiểu, không bắt bạn đọc log kỹ thuật.
# ==========================================================
set -u

# ---------- màu ----------
G='\033[92m'; Y='\033[93m'; R='\033[91m'; B='\033[94m'; C='\033[96m'
N='\033[0m'; D='\033[2m'; BOLD='\033[1m'
ok()   { printf "  ${G}✔${N} %s\n" "$1"; }
info() { printf "  ${B}•${N} %s\n" "$1"; }
warn() { printf "  ${Y}!${N} %s\n" "$1"; }
err()  { printf "  ${R}✘${N} %s\n" "$1"; }
title(){ printf "\n${C}══ %s ══${N}\n" "$1"; }
ask()  { printf "  ${Y}?${N} %s " "$1"; }

# Termux không có /tmp
LOGF="${TMPDIR:-${PREFIX:-/tmp}/tmp}/r1y-install.log"
mkdir -p "$(dirname "$LOGF")" 2>/dev/null || LOGF="/dev/null"

REPO_URL="https://github.com/BachDaThan/R1-Y"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TOOL_DIR="$HOME_DIR/R1-Y"
TOOL="$TOOL_DIR/tools/r1y.py"

banner() {
    printf "\n${C}  ╔════════════════════════════════════════════╗${N}\n"
    printf "${C}  ║${N}   ${BOLD}R1-Y — Tool trên điện thoại${N}              ${C}║${N}\n"
    printf "${C}  ║${N}   ${D}Loa Phicomm R1 · không cần máy tính${N}      ${C}║${N}\n"
    printf "${C}  ╚════════════════════════════════════════════╝${N}\n\n"
}

# ---------- 1. Chuẩn bị môi trường ----------
ensure_python() {
    command -v python3 >/dev/null 2>&1 && return 0
    command -v python  >/dev/null 2>&1 && return 0
    # chưa có python → cài (im lặng, không làm ồn)
    command -v pkg >/dev/null 2>&1 || return 1
    info "Đang chuẩn bị lần đầu (cần mạng, ~1 phút)…"
    pkg install -y python >"$LOGF" 2>&1 || {
        pkg update -y >/dev/null 2>&1 || true
        pkg install -y python >"$LOGF" 2>&1 || return 1
    }
    command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1
}

ensure_adb() {
    command -v adb >/dev/null 2>&1 && return 0
    command -v pkg >/dev/null 2>&1 || return 1
    info "Đang cài ADB (android-tools)…"
    pkg install -y android-tools >"$LOGF" 2>&1 || {
        pkg update -y >/dev/null 2>&1 || true
        pkg install -y android-tools >"$LOGF" 2>&1 || return 1
    }
    command -v adb >/dev/null 2>&1
}

# ---------- 2. Tải firmware repo (lần đầu) ----------
ensure_repo() {
    if [ -f "$TOOL" ]; then
        # có rồi → cập nhật nhanh, im lặng
        if command -v git >/dev/null 2>&1 && [ -d "$TOOL_DIR/.git" ]; then
            git -C "$TOOL_DIR" pull --quiet --rebase >/dev/null 2>&1 || true
        fi
        return 0
    fi

    command -v pkg >/dev/null 2>&1 && command -v git >/dev/null 2>&1 || {
        command -v pkg >/dev/null 2>&1 && pkg install -y git >"$LOGF" 2>&1 || true
    }

    info "Đang tải firmware R1-Y về máy (lần đầu, ~20 giây)…"
    if command -v git >/dev/null 2>&1; then
        rm -rf "$TOOL_DIR" 2>/dev/null
        git clone --quiet --depth 1 "$REPO_URL" "$TOOL_DIR" >"$LOGF" 2>&1 && return 0
    fi
    # git hỏng → tải file zip
    if command -v curl >/dev/null 2>&1; then
        curl -sL "$REPO_URL/archive/refs/heads/main.zip" -o "$HOME_DIR/r1y.zip" 2>/dev/null && \
        (command -v unzip >/dev/null 2>&1 || pkg install -y unzip >"$LOGF" 2>&1 || true) && \
        rm -rf "$TOOL_DIR"; unzip -q "$HOME_DIR/r1y.zip" -d "$HOME_DIR" 2>/dev/null && \
        mv "$HOME_DIR/R1-Y-main" "$TOOL_DIR" 2>/dev/null && return 0
    fi
    return 1
}

# ---------- 3. Chạy tool ----------
run_tool() {
    local PY
    PY=$(command -v python3 || command -v python)
    cd "$TOOL_DIR" || return 1
    "$PY" "$TOOL" "$@"
}

main() {
    banner

    if ! ensure_python; then
        err "Chưa có Python và không cài được."
        printf "     Mở Termux, gõ: %spkg install python%s rồi chạy lại.\n\n" "$BOLD" "$N"
        printf "     Hoặc tải %sR1Y-Tool.exe%s cho máy tính.\n\n" "$BOLD" "$N"
        return 1
    fi

    if ! ensure_repo; then
        err "Không tải được firmware R1-Y (kiểm tra mạng WiFi/4G)."
        printf "     Thử lại, hoặc mở trình duyệt tải: %s%s%s\n\n" "$C" "$REPO_URL" "$N"
        return 1
    fi

    # adb thiếu thì vẫn cho vào menu (mục 3b không cần adb)
    ensure_adb || warn "Chưa có adb — vẫn cập nhật được firmware qua web (mục 3b)."

    # menu chính của tool (đã tự lo phần cấu hình)
    run_tool menu
}

main "$@"
