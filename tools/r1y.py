#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
r1y.py — Tool cài đặt firmware R1-Y cho loa Phicomm R1, từ A đến Z.

Một file Python, chỉ dùng thư viện chuẩn. Chạy được trên Windows / macOS / Linux.

    python3 tools/r1y.py

Menu:
  1) Cài mới từ đầu (A→Z)         : tìm loa, tải APK từ GitHub, cài, cấp quyền, cấu hình
  2) Cập nhật firmware            : so bản trên loa với GitHub Releases mới nhất
  3) Cài bản build trong máy      : dùng file APK có sẵn (dev)
  4) Cấu hình máy chủ YouTube     : nhập mảng resolver [] hoặc máy chủ riêng
  5) Kiểm tra loa                 : phiên bản, trạng thái, máy chủ đang dùng
  6) Gỡ cài đặt / khôi phục       : gỡ R1-Y, bật lại app Phicomm gốc
  8) Thoát

Chạy không cần hỏi (dùng cho CI / script khác):
  python3 tools/r1y.py install --ip 192.168.2.20 --yes
  python3 tools/r1y.py update  --ip 192.168.2.20 --yes
  python3 tools/r1y.py status  --ip 192.168.2.20
  python3 tools/r1y.py config  --ip 192.168.2.20 --resolvers '[{"type":"custom","url":"http://192.168.2.130:8091"}]'

Nguồn firmware: GitHub Releases của BachDaThan/R1-Y (repo này). Không nguồn nào khác.
"""

import argparse
import json
import os
import platform
import re
import sys


def _setup_utf8_console():
    """
    Windows: console mặc định dùng cp1252/cp437 → in tiếng Việt là crash
    (UnicodeEncodeError: 'charmap' codec...). Chuyển sang UTF-8 và bật
    chế độ thay thế ký tự để KHÔNG BAO GIỜ crash vì chữ.
    """
    def _safe(stream):
        """Đổi sang UTF-8 nếu bảng mã hiện tại không in được tiếng Việt."""
        try:
            enc = (getattr(stream, "encoding", "") or "").lower()
            if enc.replace("-", "") in ("utf8", "utf_8"):
                return
            if hasattr(stream, "reconfigure"):
                stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            try:  # Python < 3.7
                import io
                stream.flush()
                if hasattr(sys, "stdout"):
                    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
            except Exception:
                pass

    _safe(sys.stdout)
    _safe(sys.stderr)

    if os.name != "nt":
        return
    # đặt code page console sang 65001 (UTF-8)
    try:
        import ctypes
        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        ctypes.windll.kernel32.SetConsoleCP(65001)
    except Exception:
        pass


_setup_utf8_console()
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import zipfile
import io
import urllib.error
import urllib.parse
import urllib.request

# ----------------------------------------------------------------- cấu hình chung
REPO = "BachDaThan/R1-Y"
GITHUB_API = "https://api.github.com/repos/%s/releases" % REPO
PACKAGE = "com.bachdathan.r1y"
APP_NAME = "R1-Y"
ACTIVITY = PACKAGE + "/.ui.MainActivity"
WEB_PORT = 8088
ADB_PORT = 5555
PHICOMM_APPS = [
    "com.phicomm.speaker.player",
    "com.phicomm.speaker.device",
    "com.phicomm.speaker.airskill",
    "com.phicomm.speaker.exceptionreporter",
]
PLATFORM_TOOLS_URL = {
    "Windows": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    "Darwin": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
    "Linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
}
IS_WIN = platform.system() == "Windows"

# ----------------------------------------------------------------- trang trí
if IS_WIN:
    try:
        import ctypes
        ctypes.windll.kernel32.SetConsoleMode(ctypes.windll.kernel32.GetStdHandle(-11), 7)
    except Exception:
        pass


class C:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[91m"
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    GRAY = "\033[90m"


def supports_color():
    if os.environ.get("NO_COLOR"):
        return False
    if IS_WIN and not os.environ.get("WT_SESSION") and not os.environ.get("TERM"):
        return False
    return sys.stdout.isatty()


if not supports_color():
    for _k in list(vars(C)):
        if _k.isupper():
            setattr(C, _k, "")


def title(text):
    print("\n%s%s══ %s ══%s" % (C.BOLD, C.CYAN, text, C.RESET))


def step(n, total, text):
    print("\n%s[%s/%s]%s %s%s%s" % (C.BOLD, n, total, C.RESET, C.BOLD, text, C.RESET))


def ok(text):
    print("  %s✔%s %s" % (C.GREEN, C.RESET, text))


def info(text):
    print("  %s•%s %s" % (C.BLUE, C.RESET, text))


def warn(text):
    print("  %s!%s %s" % (C.YELLOW, C.RESET, text))


def err(text):
    print("  %s✘%s %s" % (C.RED, C.RESET, text))


def die(text, code=1):
    err(text)
    print()
    sys.exit(code)


def is_termux():
    return ("com.termux" in os.environ.get("PREFIX", "")
            or os.path.isdir("/data/data/com.termux"))


def explain_error(msg):
    """Dịch lỗi kỹ thuật thành gợi ý dễ hiểu (không bắt người dùng mò)."""
    m = (msg or "").lower()
    if "command not found" in m or "not found" in m:
        return "Thiếu công cụ cần thiết — tool sẽ thử tự cài lại."
    if "no such file" in m:
        return "Không thấy file cần dùng — kiểm tra lại đường dẫn."
    if "permission denied" in m:
        return "Không có quyền truy cập — trên Termux chạy: termux-setup-storage"
    if "connection refused" in m or "timed out" in m or "timeout" in m:
        return "Loa chưa phản hồi — kiểm tra loa đã bật và cùng WiFi."
    if "unauthorized" in m:
        return "Loa chưa cho phép máy này — bấm 'Cho phép' trên màn hình loa."
    if "install_failed" in m:
        return "Cài đặt bị từ chối — tool đã thử gỡ bản cũ; nếu vẫn lỗi, chọn bản APK khác."
    if "http 404" in m:
        return "Máy chủ không có dữ liệu này (có thể repo chưa có bản phát hành)."
    if "ssl" in m or "certificate" in m or "eof" in m or "tls" in m:
        return ("Kết nối bị ngắt khi tải (SSL/TLS) — mạng chặn hoặc yếu. "
                "Thử lại, hoặc đổi WiFi/4G. Trên Termux có thể cài: pkg install openssl-tool")
    return msg


def ensure_env(quiet=False):
    """
    Kiểm tra môi trường và tự cài thư viện còn thiếu một cách an toàn.
    - Chỉ dùng trình quản lý gói chính thức của hệ điều hành / Termux.
    - Không tải và chạy script từ mạng.
    - Thiếu gì không cài được thì vẫn tiếp tục (báo nhẹ, không chặn).
    """
    missing = []
    if not shutil.which("adb") and not ADB:
        missing.append("adb")
    if is_termux():
        pkgs = []
        if missing:
            pkgs.append("android-tools")
        if not shutil.which("curl"):
            pkgs.append("curl")
        if not pkgs:
            return True
        if not quiet:
            info("Đang tự cài thêm: %s" % ", ".join(pkgs))
        try:
            run(["pkg", "install", "-y"] + pkgs, timeout=600, quiet=True)
        except Exception:
            try:
                run(["pkg", "update", "-y"], timeout=300, quiet=True)
                run(["pkg", "install", "-y"] + pkgs, timeout=600, quiet=True)
            except Exception as e:
                if not quiet:
                    warn("Không cài được tự động (%s) — vẫn tiếp tục." % explain_error(str(e)))
                return False
        if not quiet:
            ok("Đã chuẩn bị xong công cụ cần thiết.")
        return True

    # PC: chỉ nhắc bằng lệnh cài chuẩn, không tự chạy apt (cần sudo)
    if missing and not quiet:
        if platform.system() == "Linux":
            info("Nếu cần adb, cài bằng: sudo apt install android-tools-adb")
        elif platform.system() == "Darwin":
            info("Nếu cần adb: brew install android-platform-tools")
        else:
            info("Nếu cần adb: tải Android Platform Tools từ developer.android.com")
    return True


def ask(prompt, default=""):
    """Hỏi người dùng; trả về default khi nhập rỗng."""
    suffix = (" %s[%s]%s" % (C.GRAY, default, C.RESET)) if default else ""
    try:
        v = input("%s?%s %s%s: " % (C.YELLOW, C.RESET, prompt, suffix)).strip()
    except (EOFError, KeyboardInterrupt):
        print()
        die("Đã huỷ.")
    return v if v else default


def confirm(prompt, default=True):
    d = "Có" if default else "Không"
    v = ask("%s (%s/không)" % (prompt, d), d).lower()
    return v in ("c", "có", "co", "y", "yes", "1", "true", "đồng ý", "dong y")


def banner():
    print("%s" % C.CYAN)
    print("  ╔══════════════════════════════════════════════╗")
    print("  ║   %sR1-Y%s%s — Tool cài đặt firmware A→Z      ║" % (C.BOLD + C.RESET + C.CYAN, C.RESET, C.CYAN))
    print("  ║   %sLoa Phicomm R1 · nguồn: %s%s" % (C.DIM, REPO, C.RESET + C.CYAN))
    print("  ╚══════════════════════════════════════════════╝")
    print(C.RESET)


# ----------------------------------------------------------------- gọi lệnh
def run(cmd, timeout=600, check=False, quiet=False, input_text=None):
    """Chạy lệnh, trả về (returncode, stdout+stderr)."""
    if not quiet:
        print("    %s$ %s%s" % (C.GRAY, " ".join(cmd), C.RESET))
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           input=input_text,
                           encoding="utf-8", errors="replace")
        out = ((p.stdout or "") + (p.stderr or "")).strip()
        if check and p.returncode != 0:
            raise RuntimeError(out or ("lệnh lỗi: " + " ".join(cmd)))
        return p.returncode, out
    except FileNotFoundError:
        raise RuntimeError("Không tìm thấy lệnh: %s" % cmd[0])
    except subprocess.TimeoutExpired:
        raise RuntimeError("Lệnh quá thời gian chờ: %s" % " ".join(cmd))


# ----------------------------------------------------------------- adb
ADB = None
ADB_MAX_CMD_TIMEOUT = 180      # giây — mọi lệnh adb đơn lẻ không chờ lâu hơn


def find_adb():
    """Tìm adb: PATH -> biến môi trường SDK -> vị trí quen thuộc -> tải platform-tools."""
    global ADB
    if ADB:
        return ADB
    exe = "adb.exe" if IS_WIN else "adb"
    found = shutil.which("adb")
    if found:
        ADB = found
        return ADB

    bases = []
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env):
            bases.append(os.path.join(os.environ[env], "platform-tools"))
    home = os.path.expanduser("~")
    if IS_WIN:
        bases += [os.path.join(os.environ.get("LOCALAPPDATA", home), "Android", "Sdk", "platform-tools"),
                  os.path.join(home, "AppData", "Local", "Android", "Sdk", "platform-tools")]
    elif platform.system() == "Darwin":
        bases += [os.path.join(home, "Library", "Android", "sdk", "platform-tools"),
                  "/usr/local/share/android-sdk/platform-tools"]
    else:
        bases += [os.path.join(home, "Android", "Sdk", "platform-tools"),
                  "/usr/lib/android-sdk/platform-tools"]
    for b in bases:
        cand = os.path.join(b, exe)
        if os.path.isfile(cand):
            ADB = cand
            return ADB

    # Tải platform-tools vào thư mục tool
    if confirm("Chưa có adb trên máy. Tự tải Android Platform Tools (~15MB)?", True):
        ADB = download_platform_tools()
        if ADB:
            return ADB
    die("Cần adb. Tải thủ công: https://developer.android.com/tools/releases/platform-tools")


def download_platform_tools():
    sysname = platform.system()
    url = PLATFORM_TOOLS_URL.get(sysname)
    if not url:
        warn("Hệ điều hành %s chưa hỗ trợ tự tải, hãy cài adb thủ công." % sysname)
        return None
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_tools")
    os.makedirs(dest, exist_ok=True)
    info("Đang tải platform-tools từ Google…")
    try:
        tmp = download_bytes(url, "platform-tools.zip")
    except Exception as e:
        warn("Tải thất bại: %s" % e)
        return None
    try:
        if url.endswith(".zip"):
            with zipfile.ZipFile(tmp) as z:
                z.extractall(dest)
        else:
            with tarfile.open(tmp) as t:
                t.extractall(dest)
    except Exception as e:
        warn("Giải nén thất bại: %s" % e)
        return None
    finally:
        try:
            os.unlink(tmp)
        except Exception:
            pass
    exe = "adb.exe" if IS_WIN else "adb"
    adb = os.path.join(dest, "platform-tools", exe)
    if os.path.isfile(adb):
        if not IS_WIN:
            os.chmod(adb, 0o755)
        ok("Đã có adb: %s" % adb)
        return adb
    warn("Không tìm thấy adb sau khi giải nén.")
    return None


def adb(*args, **kw):
    """Gọi adb. Luôn đặt thời gian chờ để không bị treo trên điện thoại."""
    kw.setdefault("timeout", 60)
    # pm install trên loa Android 5.1 đôi khi treo và shell đóng giữa lúc chạy
    # (adb báo "error: closed"). Chờ tối đa 90 giây rồi sang cách khác,
    # KHÔNG để người dùng đứng im 15 phút như trước.
    if kw["timeout"] > ADB_MAX_CMD_TIMEOUT:
        kw["timeout"] = ADB_MAX_CMD_TIMEOUT
    kw.pop("allow_fail", None)
    exe = find_adb()
    if not exe:
        raise RuntimeError("Chưa có adb — chạy: bash tools/termux_setup.sh")
    return run([exe] + list(args), **kw)


def adb_out(*args, **kw):
    """Chạy adb, trả về stdout. Không nuốt lỗi — ném RuntimeError kèm thông báo thật."""
    rc, out = adb(*args, **kw)
    if rc != 0 and not kw.get("allow_fail"):
        low = out.lower()
        if "unauthorized" in low:
            raise RuntimeError("Loa chưa cho phép máy này — bấm Cho phép trên màn hình loa")
        if "no devices" in low or "device not found" in low:
            raise RuntimeError("Chưa kết nối được loa qua ADB WiFi")
        if "offline" in low:
            raise RuntimeError("Loa đang offline — tắt/bật lại Gỡ lỗi ADB trên loa")
        if "more than one device" in low:
            raise RuntimeError("Có nhiều thiết bị ADB — chỉ bật 1 loa rồi chạy lại")
        raise RuntimeError(out.strip() or ("adb lỗi: " + " ".join(args)))
    return out


# ----------------------------------------------------------------- mạng / tải
def download_bytes(url, name_hint="file.bin", progress=True, retries=3):
    """Tải URL về file tạm, trả về đường dẫn. Tự thử lại khi mạng chập chờn."""
    last = None
    for attempt in range(1, retries + 1):
        try:
            return _download_once(url, name_hint, progress)
        except Exception as e:
            last = e
            msg = str(e).lower()
            fatal = any(k in msg for k in ("http 404", "http 403", "http 401"))
            if fatal or attempt == retries:
                break
            warn("Tải lỗi (lần %d/%d): %s — thử lại…" % (attempt, retries, explain_error(str(e))))
            time.sleep(2 * attempt)
    raise RuntimeError(explain_error(str(last)))


def _download_once(url, name_hint="file.bin", progress=True):
    """Một lần tải."""
    req = urllib.request.Request(url, headers={
        "User-Agent": "r1y-tool/1.4",
        "Accept": "application/vnd.github+json",
    })
    fd, path = tempfile.mkstemp(prefix="r1y-", suffix=os.path.splitext(name_hint)[1] or ".bin")
    total = 0
    try:
        with urllib.request.urlopen(req, timeout=60) as r, os.fdopen(fd, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(262144)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if progress and total > 0 and sys.stdout.isatty():
                    pct = done * 100 // total
                    sys.stdout.write("\r    %s[%3d%%] %5.1f / %5.1f MB%s" %
                                     (C.GRAY, pct, done / 1048576.0, total / 1048576.0, C.RESET))
                    sys.stdout.flush()
        if progress and total > 0 and sys.stdout.isatty():
            sys.stdout.write("\r" + " " * 60 + "\r")
        return path
    except urllib.error.HTTPError as e:
        try:
            os.unlink(path)
        except Exception:
            pass
        raise RuntimeError("HTTP %s (%s)" % (e.code, url))
    except Exception as e:
        try:
            os.unlink(path)
        except Exception:
            pass
        raise RuntimeError(str(e))


def http_json(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": "r1y-tool/1.4",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def http_post_json(url, payload, timeout=25):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json",
        "User-Agent": "r1y-tool/1.4",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8", "replace")
        try:
            return json.loads(body)
        except Exception:
            return {"raw": body}


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 9))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def scan_lan_for_adb(port=ADB_PORT, timeout=0.25, max_workers=64):
    """Quét nhanh LAN tìm cổng 5555 (ADB over WiFi) mở."""
    ip = local_ip()
    if not ip.startswith(("192.168.", "10.", "172.")):
        return []
    base = ip.rsplit(".", 1)[0] + "."
    from concurrent.futures import ThreadPoolExecutor

    def probe(i):
        host = base + str(i)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(timeout)
            if s.connect_ex((host, port)) == 0:
                return host
        except Exception:
            pass
        finally:
            try:
                s.close()
            except Exception:
                pass
        return None

    found = []
    with ThreadPoolExecutor(max_workers=max_workers) as ex:
        for r in ex.map(probe, range(1, 255)):
            if r:
                found.append(r)
    return found


# ----------------------------------------------------------------- chọn loa
def devices_raw():
    """Trả về list (serial, trạng thái) từ `adb devices` — gồm cả unauthorized/offline."""
    out = adb_out("devices", allow_fail=True)
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def list_devices():
    return [serial for serial, state in devices_raw() if state == "device"]


def explain_device_states(rows):
    """Dịch trạng thái adb sang việc người dùng cần làm."""
    hints = []
    for serial, state in rows:
        if state == "unauthorized":
            hints.append("%s: chưa cho phép → bấm “Cho phép” trên màn hình loa" % serial)
        elif state == "offline":
            hints.append("%s: offline → tắt/bật lại Gỡ lỗi ADB trên loa" % serial)
        elif state == "connecting":
            hints.append("%s: đang kết nối → chờ thêm vài giây" % serial)
        elif state == "device":
            hints.append("%s: sẵn sàng" % serial)
        else:
            hints.append("%s: %s" % (serial, state))
    return hints


def scan_for_loa(progress=True):
    """
    Tự dò loa trong WiFi nhà: tìm máy mở cổng 8088 (web) hoặc 5555 (adb).
    Dùng khi IP loa đổi hoặc người dùng không biết IP.
    """
    base = local_ip().rsplit(".", 1)[0] + "."
    found = []
    lock = threading.Lock()

    def probe(i):
        host = base + str(i)
        for port in (WEB_PORT, ADB_PORT):
            try:
                c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                c.settimeout(0.35)
                if c.connect_ex((host, port)) == 0:
                    with lock:
                        found.append((host, port))
                    c.close()
                    return
                c.close()
            except Exception:
                pass

    if progress:
        info("Đang dò loa trong %s0/24…" % base)
    threads = [threading.Thread(target=probe, args=(i,), daemon=True) for i in range(1, 255)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=2)

    # ưu tiên máy mở 8088 (chắc chắn là loa R1-Y)
    found.sort(key=lambda x: 0 if x[1] == WEB_PORT else 1)
    return found


def scan_for_loa_ip(progress=True):
    """Trả về IP loa đầu tiên dò được (hoặc None)."""
    hosts = scan_for_loa(progress)
    return hosts[0][0] if hosts else None


def port_open(ip, port, timeout=1.0):
    """Cổng có mở không (nhanh, không cần adb)."""
    try:
        c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        c.settimeout(timeout)
        ok_ = c.connect_ex((ip, port)) == 0
        c.close()
        return ok_
    except Exception:
        return False


def web_alive(ip, port=None, timeout=4):
    """Loa còn sống không? Thử mở API web của loa (nhanh, không cần adb)."""
    try:
        http_json("http://%s:%d/api/status" % (ip, port or WEB_PORT), timeout=timeout)
        return True
    except Exception:
        return False


def adb_connect_smart(host, port=None, tries=3, verbose=True):
    """
    Kết nối ADB WiFi "chịu lỗi" — xử lý đúng các ca thực tế:
      - "already connected" nhưng devices lại offline  → disconnect rồi connect lại
      - adb server giữ kết nối cũ hỏng                → kill-server
      - loa chậm phản hồi (Android 5.1)               → chờ tối đa ~10 giây/lần
    Trả về serial nếu OK, None nếu không. Tổng thời gian tối đa ~45 giây.
    """
    port = port or ADB_PORT
    target = "%s:%d" % (host, port)
    last = ""
    t0 = time.time()

    for attempt in range(1, tries + 1):
        if time.time() - t0 > 45:
            break
        st = dict(devices_raw()).get(target)

        if st == "device":
            if verbose:
                ok("Đã kết nối %s" % target)
            return target

        if st == "unauthorized":
            warn("Loa chưa cho phép máy này (unauthorized).")
            print("  → Nhìn màn hình loa, bấm %sCho phép%s (tick “luôn cho phép”)."
                  % (C.BOLD, C.RESET))
            for _ in range(20):                      # chờ tối đa ~20 giây
                time.sleep(1)
                if dict(devices_raw()).get(target) == "device":
                    ok("Đã kết nối %s" % target)
                    return target
            continue

        # đang offline hoặc chưa có → dọn kết nối cũ
        if st:
            adb("disconnect", target, quiet=True, allow_fail=True)
        if attempt == 2:
            adb("kill-server", quiet=True, allow_fail=True)
            adb("start-server", quiet=True, allow_fail=True)

        rc, out = adb("connect", target, timeout=40, allow_fail=True)
        last = (out or "").strip()
        low = last.lower()

        # adb báo "already connected" mà vẫn offline → ép ngắt hẳn rồi nối lại
        if "already connected" in low:
            adb("disconnect", target, quiet=True, allow_fail=True)
            time.sleep(1)
            rc, out = adb("connect", target, timeout=40, allow_fail=True)
            last = (out or "").strip()
            low = last.lower()

        # cổng đóng hẳn → thử lại cũng vô ích, báo luôn
        if "refused" in low or "cannot connect" in low or "unable to connect" in low:
            if verbose:
                warn("Loa không mở cổng %d: %s" % (port, last))
            return None

        # chờ loa lên "device" (tối đa 10 giây)
        for _ in range(10):
            cur = dict(devices_raw()).get(target)
            if cur == "device":
                if verbose:
                    ok("Đã kết nối %s" % target)
                return target
            if cur == "unauthorized":
                break
            time.sleep(1)

        if verbose:
            rows = devices_raw()
            if rows:
                warn("Lần %d/%d: %s" % (attempt, tries, ", ".join(explain_device_states(rows))))
            else:
                warn("Lần %d/%d: adb chưa thấy loa (%s)" % (attempt, tries, last or "không phản hồi"))
        time.sleep(1)

    return None


HOTSPOT_IP = "192.168.43.1"   # IP loa khi phát WiFi riêng (chế độ cài đặt)


def connect_device(ip=None, interactive=True):
    """Bảo đảm có 1 loa đang kết nối, trả về (serial, ip)."""
    find_adb()
    title("Tìm loa Phicomm R1")

    # Trên firmware gốc, ADB (cổng 5555) CHỈ mở khi loa phát WiFi riêng.
    # Nên thử 192.168.43.1 trước — đúng cách các tool khác vẫn làm.
    if not ip:
        rows = devices_raw()
        if any(st == "device" for _, st in rows):
            dev = [d for d, st in rows if st == "device"][0]
            return dev, (dev.split(":")[0] if ":" in dev else None)
        if web_alive(HOTSPOT_IP, timeout=2) or port_open(HOTSPOT_IP, ADB_PORT):
            info("Thấy loa ở %s (WiFi do loa phát)…" % HOTSPOT_IP)
            if adb_connect_smart(HOTSPOT_IP):
                return "%s:%d" % (HOTSPOT_IP, ADB_PORT), HOTSPOT_IP
            warn("Thấy loa nhưng chưa nối được ADB. Loa chỉ nhận MỘT kết nối adb:")
            print("  • Tắt tool/adb khác đang nối loa (Android Studio, tool khác…)")
            print("  • Rút điện loa 10 giây, cắm lại, chờ ~1 phút, giữ nút 5 giây rồi chạy lại")
        else:
            info("Chưa thấy loa ở %s. Cách cài ĐÚNG với loa còn firmware gốc:" % HOTSPOT_IP)
            print("  1) Cắm điện, chờ ~1 phút, giữ nút trên đỉnh loa ~5 giây → loa phát WiFi %sPhicomm R1%s"
                  % (C.BOLD, C.RESET))
            print("  2) Nối điện thoại/PC vào WiFi đó (tool đã tải firmware trước lúc còn internet)")
            print("  3) Chạy lại tool: cài NGAY qua %s, xong mới cho loa vào WiFi nhà" % HOTSPOT_IP)

    if ip:
        ip = re.sub(r":\d+$", "", ip)
        info("Kết nối ADB WiFi tới %s…" % ip)
        serial_ok = adb_connect_smart(ip)
        if serial_ok:
            return serial_ok, ip
        # IP có thể đã đổi (DHCP) → tự dò lại trong WiFi
        if interactive:
            new_ip = scan_for_loa_ip()
            if new_ip and new_ip != ip:
                ok("Loa có thể đã đổi IP: %s (đang thử…)" % new_ip)
                if adb_connect_smart(new_ip):
                    return "%s:%d" % (new_ip, ADB_PORT), new_ip
        rc, out = adb("connect", "%s:%d" % (ip, ADB_PORT), timeout=30, allow_fail=True)
        txt = out.strip()
        low = txt.lower()
        if "failed to authenticate" in low or "authentication" in low:
            warn("Loa chưa cho phép máy này (authentication failed).")
            print("  → Nhìn màn hình loa: bấm %sCho phép%s (tick “luôn cho phép”)." % (C.BOLD, C.RESET))
            print("  → Không thấy hộp thoại? Cắm cáp USB một lần rồi chạy lại.")
        elif "refused" in low or "cannot connect" in low or "unable" in low:
            warn("Loa không mở cổng 5555: %s" % (txt or "không rõ"))
            print("  • Loa phải %sbật Gỡ lỗi ADB%s (Tùy chọn nhà phát triển)" % (C.BOLD, C.RESET))
            print("  • Sau khi bật ADB, loa thường cần %skhởi động lại 1 lần%s" % (C.BOLD, C.RESET))
        elif "connected" in low or "already" in low:
            ok("Đã kết nối %s" % ip)
        else:
            warn("adb trả lời: %s" % (txt or "(không có gì)"))

    rows = devices_raw()
    devs = [d for d, st in rows if st == "device"]
    if not devs and rows:
        warn("Có kết nối TCP nhưng chưa dùng được:")
        for h in explain_device_states(rows):
            print("  • %s" % h)
    if devs:
        ok("Thấy %d thiết bị: %s" % (len(devs), ", ".join(devs)))
        # Ưu tiên serial dạng ip:5555
        serial = sorted(devs, key=lambda d: (0 if ":" in d else 1))[0]
        return serial, (serial.split(":")[0] if ":" in serial else None)

    if not interactive:
        rows = devices_raw()
        msg = ["Chưa kết nối được loa qua ADB."]
        if ip:
            msg.append("  • Đã thử %s:%d nhưng chưa dùng được" % (ip, ADB_PORT))
        for h in explain_device_states(rows):
            msg.append("  • %s" % h)
        if not rows:
            msg += ["  • Loa chưa bật Gỡ lỗi ADB, hoặc chưa cho phép máy này",
                    "  • Bật ADB rồi khởi động lại loa, sau đó chạy lại",
                    "  • Kiểm tra điện thoại và loa cùng 1 WiFi"]
        if ip:
            msg.append("  • Không cần adb: python3 tools/r1y.py webinstall --ip %s" % ip)
        die("\n".join(msg))

    print()
    warn("Chưa thấy loa nào.")
    print("  Cách bật ADB trên loa Phicomm R1:")
    print("    1. Cài đặt → Giới thiệu / Tùy chọn nhà phát triển → bật %sGỡ lỗi ADB/USB%s" % (C.BOLD, C.RESET))
    print("    2. Ghi lại IP loa ở Cài đặt → WiFi (hoặc xem trên router)")
    print("    3. Loa và máy tính phải %scùng một WiFi%s" % (C.BOLD, C.RESET))
    print()

    if confirm("Tự quét WiFi nhà để tìm loa (cổng 5555)?", True):
        info("Đang quét %s…" % (local_ip().rsplit(".", 1)[0] + ".0/24"))
        hosts = scan_lan_for_adb()
        if hosts:
            ok("Thấy %d máy mở cổng 5555: %s" % (len(hosts), ", ".join(hosts)))
            host = hosts[0] if len(hosts) == 1 else ask("Chọn IP loa", hosts[0])
            if adb_connect_smart(host):
                return host + ":" + str(ADB_PORT), host
        else:
            warn("Không quét thấy máy nào mở cổng 5555.")

    manual = ask("Nhập IP loa (Enter để dừng)")
    if not manual:
        die("Không có loa. Hãy bật ADB WiFi rồi chạy lại tool.")
    manual = re.sub(r":\d+$", "", manual)
    serial_ok = adb_connect_smart(manual)
    if serial_ok:
        return serial_ok, manual
    devs = []
    out = ""
    rc = 1
    if not devs:
        detail = out.strip()
        print()
        rows = devices_raw()
        if rows:
            for h in explain_device_states(rows):
                print("  • %s" % h)
        warn("Chưa kết nối được %s:%d" % (manual, ADB_PORT))
        if detail:
            print("  adb trả lời: %s%s%s" % (C.DIM, detail, C.RESET))
        low = detail.lower()
        if "authenticate" in low or "unauthorized" in low:
            print("  → Trên loa có hộp thoại %s“Cho phép gỡ lỗi USB”%s — bấm Cho phép." % (C.BOLD, C.RESET))
        elif "refused" in low or "unable" in low or rc != 0:
            print("  → Cổng 5555 chưa mở: bật %sGỡ lỗi ADB%s trên loa, rồi %skhởi động lại loa%s." % (C.BOLD, C.RESET, C.BOLD, C.RESET))
        # web 8088 mở được = loa sống, chỉ adb chưa bật → ưu tiên cài qua web
        if web_alive(manual):
            print()
            ok("Loa vẫn sống: %shttp://%s:%d%s mở được — chỉ ADB chưa bật." % (C.BOLD, manual, WEB_PORT, C.RESET))
            print("  → Cài được NGAY không cần ADB (loa tự tải + cài).")
            if confirm("Cập nhật qua web ngay?", True):
                flow_webinstall(argparse.Namespace(ip=manual))
                raise SystemExit(0)
        print("  • Kiểm tra điện thoại và loa cùng 1 WiFi (thử mở %shttp://%s:8088%s)" % (C.BOLD, manual, C.RESET))
        print("  • Thử tay: adb connect %s:%d" % (manual, ADB_PORT))
        print("  • Không dùng được adb? Vẫn cài được bằng: %spython3 tools/r1y.py webinstall --ip %s%s"
              % (C.BOLD, manual, C.RESET))
        die("Dừng ở bước kết nối ADB.")
    return devs[0], manual


def check_is_r1(serial):
    """Kiểm tra thiết bị có phải loa Phicomm R1 không (cảnh báo, không chặn)."""
    model = adb_out("-s", serial, "shell", "getprop", "ro.product.model").strip()
    brand = adb_out("-s", serial, "shell", "getprop", "ro.product.brand").strip()
    sdk = adb_out("-s", serial, "shell", "getprop", "ro.build.version.sdk").strip()
    info("Thiết bị: %s %s (Android API %s)" % (brand, model, sdk))
    low = (brand + " " + model).lower()
    # Bo mạch thật của loa Phicomm R1 là rk322x-box (Rockchip RK3229, Android 5.1)
    if "phicomm" in low or "rk322x" in low or low.strip().startswith("r1"):
        if "rk322x" in low:
            ok("Đúng là loa Phicomm R1 (bo mạch rk322x, Android 5.1).")
        else:
            ok("Đúng là loa Phicomm R1.")
        return True
    warn("Không nhận ra Phicomm R1 (vẫn tiếp tục).")
    return False


def device_version(serial):
    out = adb_out("-s", serial, "shell", "dumpsys", "package", PACKAGE)
    if "Unable" in out or "Unknown package" in out or not out.strip():
        return None, None
    m_name = re.search(r"versionName=(\S+)", out)
    m_code = re.search(r"versionCode=(\d+)", out)
    if not m_name and not m_code:
        return None, None
    return (m_name.group(1) if m_name else "?"), (int(m_code.group(1)) if m_code else 0)


def install_reason(serial, apk_path):
    """
    Vì sao không cài đè được? Chạy `pm install -r` MỘT lần và đọc câu lỗi thật
    mà Android trả về (tool cũ chỉ thấy "error: closed" của adb vì shell bị
    đóng giữa lúc pm đang chạy).

    Trả về mã ngắn: "signature" | "downgrade" | "system" | "space" | "" (không rõ)
    """
    import posixpath
    remote = posixpath.join("/data/local/tmp", "__r1y_check.apk")
    adb("-s", serial, "push", apk_path, remote, timeout=900, quiet=True)
    rc, out = adb("-s", serial, "shell", "/system/bin/pm", "install", "-r", "-t",
                  remote, timeout=900, allow_fail=True, quiet=True)
    adb("-s", serial, "shell", "rm", remote, quiet=True)
    return classify_install_error(out)


def classify_install_error(text):
    m = (text or "").lower()
    if "signatures do not match" in m or "install_failed_inconsistent" in m \
            or "update incompatible" in m or "signature" in m:
        return "signature"
    if "downgrade" in m:
        return "downgrade"
    if "system" in m or "not allowed" in m or "signature verification" in m:
        return "system"
    if "insufficient_storage" in m or "insufficient storage" in m or "no space" in m:
        return "space"
    return ""


def package_present(serial):
    """App còn trên loa không (dù pm hide thì vẫn còn)."""
    rc, out = adb("-s", serial, "shell", "/system/bin/pm", "path", PACKAGE,
                  quiet=True, allow_fail=True)
    return "package:" in (out or "")


def remove_app(serial):
    """
    Gỡ hẳn app khỏi loa — cần khi bản đang cài do robot GitHub build nên ĐỔI
    KEY KÝ mỗi lần (Android không cho cài đè app khác chữ ký).
    Trả về True nếu chắc chắn đã sạch.
    """
    adb("-s", serial, "shell", "am", "force-stop", PACKAGE, quiet=True, allow_fail=True)
    if not package_present(serial):
        return True
    # -k = giữ lại /data/data (cài đặt WiFi/AI) nếu ROM cho phép; nếu không
    # gỡ được thì gỡ sạch. Lần cài sau dùng đúng chữ ký mới nên vẫn cài được.
    for flags in (["-k"], [], ["--user", "0"], ["-k", "--user", "0"]):
        rc, out = adb("-s", serial, "shell", "/system/bin/pm", "uninstall",
                      *(flags + [PACKAGE]), timeout=180, allow_fail=True, quiet=True)
        if "Success" in (out or "") or not package_present(serial):
            return True
    # cài vào /data nhưng hệ thống khoá: thử thêm --user 0 rồi gỡ cho mọi user
    for extra in (["--user", "0"], ["-k", "--user", "0"]):
        rc, out = adb("-s", serial, "shell", "/system/bin/pm", "uninstall",
                      *(extra + [PACKAGE]), timeout=180, allow_fail=True, quiet=True)
        if not package_present(serial):
            return True
    return not package_present(serial)


def package_signature(serial):
    """
    Chữ ký của app đang cài. Android 5.1: `dumpsys package` in ra
    `signatures=PackageSignatures{... [<hash>]}` → lấy 8 ký tự đầu của hash
    là đủ để so sánh giữa các bản do robot build.
    """
    rc, out = adb("-s", serial, "shell", "/system/bin/dumpsys", "package", PACKAGE,
                  timeout=120, allow_fail=True, quiet=True)
    # Android 5.1 (API 22):  signatures=PackageSignatures{2f4b1c3 [a1b2c3d4]}
    # Android 7+  :           signatures=PackageSignatures{2f4b1c3 [a1b2c3d4, 9e8f...]}
    m = re.search(r"signatures=\s*PackageSignatures\W*\[([0-9a-fA-F]+)", out or "")
    if not m:
        m = re.search(r"signatures=\w*\{[^]]*?\[([0-9a-fA-F]+)", out or "")
    return m.group(1)[:8].lower() if m else ""


def device_ip(serial, fallback=None):
    out = adb_out("-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
    m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", out)
    if m:
        return m.group(1)
    if fallback:
        return fallback
    out = adb_out("-s", serial, "shell", "getprop", "dhcp.wlan0.ipaddress").strip()
    return out or None


# ----------------------------------------------------------------- GitHub releases
def gh_release(include_prerelease=False):
    """Lấy bản release mới nhất từ repo này."""
    rels = http_json(GITHUB_API + "?per_page=20")
    for r in rels:
        if r.get("draft"):
            continue
        if r.get("prerelease") and not include_prerelease:
            continue
        assets = r.get("assets") or []
        apk = None
        for a in assets:
            name = a.get("name", "").lower()
            if not name.endswith(".apk") or "debug" in name or "unsigned" in name:
                continue
            apk = a
            break
        if apk is None:
            for a in assets:
                if a.get("name", "").lower().endswith(".apk"):
                    apk = a
                    break
        if apk is None:
            continue
        return {
            "tag": (r.get("tag_name") or "").lstrip("vV"),
            "name": r.get("name") or r.get("tag_name"),
            "notes": r.get("body") or "",
            "published": r.get("published_at") or "",
            "apk_url": apk["browser_download_url"],
            "apk_name": apk["name"],
            "apk_size": apk.get("size") or 0,
            "prerelease": bool(r.get("prerelease")),
        }
    return None


def version_tuple(v):
    parts = re.findall(r"\d+", v or "")
    return tuple(int(x) for x in parts[:4]) if parts else (0,)


# ----------------------------------------------------------------- tải APK
def local_apk_from_build():
    """Tìm APK vừa build trong repo (dev)."""
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    cands = [
        os.path.join(here, "app", "app", "build", "outputs", "apk", "release", "app-release.apk"),
        os.path.join(here, "app", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
    ]
    # Release có thể bị đổi tên bởi CI
    rel_dir = os.path.join(here, "app", "app", "build", "outputs", "apk", "release")
    if os.path.isdir(rel_dir):
        for f in sorted(os.listdir(rel_dir)):
            if f.endswith(".apk"):
                cands.insert(0, os.path.join(rel_dir, f))
    for c in cands:
        if os.path.isfile(c):
            return c
    return None


def gh_debug_asset(tag):
    """Tìm file *debug*.apk trong release (nếu repo có đăng kèm)."""
    url = "https://api.github.com/repos/%s/releases/tags/%s" % (REPO, tag)
    try:
        data = http_json(url, timeout=30)
    except Exception:
        return None
    for a in data.get("assets", []):
        name = a.get("name", "")
        if name.lower().endswith(".apk") and "debug" in name.lower():
            return {"tag": tag, "prerelease": False, "apk_name": name,
                    "apk_url": a["browser_download_url"], "apk_size": a.get("size", 0),
                    "published": data.get("published_at"), "unsigned": False}
    return None


def apk_is_signed(path):
    """APK đã ký chưa? Kiểm tra META-INF có .RSA/.DSA/.EC/.SF (chữ ký v1)."""
    try:
        import zipfile
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC", ".SF")):
                    return True
    except Exception:
        return True  # không đọc được thì đừng chặn oan
    return False


def warn_if_unsigned(rel):
    """APK chưa ký thì Android không cài được — nói rõ và đề xuất bản debug."""
    if not rel.get("unsigned"):
        return False
    warn("Bản APK này CHƯA ĐƯỢC KÝ (unsigned) — Android sẽ từ chối cài.")
    print("  Lý do: bản release cần keystore để ký, repo chưa cấu hình.")
    print("  Cách xử lý (chọn 1):")
    print("    1. Cài bản debug (có sẵn, ký bằng key debug, cài được ngay):")
    print("       python3 tools/r1y.py install --ip <IP_LOA> --source debug")
    print("    2. Tự ký: xem docs/TERMUX.md mục “Ký APK release”")
    return True


def get_apk(source="github", apk_path=None, include_prerelease=False):
    """
    Trả về (đường dẫn APK, tuple phiên bản, thông tin release|None).
    source: github (bản phát hành) | debug (ưu tiên APK debug nếu có) | local
    """
    prefer_debug = (source == "debug")
    if source == "local":
        p = apk_path or local_apk_from_build()
        if not p or not os.path.isfile(p):
            die("Không thấy APK để cài. Build trước: cd app && ./gradlew assembleRelease")
        ok("Dùng APK có sẵn: %s" % p)
        return p, None, None

    if apk_path:
        if not os.path.isfile(apk_path):
            die("File APK không tồn tại: %s" % apk_path)
        ok("Dùng APK: %s" % apk_path)
        return apk_path, None, None

    title("Lấy firmware từ GitHub")
    info("Nguồn: github.com/%s (bản phát hành)" % REPO)
    try:
        rel = gh_release(include_prerelease)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            die("Repo %s chưa có bản phát hành (Release) nào.\n"
                "  → Chủ repo cần tạo Release: mở Actions, hoặc git tag v1.5.0 && git push origin v1.5.0\n"
                "  → Hoặc chọn mục 3 để cài bản build trong máy." % REPO)
        die("GitHub trả lỗi HTTP %s." % e.code)
    except Exception as e:
        die("Không gọi được GitHub API: %s\n  → Kiểm tra mạng/Internet." % e)
    if not rel:
        die("Repo %s chưa có bản phát hành (Release) nào kèm file .apk.\n"
            "  → Cần tạo Release có APK trước (CI sẽ tự tạo khi gắn tag như v1.6.0).\n"
            "  → Hoặc cài bằng file APK có sẵn trong máy:\n"
            "      python3 tools/r1y.py install --ip <IP_LOA> --apk /sdcard/Download/app-release.apk\n"
            "  → Hoặc nhờ loa tự tải+cài (không cần file):\n"
            "      python3 tools/r1y.py webinstall --ip <IP_LOA>" % REPO)

    ok("Bản mới nhất: v%s%s" % (rel["tag"], " (thử nghiệm)" if rel["prerelease"] else ""))
    # APK tên có chữ "unsigned" → Android sẽ từ chối cài, cảnh báo ngay
    rel["unsigned"] = "unsigned" in (rel["apk_name"] or "").lower()
    if rel["apk_size"]:
        info("APK: %s (%.1f MB)" % (rel["apk_name"], rel["apk_size"] / 1048576.0))
    if rel["published"]:
        info("Phát hành: %s" % rel["published"][:10])

    if prefer_debug:
        dbg = gh_debug_asset(rel["tag"])
        if dbg:
            info("Dùng bản DEBUG (đã ký bằng key debug): %s" % dbg["apk_name"])
            rel = dbg
        else:
            warn("Bản phát hành không có APK debug — dùng bản release.")

    if warn_if_unsigned(rel) and not confirm("Vẫn tải và thử cài bản chưa ký?", False):
        die("Đã dừng. Dùng: --source debug  hoặc  --apk <file.apk>")

    info("Đang tải APK…")
    try:
        path = download_bytes(rel["apk_url"], rel["apk_name"])
    except Exception as e:
        die("Tải APK thất bại: %s" % e)
    size = os.path.getsize(path)
    if size < 100 * 1024:
        die("File tải về quá nhỏ (%d bytes) — có thể lỗi mạng." % size)
    ok("Đã tải %.1f MB" % (size / 1048576.0))
    saved = save_to_cache(path)
    if saved:
        info("Đã lưu bản firmware — lần sau cài lại không cần internet.")
    if not apk_is_signed(path):
        warn("APK tải về CHƯA ĐƯỢC KÝ — Android sẽ báo “ứng dụng chưa được cài đặt”.")
        print("  Cách xử lý:")
        print("    • Nhờ chủ repo bật ký release (xem docs/TERMUX.md → “Ký APK release”)")
        print("    • Hoặc cài bản debug: python3 tools/r1y.py install --ip <IP> --source debug")
        if not confirm("Vẫn thử cài bản chưa ký?", False):
            die("Đã dừng.")
    return path, version_tuple(rel["tag"]), rel


# ----------------------------------------------------------------- cài đặt
def adb_install(serial, apk_path):
    """
    Cài APK lên loa — theo đúng cách R1 cần (đã kiểm chứng trên phần cứng thật
    bởi nhiều dự án firmware R1):

        adb push <apk> /data/local/tmp/
        adb shell "sh -c 'pm install -r <apk> > ket_qua.txt 2>&1; echo DONE >> ket_qua.txt &'"
        chờ 10s -> đọc ket_qua.txt

    Vì sao phải chạy NỀN: trên Android 5.1 của R1, `pm install` chạy ở tiền cảnh
    làm adbd ngắt kết nối giữa lúc cài — đúng hiện tượng "error: closed" mà tool
    hay gặp. Chạy nền + đọc file kết quả thì không bị ngắt.
    (vẫn gọi pm qua đường dẫn tuyệt đối vì ROM stock thiếu PATH trong shell)
    """
    import posixpath
    remote_dir = "/data/local/tmp"
    remote = posixpath.join(remote_dir, os.path.basename(apk_path) or "r1y.apk")
    info("Đang chuyển APK lên loa…")
    adb("push", apk_path, remote, timeout=900)
    info("Đang cài…")
    result_file = "/data/local/tmp/r1y_install_result.txt"
    adb("-s", serial, "shell", "rm", "-f", result_file, quiet=True, allow_fail=True)
    # chạy pm install Ở NỀN, ghi kết quả ra file — tránh adbd ngắt giữa lúc cài
    adb("-s", serial, "shell",
        "sh -c 'pm install -r %s > %s 2>&1; echo DONE >> %s &'"
        % (remote, result_file, result_file),
        timeout=60, allow_fail=True, quiet=True)
    rc, out = 1, ""
    for _ in range(30):                       # chờ tối đa ~60 giây
        time.sleep(2)
        rc, out = adb("-s", serial, "shell", "cat", result_file,
                      timeout=30, allow_fail=True, quiet=True)
        if "DONE" in (out or ""):
            break
    if "Success" not in (out or ""):
        # thử lại kiểu cũ (gọi thẳng, có -t) — một số ROM lại cần cách này
        rc, out2 = adb("-s", serial, "shell", "/system/bin/pm", "install", "-r", remote,
                       timeout=300, allow_fail=True)
        out = (out or "") + "\n" + (out2 or "")
        if "Success" not in (out2 or ""):
            rc, out3 = adb("-s", serial, "shell", "/system/bin/pm", "install", "-r", "-t",
                           remote, timeout=300, allow_fail=True)
            out = out + "\n" + (out3 or "")
    if rc != 0 or "Success" not in out:
        rc, out = adb("-s", serial, "shell", "pm", "install", "-t", "-r", remote,
                      timeout=300, allow_fail=True)
    if rc != 0 or "Success" not in out:
        warn("pm install lỗi, thử adb install: %s" % out.splitlines()[-1] if out else "")
        rc2, out2 = adb("-s", serial, "install", "-t", "-r", apk_path, timeout=900)
        if rc2 != 0 or "Success" not in out2:
            adb("-s", serial, "shell", "rm", remote)
            reason = classify_install_error(out2 or out)
            hint = {
                "signature": "Bản đang cài trên loa KHÁC CHỮ KÝ với APK này "
                             "(Android không cho cài đè). Tool sẽ tự gỡ rồi cài lại.",
                "downgrade": "APK có versionCode NHỎ HƠN bản trên loa (hạ cấp).",
                "system": "ROM chặn cài bản không phải bản hệ thống.",
                "space": "Loa hết dung lượng /data.",
            }.get(reason, "Không rõ lý do (xem log pm ở trên).")
            die("Cài APK thất bại: %s\n%s" % (hint, (out2 or out)[-800:]))
    adb("-s", serial, "shell", "rm", remote, quiet=True, allow_fail=True)
    adb("-s", serial, "shell", "rm", "-f", result_file, quiet=True, allow_fail=True)
    ok("Đã cài %s" % APP_NAME)


def grant_permissions(serial):
    perms = [
        "android.permission.RECORD_AUDIO",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
    ]
    for p in perms:
        adb("-s", serial, "shell", "pm", "grant", PACKAGE, p, quiet=True)
    ok("Đã cấp quyền thu âm + bộ nhớ")


# Trợ lý gốc Phicomm (Unisound): vẫn chạy ngầm, chiếm ~100MB RAM và mỗi lần app
# khởi động lại nó có thể GIÀNH MICRO -> loa không nghe được cho tới khi rút điện.
# Nhiều dự án firmware R1 đều khuyên ẩn nó. Ẩn không xoá, bật lại được.
UNISOUND_APPS = ["com.phicomm.speaker.device"]


def hide_unisound(serial):
    """Ẩn trợ lý gốc Phicomm để nó không giành micro. Không xoá gì."""
    hidden = 0
    for app in UNISOUND_APPS:
        rc, out = adb("-s", serial, "shell", "pm", "hide", app, quiet=True, allow_fail=True)
        if "new hidden state: true" in (out or ""):
            hidden += 1
    if hidden:
        ok("Đã ẩn trợ lý gốc Phicomm (Unisound) để nó không giành micro.")
    return hidden


def unhide_unisound(serial):
    """Bật lại trợ lý gốc Phicomm."""
    for app in UNISOUND_APPS:
        adb("-s", serial, "shell", "pm", "unhide", app, quiet=True, allow_fail=True)
    info("Đã bật lại Unisound (cần rút điện loa rồi cắm lại để nó chạy).")


def hide_phicomm(serial):
    hidden = 0
    for app in PHICOMM_APPS:
        rc, out = adb("-s", serial, "shell", "pm", "hide", app, quiet=True)
        if "new hidden state: true" in out or rc == 0:
            hidden += 1
    if hidden:
        ok("Đã tắt %d app Phicomm gốc (vẫn giữ để khôi phục)" % hidden)
    else:
        info("Không có app Phicomm gốc cần tắt.")


def check_root(serial):
    out = adb_out("-s", serial, "shell", "su", "-c", "id")
    if "uid=0" in out:
        ok("Có root — đèn LED sẽ hoạt động.")
        return True
    warn("Chưa root — đèn LED sẽ tắt, các tính năng khác vẫn chạy.")
    return False


def enable_autostart(serial):
    adb("-s", serial, "shell", "pm", "enable", PACKAGE + "/.receiver.BootReceiver", quiet=True)
    adb("-s", serial, "shell", "dumpsys", "deviceidle", "whitelist", "+" + PACKAGE, quiet=True)
    ok("Đã bật tự khởi động khi cắm điện / bật loa")


def start_app(serial):
    adb("-s", serial, "shell", "am", "start", "-n", ACTIVITY, quiet=True)
    time.sleep(3)
    ok("Đã khởi động %s" % APP_NAME)


def reboot_and_wait(serial, old_ip=None, timeout=120):
    """Khởi động lại loa rồi chờ nó lên lại. Trả về IP mới (nếu lấy được)."""
    title("Khởi động lại loa")
    info("Đang khởi động lại (mất khoảng 30-60 giây)…")
    try:
        adb("-s", serial, "reboot", quiet=True)
    except Exception as e:
        warn("Không ra lệnh khởi động lại được: %s" % explain_error(str(e)))
        return None

    # chờ mất kết nối rồi có lại
    t0 = time.time()
    gone = False
    while time.time() - t0 < timeout:
        devs = []
        try:
            devs = list_devices()
        except Exception:
            pass
        if not devs:
            gone = True
        elif gone:
            ok("Loa đã lên lại")
            break
        time.sleep(3)

    # chờ boot xong (tối đa 60 giây, dừng sớm khi loa phản hồi bình thường)
    t1 = time.time()
    while time.time() - t1 < 60:
        try:
            out = adb_out("-s", serial, "shell", "getprop", "sys.boot_completed").strip()
            ping = adb_out("-s", serial, "shell", "echo", "r1y-ok").strip()
        except Exception:
            out, ping = "", ""
        if out == "1" or "r1y-ok" in ping:
            break
        time.sleep(3)

    # nếu mất kết nối ADB WiFi thì thử nối lại
    if serial and ":" in serial:
        adb("connect", serial, timeout=20, quiet=True)
    time.sleep(2)

    new_ip = None
    try:
        new_ip = device_ip(serial, old_ip)
    except Exception:
        pass
    ok("Khởi động lại xong")
    if new_ip:
        info("IP loa: %s" % new_ip)
        info("Giao diện: http://%s:%d" % (new_ip, WEB_PORT))
    return new_ip


def wait_web(ip, seconds=15):
    """Chờ giao diện web loa (cổng 8088) lên; trả về JSON /api/status hoặc None."""
    if not ip:
        return None
    sys.stdout.write("  %s•%s Chờ giao diện loa ở http://%s:%d " % (C.BLUE, C.RESET, ip, WEB_PORT))
    sys.stdout.flush()
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            st = http_json("http://%s:%d/api/status" % (ip, WEB_PORT), timeout=4)
            print("%s✔%s" % (C.GREEN, C.RESET))
            return st
        except Exception:
            sys.stdout.write(".")
            sys.stdout.flush()
            time.sleep(1.5)
    print("%s!%s" % (C.YELLOW, C.RESET))
    warn("Chưa mở được giao diện web (loa có thể đang khởi động, thử lại sau).")
    return None


# ----------------------------------------------------------------- cấu hình web
def configure_loa(ip, resolvers=None, music_server=None, autodiscover=None, interactive=True,
                  ai_server=None, ai_url=None, ai_token=None, audio_quality=None):
    """Cấu hình loa qua API 8088 (YouTube, máy chủ AI, chất lượng âm thanh)."""
    if not ip:
        warn("Không biết IP loa — bỏ qua cấu hình web.")
        return False

    if resolvers is None and interactive:
        title("Cấu hình máy chủ YouTube")
        print("  %s[] = loa tự dò máy chủ công cộng (mặc định, khuyên dùng)%s" % (C.DIM, C.RESET))
        print("  %sMáy chủ riêng: [{\"type\":\"custom\",\"url\":\"http://192.168.2.130:8091\"}]%s\n"
              % (C.DIM, C.RESET))
        cur = None
        try:
            cur = http_json("http://%s:%d/api/youtube/resolvers" % (ip, WEB_PORT), timeout=5)
        except Exception:
            pass
        if cur is not None:
            info("Hiện tại: %s" % json.dumps(cur, ensure_ascii=False))
        raw = ask("Mảng JSON resolver", "[]")
        try:
            resolvers = json.loads(raw) if raw.strip().startswith("[") else raw
        except Exception:
            warn("JSON không hợp lệ — giữ nguyên cấu hình cũ.")
            return False
        if interactive and not isinstance(resolvers, list):
            resolvers = []

    payload = {}
    if resolvers is not None:
        payload["youtube_resolvers"] = resolvers
    if ai_server is not None:
        payload["ai_server"] = ai_server
    if ai_url is not None:
        payload["ai_server_url"] = ai_url
    if ai_token is not None:
        payload["ai_token"] = ai_token
    if audio_quality is not None:
        payload["audio_quality"] = audio_quality

    # Hỏi chọn máy chủ AI (mặc định giữ xiaozhi.me)
    if interactive and ai_server is None:
        if confirm("Đổi máy chủ AI (mặc định là xiaozhi.me)?", False):
            title("Máy chủ AI")
            print("   1  xiaozhi.me (chính thức) — mặc định")
            print("   2  Xiaozhi tự dựng (esp32-server)")
            print("   3  Tự nhập URL khác")
            c = ask("Chọn", "1")
            if c == "2":
                payload["ai_server"] = "xiaozhi_self"
                payload["ai_server_url"] = ask("URL WebSocket", "ws://192.168.1.100:8000/xiaozhi/v1/")
            elif c == "3":
                payload["ai_server"] = "custom"
                payload["ai_server_url"] = ask("URL WebSocket (ws:// hoặc wss://)")
            t = ask("Token/API key (Enter bỏ qua, dùng token kích hoạt)")
            if t:
                payload["ai_token"] = t

    # Hỏi chất lượng âm thanh
    if interactive and audio_quality is None:
        if confirm("Dùng âm thanh chất lượng cao (Opus, nghe hay hơn)?", True):
            payload["audio_quality"] = "high"
        else:
            payload["audio_quality"] = "compat"
    if music_server is not None:
        payload["music_server"] = music_server
    if autodiscover is not None:
        payload["youtube_autodiscover"] = bool(autodiscover)

    if interactive and confirm("Đặt máy chủ nhạc riêng (music_server)?", False):
        payload["music_server"] = ask("URL máy chủ nhạc (Enter bỏ trống)")

    if not payload:
        return False
    try:
        r = http_post_json("http://%s:%d/api/settings" % (ip, WEB_PORT), payload, timeout=15)
        if isinstance(r, dict) and r.get("ok"):
            ok("Đã lưu cấu hình vào loa.")
            return True
        warn("Loa trả: %s" % r)
    except Exception as e:
        warn("Không lưu được cấu hình: %s" % e)
    return False


def show_status(ip, serial=None):
    title("Trạng thái loa")
    if serial:
        ver, code = device_version(serial)
        if ver:
            ok("Bản trên loa: v%s (code %s)" % (ver, code))
        else:
            warn("Chưa cài %s" % APP_NAME)
    if not ip:
        warn("Không biết IP loa.")
        return
    try:
        st = http_json("http://%s:%d/api/status" % (ip, WEB_PORT), timeout=6)
    except Exception:
        warn("Không mở được giao diện web http://%s:%d" % (ip, WEB_PORT))
        info("Loa có thể đang khởi động, hoặc IP đã đổi. Thử lại sau 30 giây.")
        return
    ok("Giao diện: http://%s:%d" % (ip, WEB_PORT))
    info("Phiên bản: v%s" % st.get("version", "?"))
    mode = "máy chủ công cộng (tự dò)" if st.get("mode") == "public" else "máy chủ riêng của bạn"
    info("Chế độ YouTube: %s" % mode)
    if st.get("discoveredPiped"):
        info("Piped đang dùng: %s" % ", ".join(st["discoveredPiped"].split()))
    if st.get("discoveredInvidious"):
        info("Invidious đang dùng: %s" % ", ".join(st["discoveredInvidious"].split()))
    if st.get("musicServer"):
        info("Máy chủ nhạc: %s" % st["musicServer"])
    info("Máy chủ AI: %s (%s)" % (st.get("aiServer", "?"), st.get("aiUrl", "")))
    info("Chất lượng âm thanh: %s" % ("cao (Opus)" if st.get("audioQuality") == "high" else "tương thích (AAC)"))
    if st.get("hotspotEnabled"):
        info("Phát WiFi: bật (SSID %s)" % st.get("hotspotSsid", "R1-Y"))
    info("Nguồn cập nhật: github.com/%s" % (st.get("updateRepo") or REPO))
    try:
        rd = http_json("http://%s:%d/api/youtube/discover" % (ip, WEB_PORT), timeout=60)
        if rd.get("found"):
            ok("Dò được %d máy chủ công cộng sẵn sàng." % rd["found"])
    except Exception:
        pass


# ----------------------------------------------------------------- luồng chính
LAN_HOTSPOT_PREFIX = "192.168.43."     # dải IP của WiFi do loa phát


def local_ips():
    """IP của máy này (không cần thư viện ngoài)."""
    out = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))          # không gửi gói tin nào
        out.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    return out


def on_device_hotspot():
    """Máy đang nối WiFi do loa phát? (IP dạng 192.168.43.x)"""
    return any(ip.startswith(LAN_HOTSPOT_PREFIX) for ip in local_ips())


def has_internet(timeout=4):
    """Có internet thật không (WiFi của loa thì không)."""
    for url in ("https://api.github.com", "https://www.google.com"):
        try:
            urllib.request.urlopen(url, timeout=timeout).read(1)
            return True
        except Exception:
            continue
    return False


TOOL_URL = "https://raw.githubusercontent.com/%s/main/tools/r1y.py" % REPO
TOOL_VERSION = "1.9.0"


def tool_is_outdated():
    """Bản tool đang chạy cũ hơn bản trên repo?"""
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/%s/commits?path=tools/r1y.py&per_page=1" % REPO,
            headers={"User-Agent": "R1Y-tool"})
        data = json.loads(urllib.request.urlopen(req, timeout=8).read().decode("utf-8"))
        remote_unix = data[0]["commit"]["committer"]["date"]
        remote = time.mktime(time.strptime(remote_unix, "%Y-%m-%dT%H:%M:%SZ"))
        local = os.path.getmtime(os.path.abspath(__file__))
        return remote > local + 60        # cho lệch múi giờ/đồng hồ
    except Exception:
        return False


def tool_self_update(silent_if_same=True):
    """
    Tải bản tools/r1y.py mới nhất trong repo về đè lên file đang chạy.
    Cần vì bản cũ không sửa được lỗi chữ ký (v1.7.1) — chính nó là thứ cản
    việc cài bản mới.
    """
    if not has_internet():
        if not silent_if_same:
            warn("Không có internet — không tự cập nhật được tool.")
        return False
    try:
        req = urllib.request.Request(TOOL_URL, headers={"Cache-Control": "no-cache"})
        data = urllib.request.urlopen(req, timeout=30).read()
        if len(data) < 50 * 1024 or b"def main()" not in data:
            warn("Bản tool tải về không hợp lệ — giữ nguyên bản đang chạy.")
            return False
        cur = os.path.abspath(__file__)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        try:
            shutil.copy2(cur, cur + ".bak-" + stamp)
        except Exception:
            pass
        tmp = cur + ".new"
        with open(tmp, "wb") as f:
            f.write(data)
        # kiểm tra cú pháp trước khi ghi đè
        import py_compile
        py_compile.compile(tmp, doraise=True)
        shutil.move(tmp, cur)
        ok("Đã cập nhật tool lên bản mới (bản cũ lưu ở %s.bak-%s)."
           % (os.path.basename(cur), stamp))
        info("Chạy lại: python3 tools/r1y.py  để dùng bản mới.")
        return True
    except Exception as e:
        warn("Không tự cập nhật được tool: %s" % e)
        return False


def firmware_cache_dir():
    """Thư mục giữ APK đã tải (dùng lại được khi không có internet)."""
    base = os.environ.get("HOME") or os.path.expanduser("~")
    d = os.path.join(base, ".r1y", "firmware")
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    return d


def save_to_cache(apk_path):
    """Chép APK đã tải vào ~/.r1y/firmware để lần sau không cần internet."""
    try:
        import shutil as _sh
        d = firmware_cache_dir()
        dest = os.path.join(d, os.path.basename(apk_path))
        if os.path.abspath(apk_path) != os.path.abspath(dest):
            _sh.copy2(apk_path, dest)
        # giữ tối đa 3 bản gần nhất
        files = sorted((os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk")),
                       key=os.path.getmtime, reverse=True)
        for old_f in files[3:]:
            try:
                os.remove(old_f)
            except Exception:
                pass
        return dest
    except Exception:
        return None


def cached_firmware():
    """APK đã tải sẵn trong máy (mới nhất)."""
    try:
        d = firmware_cache_dir()
        files = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk")]
        files = [f for f in files if os.path.getsize(f) > 100 * 1024]
        if not files:
            return None
        return max(files, key=os.path.getmtime)
    except Exception:
        return None


def preload_apk(args, quiet=False):
    """
    Tải APK về máy TRƯỚC khi nối vào WiFi của loa.
    WiFi do loa phát KHÔNG có internet, nên phải tải trước — đây là lỗi hay gặp nhất.
    Trả về đường dẫn file APK đã tải (hoặc None nếu dùng --apk/--source local).
    """
    if args.apk:
        return args.apk
    if getattr(args, "source", "github") == "local":
        return None

    # Tool cũ không sửa được lỗi nó đang gặp (vd v1.7.0 không gỡ được bản khác
    # chữ ký) → khi có internet, tự lấy bản mới nhất rồi bảo chạy lại.
    if has_internet() and tool_is_outdated():
        if not quiet:
            title("Cập nhật tool")
            warn("Tool đang chạy là bản cũ so với repo — tự cập nhật trước khi cài.")
        if tool_self_update(silent_if_same=True):
            raise SystemExit(1)

    # đã tải sẵn trong phiên này? -> dùng lại
    cached = getattr(args, "_apk_cache", None)
    if cached and os.path.isfile(cached):
        if not quiet:
            info("Dùng lại APK đã tải: %s" % os.path.basename(cached))
        return cached

    if not quiet:
        title("Bước 1/2 — Tải firmware về điện thoại trước")
        info("Cần internet ở bước này (WiFi nhà / 4G).")

    # Đang nối WiFi do loa phát (192.168.43.x) = KHÔNG có internet:
    # đừng cố tải rồi báo lỗi khó hiểu — dùng bản đã tải sẵn trong máy.
    if on_device_hotspot():
        cached = cached_firmware()
        if cached:
            print()
            warn("Bạn đang nối WiFi của loa (192.168.43.x) — WiFi này KHÔNG có internet.")
            ok("Dùng bản firmware đã tải trước đó trong máy:")
            print("     %s (%.1f MB, %s)" % (os.path.basename(cached),
                  os.path.getsize(cached) / 1048576.0,
                  time.strftime("%d/%m/%Y %H:%M", time.localtime(os.path.getmtime(cached)))))
            if confirm("Cài bản này bây giờ?", True):
                return cached
            raise SystemExit(1)
        print()
        warn("Bạn đang nối WiFi của loa (192.168.43.x) — WiFi này KHÔNG có internet.")
        print("  Trong máy cũng chưa có bản firmware nào đã tải.")
        print("  Làm 1 trong 2 cách:")
        print("    1. Nối điện thoại về WiFi nhà/4G → chạy lại tool → tải xong tool tự")
        print("       bảo bạn nối vào WiFi của loa để cài (không cần internet lúc cài).")
        print("    2. Tải sẵn file APK bằng trình duyệt, rồi:")
        print("       python3 tools/r1y.py install --apk /sdcard/Download/R1Y-v1.7.2-release.apk --ip 192.168.43.1")
        raise SystemExit(1)

    try:
        apk, want_ver, rel = get_apk(args.source, None, getattr(args, "pre", False))
        try:
            args._apk_cache = apk
        except Exception:
            pass
        return apk
    except SystemExit:
        cached = cached_firmware()
        if cached:
            print()
            ok("Không có internet — dùng bản firmware đã tải trước đó:")
            print("     %s (%.1f MB, %s)" % (os.path.basename(cached),
                  os.path.getsize(cached) / 1048576.0,
                  time.strftime("%d/%m/%Y %H:%M", time.localtime(os.path.getmtime(cached)))))
            if confirm("Dùng bản này để cài?", True):
                return cached
        raise
    except Exception as e:
        warn("Không tải được firmware: %s" % explain_error(str(e)))
        return None


def flow_prefetch():
    """Tải firmware về máy khi còn internet (không cần loa, không cần adb)."""
    title("Tải firmware về trước")
    if on_device_hotspot() and not has_internet():
        warn("Đang nối WiFi của loa — WiFi này KHÔNG có internet.")
        info("Nối về WiFi nhà/4G rồi chạy lại mục này.")
        return
    apk, want_ver, rel = get_apk("github", None, False)
    dest = save_to_cache(apk)
    ok("Đã lưu: %s (%.1f MB)" % (dest or apk, os.path.getsize(apk) / 1048576.0))
    if want_ver:
        info("Bản %s — nằm trong %s" % (want_ver[0], firmware_cache_dir()))
    info("Giờ có thể nối WiFi của loa rồi chọn 1 — tool dùng bản đã tải, không cần mạng.")


def explain_no_internet():
    """Hướng dẫn khi WiFi của loa không có internet."""
    print()
    warn("WiFi do loa phát KHÔNG CÓ INTERNET — không tải được firmware từ GitHub.")
    print("  Cách làm đúng (2 bước):")
    print("    1. Nối điện thoại về %sWiFi nhà (hoặc 4G)%s" % (C.BOLD, C.RESET))
    print("       rồi chạy lại: %sr1y%s → chọn 1  (tool tải firmware về trước)" % (C.BOLD, C.RESET))
    print("    2. Sau khi tải xong, nối vào %sWiFi của loa%s → tool cài ngay" % (C.BOLD, C.RESET))
    print("       (file firmware đã nằm trong máy, không cần internet nữa)")


def setup_home_wifi_after_install(ip, interactive=True):
    """
    Sau khi cài qua WiFi của loa (192.168.43.1): cho loa vào WiFi nhà.
    Ưu tiên API của R1-Y (cổng 8088, có quét WiFi), lỗi thì thử API gốc (8989).
    Trả về IP mới của loa trong WiFi nhà, hoặc None.
    """
    title("Cho loa vào WiFi nhà")
    info("Firmware gốc chỉ mở ADB khi loa phát WiFi riêng → luôn cài TRƯỚC, nối WiFi nhà SAU.")
    if not interactive:
        info("Bỏ qua (chế độ --yes). Làm sau: python3 tools/r1y.py wifi --ip %s" % ip)
        return None
    if not confirm("Cho loa vào WiFi nhà ngay bây giờ?", True):
        info("Để sau: nối WiFi của loa rồi chạy  python3 tools/r1y.py wifi --ip %s" % ip)
        return None
    done = False
    try:
        if web_alive(ip, timeout=3):
            done = bool(flow_wifi(argparse.Namespace(ip=ip)))
    except SystemExit:
        done = False
    if not done:
        warn("Cách 1 chưa được (ROM chặn?) — thử API gốc của loa (cổng 8989)…")
        try:
            done = bool(configure_wifi_stock(host=ip))
        except SystemExit:
            done = False
    if not done:
        warn("Chưa cho loa vào WiFi nhà được. Loa vẫn phát WiFi riêng — KHÔNG khởi động lại.")
        return None
    # WiFi của loa tắt khi loa vào WiFi nhà → máy phải nối lại WiFi nhà rồi dò IP
    print()
    warn("Loa vừa chuyển sang WiFi nhà → điện thoại/PC của bạn cũng phải NỐI LẠI WiFi nhà.")
    if confirm("Đã nối lại WiFi nhà? Tool sẽ tự dò IP loa", True):
        new_ip = None
        for attempt in range(1, 4):            # loa cần 20-60 giây để vào WiFi nhà
            info("Chờ loa vào WiFi nhà… (lần %d/3)" % attempt)
            time.sleep(20)
            new_ip = scan_for_loa_ip()
            if new_ip == HOTSPOT_IP:
                new_ip = None      # vẫn là WiFi riêng của loa, chưa phải WiFi nhà
            if new_ip:
                break
        if new_ip:
            ok("Loa ở %s → http://%s:%d" % (new_ip, new_ip, WEB_PORT))
            return new_ip
        warn("Chưa dò thấy loa. Xem IP loa trong trang quản lý router rồi mở http://IP_LOA:8088")
        print("  • Loa R1 chỉ bắt WiFi %s2.4GHz%s (không có 5GHz) và phải đúng mật khẩu" % (C.BOLD, C.RESET))
        print("  • Nếu loa phát lại WiFi 'Phicomm R1' = nối WiFi nhà thất bại → làm lại bước WiFi")
    return None


def flow_install(args):
    banner()
    # Tải firmware trước (lúc còn internet), rồi mới nối vào WiFi của loa
    apk_pre = preload_apk(args) if not args.yes else None
    if apk_pre:
        args.apk = apk_pre

    try:
        serial, ip = connect_device(args.ip, interactive=not args.yes)
    except SystemExit:
        # adb không nối được nhưng web 8088 có thể vẫn mở → nhờ loa tự cài
        if args.ip:
            print()
            warn("ADB không nối được — thử cách KHÔNG CẦN ADB (nhờ loa tự tải + cài).")
            if confirm("Cập nhật qua web (http://%s:%d)?" % (args.ip, WEB_PORT), True):
                flow_webinstall(argparse.Namespace(ip=args.ip))
        raise
    check_is_r1(serial)
    apk_path = None
    if args.apk:
        apk_path = args.apk
    elif args.source == "local":
        apk_path = local_apk_from_build()
        if not apk_path:
            die("Không thấy APK build trong máy (app/app/build/outputs/apk/...).")
    try:
        apk, want_ver, rel = get_apk(args.source, apk_path, getattr(args, "pre", False))
    except SystemExit:
        explain_no_internet()
        raise
    ip = device_ip(serial, ip)

    total = 7
    step(1, total, "Tắt app Phicomm gốc (giữ lại, không xoá)")
    hide_phicomm(serial)
    if not args.yes and confirm("Ẩn cả trợ lý gốc Unisound? (nó hay giành micro)", True):
        hide_unisound(serial)
    elif args.yes:
        hide_unisound(serial)

    step(2, total, "Kiểm tra bản cũ trên loa")
    old_ver, old_code = device_version(serial)
    new_code = (want_ver[1] if want_ver else 0)
    if not old_ver:
        info("Chưa có bản cũ — cài mới.")
    elif new_code and old_code == new_code:
        # cùng versionCode: cài đè trực tiếp, KHÔNG gỡ → giữ nguyên cài đặt WiFi/AI
        info("Loa đang chạy cùng bản v%s — cài đè, giữ nguyên cài đặt." % old_ver)
    elif new_code and old_code and new_code < old_code:
        warn("Loa đang chạy bản MỚI HƠN (v%s) — sẽ hạ cấp." % old_ver)
        if not confirm("Vẫn hạ cấp về v%s?" % (want_ver[0] if want_ver else "?"), False):
            die("Đã dừng.")
        adb("-s", serial, "uninstall", PACKAGE, quiet=True)
        ok("Đã gỡ v%s" % old_ver)
    elif new_code:
        info("Đang có v%s — sẽ nâng cấp lên v%s." % (old_ver, want_ver[0]))
    else:
        # không xác định được phiên bản APK (dùng --apk) → cài đè, giữ cài đặt
        info("Đang có v%s — sẽ cài đè (giữ nguyên cài đặt trên loa)." % old_ver)

    # Bản do robot GitHub build được ký bằng key tạo mới ở mỗi lần chạy CI,
    # nên bản mới KHÔNG BAO GIỜ cài đè được bản cũ (Android báo
    # INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match).
    # Biết trước thì gỡ sạch rồi cài, thay vì để pm lỗi giữa đường.
    old_sig = package_signature(serial)
    if old_ver and old_sig:
        info("Chữ ký bản đang cài trên loa: %s…" % old_sig)
        if old_code and new_code and old_code != new_code:
            warn("Bản mới được build ở máy khác nên chữ ký KHÁC bản đang cài.")
            info("Android bắt buộc gỡ bản cũ trước khi cài (mất cài đặt WiFi/AI trên loa,")
            info("nhưng tool sẽ cài lại + cấu hình lại ở các bước sau).")
            if confirm("Gỡ v%s rồi cài v%s?" % (old_ver, want_ver[0] if want_ver else "mới"), True):
                if remove_app(serial):
                    ok("Đã gỡ sạch v%s khỏi loa." % old_ver)
                else:
                    die("Không gỡ được v%s — ROM chặn. Thử: adb shell pm uninstall --user 0 %s"
                        % (old_ver, PACKAGE))

    step(3, total, "Cài firmware %s" % APP_NAME)
    try:
        adb_install(serial, apk)
    except SystemExit:
        # adb_install đã in lý do; nếu là chữ ký thì gỡ rồi cài lại MỘT lần
        if not package_present(serial):
            raise
        warn("Thử lại: gỡ bản cũ rồi cài lại (nguyên nhân thường là khác chữ ký).")
        if not remove_app(serial):
            die("Không gỡ được %s để cài lại — ROM chặn gỡ app hệ thống." % PACKAGE)
        adb_install(serial, apk)

    step(4, total, "Cấp quyền + kiểm tra root")
    grant_permissions(serial)
    check_root(serial)

    step(5, total, "Bật tự khởi động")
    enable_autostart(serial)

    step(6, total, "Khởi động và chờ giao diện")
    start_app(serial)
    ip = device_ip(serial, ip)
    st = wait_web(ip)

    step(7, total, "Cấu hình máy chủ YouTube")
    if args.yes and not args.resolvers and not getattr(args, "music_server", None):
        info("Bỏ qua (đang để [] = loa tự dò máy chủ công cộng).")
    if ip:
        configure_loa(ip,
                      resolvers=json.loads(args.resolvers) if args.resolvers else None,
                      music_server=getattr(args, "music_server", None),
                      autodiscover=None,
                      interactive=not args.yes,
                      ai_server=getattr(args, "ai_server", None),
                      ai_url=getattr(args, "ai_url", None),
                      ai_token=getattr(args, "ai_token", None),
                      audio_quality=getattr(args, "audio_quality", None))
    else:
        warn("Không lấy được IP loa — mở giao diện bằng tay để cấu hình.")

    # Đang cài qua WiFi do loa phát (192.168.43.1): cài xong phải cho loa vào WiFi nhà,
    # và KHÔNG reboot trước đó (reboot xong loa không còn WiFi riêng, mất cả ADB lẫn mạng).
    on_hotspot = (ip == HOTSPOT_IP) or str(serial).startswith(HOTSPOT_IP)
    if on_hotspot:
        home_ip = setup_home_wifi_after_install(ip, interactive=not args.yes)
        if home_ip:
            ip = home_ip
            st = wait_web(ip, 20) or st
        elif getattr(args, "reboot", None) is None:
            args.reboot = False
            warn("Loa chưa vào WiFi nhà → bỏ qua khởi động lại (để còn dùng được WiFi của loa).")

    # Bước 8: khởi động lại loa (mặc định hỏi; --yes thì theo --reboot/--no-reboot)
    want_reboot = getattr(args, "reboot", None)
    if want_reboot is None:
        if args.yes:
            want_reboot = True
        else:
            step(8, 8, "Khởi động lại loa")
            want_reboot = confirm("Khởi động lại loa ngay để áp dụng sạch?", True)
    if want_reboot:
        new_ip = reboot_and_wait(serial, ip)
        if new_ip:
            ip = new_ip
        st2 = wait_web(ip, 20)
        if st2:
            st = st2

    finish(ip, st)


def flow_update(args):
    banner()
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    check_is_r1(serial)
    ip = device_ip(serial, ip)
    cur_ver, cur_code = device_version(serial)
    if cur_ver:
        info("Bản trên loa: v%s (code %s)" % (cur_ver, cur_code))
    else:
        warn("%s chưa được cài — nên dùng mục 1." % APP_NAME)

    if args.apk:
        apk, want_ver, rel = get_apk("local", args.apk)
    else:
        apk, want_ver, rel = get_apk("github", None, getattr(args, "pre", False))

    if cur_ver and want_ver and version_tuple(cur_ver) >= want_ver and not args.force:
        ok("Loa đã ở bản mới nhất (v%s). Không cần cập nhật." % cur_ver)
        if not confirm("Cài lại (ghi đè) anyway?", False):
            show_status(ip, serial)
            return

    title("Cập nhật firmware")
    # Cùng lý do như flow_install: bản GitHub build ở CI nên đổi chữ ký mỗi lần.
    if cur_ver and (not want_ver or version_tuple(cur_ver) != want_ver):
        warn("Bản trên GitHub được build ở máy khác → chữ ký khác bản đang cài.")
        info("Android sẽ từ chối cài đè; tool gỡ bản cũ trước (mất cài đặt trên loa,")
        info("cài lại xong tool cấp lại quyền + cấu hình như bước cài mới).")
        if confirm("Gỡ v%s rồi cài bản mới?" % cur_ver, True):
            if remove_app(serial):
                ok("Đã gỡ sạch v%s." % cur_ver)
            else:
                die("Không gỡ được v%s — ROM chặn gỡ." % cur_ver)

    step(1, 3, "Cài bản mới")
    try:
        adb_install(serial, apk)
    except SystemExit:
        if not package_present(serial):
            raise
        warn("Thử lại: gỡ bản cũ rồi cài lại.")
        if not remove_app(serial):
            die("Không gỡ được %s để cài lại." % PACKAGE)
        adb_install(serial, apk)
    step(2, 3, "Cấp quyền + tự khởi động")
    grant_permissions(serial)
    enable_autostart(serial)
    step(3, 3, "Khởi động lại")
    adb("-s", serial, "shell", "am", "force-stop", PACKAGE, quiet=True)
    start_app(serial)
    st = wait_web(ip)

    want_reboot = getattr(args, "reboot", None)
    if want_reboot is None and not args.yes:
        want_reboot = confirm("Khởi động lại loa để áp dụng bản mới?", True)
    if want_reboot:
        new_ip = reboot_and_wait(serial, ip)
        if new_ip:
            ip = new_ip
        st2 = wait_web(ip, 20)
        if st2:
            st = st2
    finish(ip, st)


def http_post(url, body_text):
    """POST JSON/text, trả về JSON đã parse (giống http_json nhưng có body)."""
    data = (body_text or "").encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json", "User-Agent": "R1Y-tool"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8", "replace") or "{}")


def flow_audio(args):
    """
    Kiểm tra vì sao loa không ra tiếng / trợ lý không nói.
    Dùng API /api/audio/* của bản v1.7.4 trở lên (web 8088), không cần logcat.
    """
    banner()
    ip = args.ip
    if not ip:
        ip = scan_for_loa_ip() or ask("IP loa", "")
    if not ip:
        die("Chưa có IP loa. Ví dụ: python3 tools/r1y.py audio --ip 192.168.2.20")
    base = "http://%s:%d" % (ip, WEB_PORT)
    title("Chẩn đoán âm thanh loa %s" % ip)

    st = http_json(base + "/api/audio/status", timeout=10)
    info("Âm lượng nhạc: %s/%s" % (st.get("volume"), st.get("volumeMax")))
    if st.get("muted"):
        warn("ÂM LƯỢNG ĐANG BẰNG 0 — đây là lý do không có tiếng.")
    if st.get("wiredHeadset"):
        warn("Loa đang tưởng có tai nghe cắm vào (âm thanh bị dồn sang đó).")
    if st.get("bluetoothA2dp"):
        warn("Âm thanh đang đi qua Bluetooth.")
    if st.get("opusSupported") is not None:
        if st.get("opusSupported"):
            ok("Loa có bộ giải mã Opus.")
        else:
            warn("Loa KHÔNG có bộ giải mã Opus/WebM — chuyện bình thường với Android 5.1.")
            print("     → App đã tự ưu tiên AAC/m4a. Nếu vẫn im, đặt chất lượng 'Tương thích'.")
        if st.get("audioCodecs"):
            print("     Codec âm thanh loa đọc được: %s"
                  % ", ".join(sorted(st.get("audioCodecs").keys())))
    if st.get("micEnergy") is not None:
        me = st.get("micEnergy")
        info("Mức mic gần nhất: %s" % me)
        if me is not None and int(me) < 50:
            warn("Mic gần như im lặng: nói to gần loa rồi chạy lại, hoặc cấp quyền:")
            print("     adb shell pm grant %s android.permission.RECORD_AUDIO" % PACKAGE)
    if st.get("micAdvice"):
        info(str(st.get("micAdvice")))

    print()
    if confirm("Phát thử 1 tiếng bíp từ loa (không cần mạng)?", True):
        try:
            http_post(base + "/api/audio/test?kind=beep", "")
            ok("Đã yêu cầu phát bíp.")
            print("   • Loa CÓ kêu  → phần cứng + âm lượng ổn, lỗi nằm ở link nhạc/máy chủ.")
            print("   • Loa KHÔNG kêu → bấm 'Bật âm lượng' trên web hoặc kiểm tra loa hỏng.")
        except Exception as e:
            warn("Không gọi được API bíp (%s) — bản trên loa có thể cũ hơn v1.7.4." % e)

    if not args.no_sound_test:
        q = ask("Dán link YouTube (hoặc từ khoá) để thử phát", "")
        if q:
            try:
                info("Đang thử phát…")
                r = http_post(base + "/api/media/play", json.dumps({"query": q}))
                if r.get("ok"):
                    ok("Đã lấy được link phát: %s" % (r.get("source") or ""))
                    info("Bậc chất lượng: %s" % r.get("tier"))
                    col = http_json(base + "/api/audio/status", timeout=10)
                    if col.get("musicActive"):
                        ok("MediaPlayer đang phát thật (musicActive=true).")
                    else:
                        warn("MediaPlayer KHÔNG phát: link sống nhưng loa không đọc được, "
                             "hoặc âm lượng 0. Thử đổi 'Chất lượng âm thanh' sang Tương thích.")
                else:
                    warn("Không phát được: %s" % r.get("error"))
            except Exception as e:
                warn("Lỗi thử phát: %s" % e)


def flow_why_silent(args):
    """
    "Web báo đang phát mà loa im" — soi từng tầng và nói rõ hỏng ở đâu (v1.9.0).

    Tách được 3 tầng:
      A. link/nguồn   (HTTP 403? trang HTML? WebM?)
      B. giải mã      (MediaPlayer lỗi what/extra? position có nhích?)
      C. đường ra     (âm lượng + stream; bíp kêu được hay không)
    """
    banner()
    ip = args.ip or scan_for_loa_ip() or ask("IP loa", "")
    if not ip:
        die("Chưa có IP loa. Ví dụ: python3 tools/r1y.py silent --ip 192.168.2.20")
    base = "http://%s:%d" % (ip, WEB_PORT)
    title("Vì sao loa im — loa %s" % ip)

    j = http_json(base + "/api/audio/playing", timeout=15)
    info("Kết luận: %s" % (j.get("verdict") or "?"))
    if j.get("advice"):
        info("Nên làm: %s" % j["advice"])
    info("Đường ra: %s | vị trí: %s ms" % (j.get("route"), j.get("positionMs")))
    if j.get("title"):
        info("Bài: %s" % j["title"])
    if j.get("lastError"):
        warn("MediaPlayer: %s" % j["lastError"])

    url = None
    try:
        pl = http_json(base + "/api/player", timeout=15)
        url = pl.get("url")
        if pl.get("lastError") and not j.get("lastError"):
            warn("MediaPlayer: %s" % pl["lastError"])
    except Exception:
        pass

    if url:
        print()
        info("Đang kiểm tra link…")
        try:
            pr = http_json(base + "/api/audio/probe?url=" + urllib.parse.quote(url, safe=""),
                           timeout=25)
            info("HTTP %s | %s | %s" % (pr.get("status"), pr.get("contentType"),
                                        pr.get("magic")))
            info("Kết luận link: %s" % pr.get("verdict"))
            if pr.get("advice"):
                info("Nên làm: %s" % pr["advice"])
        except Exception as e:
            warn("Không kiểm tra được link: %s" % e)
    else:
        info("Chưa có bài nào đang phát -> bật một bài trước rồi chạy lại.")

    print()
    info("Bước cuối: bấm 'Phát thử tiếng bíp' trên web (tab Nhạc).")
    info("Bíp KÊU  -> lỗi ở codec/link (xem kết luận link ở trên).")
    info("Bíp IM   -> lỗi ở âm lượng/đường ra; thử đổi 'Đường ra' rồi phát lại.")


def flow_voice(args):
    """
    Nói chuyện với trợ lý AI + soi giao thức (v1.8.0).

    Đây là công cụ để trả lời dứt điểm câu "bấm nút loa không nói chuyện được":
    nó in ra kênh audio đã mở chưa, đã gửi bao nhiêu khung Opus, máy chủ trả về
    bao nhiêu khung, và mic có bị khoá/hỏng không.
    """
    banner()
    ip = args.ip or scan_for_loa_ip() or ask("IP loa", "")
    if not ip:
        die("Chưa có IP loa. Ví dụ: python3 tools/r1y.py voice --ip 192.168.2.20")
    base = "http://%s:%d" % (ip, WEB_PORT)
    title("Trợ lý AI — loa %s" % ip)

    def status():
        j = http_json(base + "/api/voice", timeout=10)
        if j.get("error"):
            warn("Lỗi: %s" % j["error"])
            return j
        if not j.get("connected"):
            warn("Chưa kết nối máy chủ AI: %s" % (j.get("url") or "?"))
        elif not j.get("channelOpen"):
            warn("Đã nối WebSocket nhưng máy chủ CHƯA trả hello "
                 "-> giao thức/token/URL sai.")
        else:
            ok("Kênh audio đã mở (session %s, %s Hz)" %
               (j.get("sessionId") or "?", j.get("serverRate")))
        info("Gửi lên: %s | khung gửi: %s | khung nhận: %s (lỗi %s)" % (
            "Opus" if j.get("useOpus") else "PCM",
            j.get("sentFrames"), j.get("recvOk"), j.get("recvBad")))
        if j.get("micRestarts"):
            warn("Mic đã tự khởi động lại %s lần (tầng âm thanh bị kẹt)" % j["micRestarts"])
        if j.get("lastKeyName"):
            info("Phím cuối loa gửi: %s" % j["lastKeyName"])
        if j.get("lastStt"):
            info("Bạn nói: %s" % j["lastStt"])
        if j.get("lastTts"):
            info("Trợ lý: %s" % j["lastTts"])
        return j

    st = status()
    if args.action:
        if args.action == "talk":
            j = http_post(base + "/api/voice?action=talk", "")
            ok("Đã %s lượt nói." % ("BẮT ĐẦU" if j.get("talking") else "KẾT THÚC"))
        elif args.action == "abort":
            http_post(base + "/api/voice?action=abort", "")
            ok("Đã ngắt lời trợ lý.")
        elif args.action == "text":
            if not args.text:
                die("Cần --text \"câu hỏi\"")
            j = http_post(base + "/api/voice?action=text&text=" +
                          urllib.parse.quote(args.text), "")
            ok("Đã gửi câu." if j.get("ok") else ("Lỗi: %s" % j.get("error")))
        elif args.action == "key":
            code = args.code if getattr(args, "code", None) else 66
            j = http_post(base + "/api/voice?action=key&code=%d" % code, "")
            ok("Đã gửi phím %s (%s)" % (code, j.get("keyName")))
        print()
        status()
        return

    while True:
        print()
        print("   1) Bấm NÓI (thử như bấm nút trên loa) — bấm lần nữa để thôi")
        print("   2) Ngắt lời trợ lý")
        print("   3) Gửi một câu gõ tay")
        print("   4) Thử một mã phím (để dò nút trên loa)")
        print("   5) Làm mới trạng thái")
        print("   0) Xong")
        c = ask("Chọn", "0")
        if c == "1":
            j = http_post(base + "/api/voice?action=talk", "")
            ok("Đang nói…" if j.get("talking") else "Đã dừng.")
        elif c == "2":
            http_post(base + "/api/voice?action=abort", "")
            ok("Đã ngắt lời.")
        elif c == "3":
            t = ask("Câu hỏi", "")
            if t:
                http_post(base + "/api/voice?action=text&text=" + urllib.parse.quote(t), "")
                ok("Đã gửi.")
        elif c == "4":
            info("Mã thường gặp: 66=ENTER, 23=DPAD_CENTER, 85=MEDIA_PLAY_PAUSE, "
                 "79=HEADSETHOOK, 84=SEARCH, 231=VOICE_ASSIST")
            code = ask("Mã phím", "66")
            try:
                j = http_post(base + "/api/voice?action=key&code=%d" % int(code), "")
                ok("Đã gửi %s -> %s" % (code, j.get("keyName")))
            except ValueError:
                warn("Mã phím phải là số.")
        elif c == "5":
            status()
        elif c == "0":
            return


def flow_player(args):
    """
    Điều khiển phát nhạc trên loa: tạm dừng / phát tiếp / ngưng hẳn / lặp lại / xem đang phát gì.
    Dùng API /api/player của bản v1.7.5 trở lên.
    """
    banner()
    ip = args.ip or scan_for_loa_ip() or ask("IP loa", "")
    if not ip:
        die("Chưa có IP loa. Ví dụ: python3 tools/r1y.py player --ip 192.168.2.20")
    base = "http://%s:%d" % (ip, WEB_PORT)
    title("Điều khiển phát nhạc — loa %s" % ip)

    def show():
        j = http_json(base + "/api/player", timeout=10)
        if j.get("title"):
            info("Đang phát: %s" % j.get("title"))
        elif j.get("playing"):
            info("Đang phát (chưa rõ tên bài)")
        else:
            info("Chưa phát bài nào.")
        if j.get("paused"):
            warn("Đang TẠM DỪNG.")
        info("Lặp lại: %s | âm lượng: %s" % ("BẬT" if j.get("repeat") else "TẮT", j.get("volume")))
        return j

    st = show()
    if args.action:
        acts = {"pause": "pause", "resume": "resume", "stop": "stop",
                "repeat-on": "repeat&on=1", "repeat-off": "repeat&on=0"}
        act = acts.get(args.action, args.action)
        j = http_post(base + "/api/player?action=" + act, "")
        ok(j.get("message") or str(j))
        print()
        show()
        return

    while True:
        print()
        print("   1) Tạm dừng")
        print("   2) Phát tiếp")
        print("   3) Ngưng hẳn bài này")
        print("   4) Bật/tắt lặp lại")
        print("   5) Làm mới")
        print("   0) Xong")
        c = ask("Chọn", "0")
        try:
            if c == "1":
                ok(http_post(base + "/api/player?action=pause", "").get("message", "OK"))
            elif c == "2":
                ok(http_post(base + "/api/player?action=resume", "").get("message", "OK"))
            elif c == "3":
                ok(http_post(base + "/api/player?action=stop", "").get("message", "OK"))
            elif c == "4":
                cur = http_json(base + "/api/player", timeout=10).get("repeat")
                j = http_post(base + "/api/player?action=repeat&on=" + ("0" if cur else "1"), "")
                ok(j.get("message", "OK"))
            elif c == "5":
                pass
            else:
                return
        except Exception as e:
            warn("Lệnh không chạy được: %s" % e)
            return
        show()


def flow_hotspot(args):
    """Bật/tắt phát WiFi từ loa (để điện thoại nối trực tiếp vào cấu hình)."""
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    ip = device_ip(serial, ip)
    on = True if args.on is None else args.on
    ssid = args.ssid or "R1-Y"
    pw = args.password or "r1y12345678"
    title("Phát WiFi từ loa")
    if ip:
        try:
            r = http_post_json("http://%s:%d/api/hotspot" % (ip, WEB_PORT),
                               {"on": on, "ssid": ssid, "password": pw}, timeout=20)
            if r.get("ok"):
                ok("Đã bật phát WiFi: %s" % r.get("ssid", ssid))
                info("Điện thoại kết nối WiFi '%s' (mật khẩu %s)" % (ssid, pw))
                info("Rồi mở: %s" % r.get("url", "http://192.168.43.1:8088"))
                return
            warn("Loa không bật được hotspot: %s" % r.get("warning", r.get("hint", "")))
        except Exception as e:
            warn("Không gọi được API loa: %s" % e)
    # Dự phòng: bật bằng ADB (cần root)
    if args.yes or confirm("Thử bật bằng ADB (cần root)?", True):
        adb("-s", serial, "shell", "su", "-c",
            'cmd wifi start-softap "%s" wpa2 "%s"' % (ssid, pw), quiet=True)
        hid = adb_out("-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
        m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", hid)
        info("Kiểm tra WiFi loa bằng tay: adb shell ip -f inet addr show wlan0")
        if m:
            ok("IP hiện tại của loa: %s" % m.group(1))


STOCK_WIFI_API = "http://%s:8989/api/configwifi"


def configure_wifi_stock(ssid=None, password=None, host=None):
    """
    Cấu hình WiFi nhà cho loa qua API gốc của Phicomm R1 (port 8989).
    Dùng được NGAY cả khi chưa cài R1-Y — loa đang phát WiFi riêng.
    """
    host = host or HOTSPOT_IP
    title("Cấu hình WiFi cho loa (qua loa đang phát WiFi)")
    if not ssid:
        ssid = ask("Tên WiFi nhà (SSID)")
    if not ssid:
        die("Chưa nhập tên WiFi.")
    if password is None:
        password = ask("Mật khẩu WiFi (Enter nếu không mật khẩu)")
    secure = "WPA" if password else "INSECURE"

    payload = {"ssid": ssid, "secure": secure, "password": password or "", "mac": ""}
    url = STOCK_WIFI_API % host
    info("Đang gửi cấu hình tới loa %s…" % host)
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            r.read()
        ok("Đã gửi cấu hình WiFi (%s) tới loa." % ssid)
        info("Loa sẽ tự nối WiFi nhà sau ~10-20 giây và TẮT WiFi riêng.")
        warn("Lưu ý: khi loa vào WiFi nhà, firmware gốc KHÔNG mở ADB nữa.")
        print("    Nếu chưa cài R1-Y: giữ nút 5 giây cho loa phát WiFi lại, rồi chọn 1 (cài qua 192.168.43.1).")
        print("    Nếu đã cài R1-Y: mở http://IP_LOA:8088 là dùng được.")
        return True
    except Exception as e:
        warn("Không gửi được tới %s: %s" % (url, explain_error(str(e))))
        print("  • Điện thoại đã nối WiFi do loa phát chưa? (tên Phicomm R1)")
        print("  • Thử mở http://%s:8989 bằng trình duyệt để kiểm tra" % host)
        return False


def flow_wifi(args):
    """Cấu hình WiFi nhà cho loa qua giao diện web (chế độ cài đặt)."""
    ip = args.ip or "192.168.43.1"
    title("Cấu hình WiFi cho loa")
    print("  %sLoa phải đang ở chế độ cài đặt (giữ nút tròn) — WiFi: %s%s"
          % (C.DIM, "R1-Y", C.RESET))
    info("Giao diện: http://%s:%d" % (ip, WEB_PORT))

    # 1) xem trạng thái
    st = None
    try:
        st = http_json("http://%s:%d/api/setup" % (ip, WEB_PORT), timeout=8)
        w = st.get("wifi") or {}
        if w.get("connected"):
            ok("Loa đang ở WiFi: %s (IP %s)" % (w.get("ssid", "?"), w.get("ip", "?")))
        else:
            warn("Loa chưa vào WiFi nhà.")
        if st.get("hotspotOn"):
            info("Loa đang phát WiFi: %s" % st.get("hotspotSsid", "R1-Y"))
    except Exception:
        warn("Không gọi được loa ở %s — kiểm tra WiFi đã nối vào loa chưa." % ip)

    # 2) quét
    info("Đang quét WiFi xung quanh loa…")
    networks = []
    try:
        r = http_json("http://%s:%d/api/wifi/scan" % (ip, WEB_PORT), timeout=40)
        networks = r.get("networks") or []
    except Exception as e:
        warn("Không quét được: %s" % e)

    if networks:
        ok("Thấy %d WiFi:" % len(networks))
        for i, n in enumerate(networks, 1):
            lock = "khoá" if n.get("secured") else "mở"
            saved = " (đã lưu)" if n.get("saved") else ""
            print("    %2d. %-32s %s%s" % (i, n.get("ssid", "?"), lock, saved))
        sel = ask("Chọn số (Enter để nhập tay)", "1")
        ssid = ""
        try:
            idx = int(sel) - 1
            if 0 <= idx < len(networks):
                ssid = networks[idx].get("ssid", "")
        except ValueError:
            ssid = sel
        if not ssid:
            ssid = ask("Tên WiFi (SSID)")
    else:
        ssid = ask("Tên WiFi nhà (SSID)")

    if not ssid:
        die("Không có tên WiFi — huỷ.")

    passwd = ""
    for n in networks:
        if n.get("ssid") == ssid and not n.get("secured"):
            info("WiFi này không có mật khẩu.")
            break
    if not any(n.get("ssid") == ssid and not n.get("secured") for n in networks):
        passwd = ask("Mật khẩu WiFi (Enter nếu mở)")

    info("Đang yêu cầu loa kết nối (chờ tới ~30 giây)…")
    try:
        r = http_post_json("http://%s:%d/api/wifi/connect" % (ip, WEB_PORT),
                           {"ssid": ssid, "password": passwd}, timeout=60)
    except Exception as e:
        die("Không gửi được yêu cầu: %s" % e)

    if r.get("ok"):
        ok("Loa đã vào WiFi: %s" % ssid)
        if r.get("ip"):
            print("\n  %sGiao diện loa:%s %s%s%s"
                  % (C.BOLD, C.RESET, C.CYAN, r.get("webUrl"), C.RESET))
            info("Loa đã vào WiFi nhà. Chưa cài R1-Y? Giữ nút 5 giây cho loa phát WiFi lại rồi cài qua 192.168.43.1.")
        return True
    else:
        err(r.get("message") or "Không kết nối được")
        if "mật khẩu" in (r.get("message") or "").lower():
            if confirm("Thử quên mạng cũ rồi nối lại?", True):
                try:
                    http_post_json("http://%s:%d/api/wifi/forget" % (ip, WEB_PORT),
                                   {"ssid": ssid}, timeout=15)
                except Exception:
                    pass
                return flow_wifi(args)
        return False


def flow_webinstall(args):
    """
    Cài/cập nhật firmware KHÔNG cần adb: nhờ chính loa tự tải APK từ GitHub
    và tự cài (loa có quyền root thì cài im lặng, không thì hiện trình cài đặt).
    Dùng được trên điện thoại khi chưa cài được android-tools.
    """
    ip = args.ip or ""
    if not ip:
        ip = "192.168.43.1"
    base = "http://%s:%d" % (ip, WEB_PORT)
    title("Cập nhật firmware qua web (không cần adb)")
    info("Loa: %s" % base)

    try:
        st = http_json(base + "/api/status", timeout=8)
    except Exception:
        die("Không mở được %s\n"
            "  • Điện thoại đã nối cùng WiFi với loa chưa?\n"
            "  • IP loa đúng chưa? (xem trong Cài đặt WiFi của điện thoại)\n"
            "  • Loa đang phát WiFi riêng thì dùng 192.168.43.1" % base)

    ok("Loa đang chạy %s v%s" % (st.get("app", "R1-Y"), st.get("version", "?")))

    info("Đang kiểm tra bản mới trên GitHub…")
    try:
        chk = http_json(base + "/api/update/check", timeout=40)
    except Exception as e:
        die("Loa không kiểm tra được cập nhật: %s" % explain_error(str(e)))

    if not chk.get("hasUpdate"):
        ok("Đã là bản mới nhất (v%s)." % chk.get("currentVersion", "?"))
        if not confirm("Cài lại (ghi đè) bản mới nhất?", False):
            return
    else:
        ok("Có bản mới: v%s (đang dùng v%s)"
           % (chk.get("latestTag", "?"), chk.get("currentVersion", "?")))
        notes = (chk.get("releaseNotes") or "").strip()
        if notes:
            print("  %s%s%s" % (C.DIM, notes[:400].replace("\n", "\n  "), C.RESET))

    if not confirm("Tải và cài ngay trên loa?", True):
        return

    info("Loa đang tải APK và cài (1-3 phút, đừng tắt loa)…")
    try:
        r = http_post_json(base + "/api/update/install", {}, timeout=600)
    except Exception as e:
        warn("Yêu cầu bị ngắt giữa chừng (loa có thể đang cài): %s" % explain_error(str(e)))
        r = {}

    if r.get("ok"):
        ok(r.get("message") or "Đã cài xong")
        info("Chờ loa khởi động lại rồi mở: %s" % base)
        if not confirm("Đợi loa lên lại và kiểm tra?", True):
            return
        for i in range(20):
            time.sleep(6)
            try:
                st2 = http_json(base + "/api/status", timeout=5)
                ok("Loa đã lên lại: v%s" % st2.get("version", "?"))
                info("Giao diện: %s" % base)
                return
            except Exception:
                sys.stdout.write(".")
                sys.stdout.flush()
        print()
        warn("Loa chưa phản hồi — mở lại %s sau 1 phút." % base)
    else:
        err(r.get("error") or "Loa chưa cài được. Thử lại hoặc dùng cách có adb.")


def flow_adb_wifi(args):
    """
    Bật ADB qua WiFi trên loa (cần cắm USB một lần).
    Đây là thứ hay thiếu nhất: cổng 5555 chưa mở thì 'adb connect' luôn offline.
    """
    title("Bật ADB qua WiFi cho loa (cổng 5555)")
    info("Loa Phicomm R1 mặc định chỉ bật ADB qua USB. Phải bật chế độ WiFi một lần.")

    rows = devices_raw()
    usb = [d for d, st in rows if ":" not in d and st == "device"]
    net = [d for d, st in rows if ":" in d and st == "device"]

    if net and not usb:
        ok("ADB WiFi đã bật sẵn: %s" % net[0])
        info("Không cần làm gì thêm.")
        return

    if not usb:
        print()
        warn("Chưa thấy loa nối qua cáp USB.")
        # thử dò loa qua mạng trước (nếu ADB WiFi đã bật thì khỏi cần cáp)
        net_found = scan_for_loa_ip(progress=False)
        if net_found:
            ok("Thấy loa trong WiFi: %s" % net_found)
            if adb_connect_smart(net_found):
                ok("ADB WiFi đã bật sẵn — không cần cáp USB.")
                return
        print("  Cách làm:")
        print("    1. Cắm cáp USB từ điện thoại/máy tính vào loa R1")
        print("       (điện thoại cần cáp OTG; máy tính cắm trực tiếp)")
        print("    2. Trên loa: Cài đặt → Tùy chọn nhà phát triển → bật %sGỡ lỗi USB%s"
              % (C.BOLD, C.RESET))
        print("    3. Trên điện thoại/máy bấm %sCho phép%s khi loa hỏi" % (C.BOLD, C.RESET))
        print("    4. Chạy lại mục này")
        print()
        print("  Không có cáp USB? Dùng cách không cần ADB:")
        print("    menu %s3b%s — loa tự tải + cài qua web 8088" % (C.BOLD, C.RESET))
        if confirm("Thử quét lại thiết bị?", True):
            rows = devices_raw()
            usb = [d for d, st in rows if ":" not in d and st == "device"]
        if not usb:
            return

    serial = usb[0]
    ok("Thấy loa qua USB: %s" % serial)
    info("Đang bật ADB qua WiFi…")
    rc, out = adb("-s", serial, "tcpip", str(ADB_PORT), timeout=60, allow_fail=True)
    if rc == 0 and ("restarting" in out.lower() or not out.strip()):
        ok("Đã bật ADB WiFi (cổng %d)" % ADB_PORT)
    else:
        warn("adb tcpip trả lời: %s" % (out.strip() or "(không có)"))

    time.sleep(2)
    ip = None
    try:
        ip = device_ip(serial, None)
    except Exception:
        pass
    if ip:
        ok("IP loa: %s" % ip)
        if adb_connect_smart(ip):
            ok("Đã nối được ADB WiFi — giờ có thể rút cáp USB.")
            info("Cài firmware: python3 tools/r1y.py install --ip %s" % ip)
        else:
            warn("Đã bật nhưng chưa nối được — thử rút cáp rồi chạy lại.")
    else:
        warn("Không đọc được IP loa — xem IP trong Cài đặt WiFi của loa.")


def flow_status(args):
    serial, ip = None, args.ip
    try:
        devs = list_devices() if shutil.which("adb") or ADB else []
        if devs:
            serial = devs[0]
            ip = ip or device_ip(serial)
    except Exception:
        pass
    show_status(ip, serial)


def flow_config(args):
    ip = args.ip
    if not ip:
        try:
            serial, ip = connect_device(None, interactive=False)
            ip = device_ip(serial, ip)
        except SystemExit:
            die("Cần IP loa: --ip 192.168.2.20")
    resolvers = json.loads(args.resolvers) if args.resolvers else None
    configure_loa(ip, resolvers=resolvers,
                  music_server=getattr(args, "music_server", None),
                  interactive=(resolvers is None and not args.yes),
                  ai_server=getattr(args, "ai_server", None),
                  ai_url=getattr(args, "ai_url", None),
                  ai_token=getattr(args, "ai_token", None),
                  audio_quality=getattr(args, "audio_quality", None))
    show_status(ip)


def flow_uninstall(args):
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    title("Gỡ cài đặt / khôi phục")
    if not confirm("Gỡ %s và bật lại app Phicomm gốc?" % APP_NAME, True):
        return
    adb("-s", serial, "uninstall", PACKAGE, quiet=True)
    ok("Đã gỡ %s" % APP_NAME)
    for app in PHICOMM_APPS:
        adb("-s", serial, "shell", "pm", "unhide", app, quiet=True)
    ok("Đã bật lại app Phicomm gốc")
    warn("Loa nên được khởi động lại.")


def finish(ip, status_info):
    title("Xong!")
    if status_info:
        ok("Phiên bản trên loa: v%s" % status_info.get("version", "?"))
    if ip:
        print("\n  %sGiao diện loa:%s %shttp://%s:%d%s" % (C.BOLD, C.RESET, C.CYAN, ip, WEB_PORT, C.RESET))
    print("\n  Việc tiếp theo:")
    print("    • Mở giao diện trên để vào %sTrợ lý%s (kích hoạt Xiaozhi) và %sNhạc%s" % (C.BOLD, C.RESET, C.BOLD, C.RESET))
    print("    • Để trống %s[]%s = loa tự dò máy chủ YouTube công cộng" % (C.BOLD, C.RESET))
    print("    • Cập nhật sau này: %spython3 tools/r1y.py update%s" % (C.BOLD, C.RESET))
    print("    • Xem log: %sadb logcat | grep -E 'Resolver|WebServer'%s" % (C.DIM, C.RESET))
    print()


def menu():
    """Menu tương tác — chạy xong 1 việc thì quay lại menu."""
    banner()
    while True:
        try:
            _menu_once()
        except KeyboardInterrupt:
            print("\nTạm biệt!")
            return
        except SystemExit as e:
            # lỗi trong flow (die) → quay lại menu; người dùng chọn thoát (code 0) → thoát hẳn
            if e.code in (0, None):
                return
        except Exception as e:
            warn("Có lỗi: %s" % explain_error(str(e)))
        print()
        if not confirm("Quay lại menu chính?", True):
            print("Tạm biệt!")
            return


def _menu_once():
    print("  %sChọn việc cần làm:%s\n" % (C.BOLD, C.RESET))
    items = [
        ("1", "Cài mới từ đầu (A→Z) — tải APK từ GitHub rồi cài"),
        ("2", "Cập nhật firmware — so với bản mới nhất trên GitHub"),
        ("3", "Cài bản build trong máy (APK dev)"),
        ("3b", "Cập nhật qua web — KHÔNG cần adb (dùng khi adb báo offline)"),
        ("4", "Cấu hình máy chủ YouTube cho loa"),
        ("5", "Kiểm tra trạng thái loa"),
        ("5b", "Chẩn đoán âm thanh — loa không ra tiếng / trợ lý không nói"),
        ("5c", "Điều khiển nhạc — tạm dừng / phát tiếp / ngưng / lặp lại"),
        ("5d", "Trợ lý AI — nói chuyện, soi giao thức, dò mã phím nút trên loa"),
        ("5e", "Vì sao loa im — soi link / giải mã / đường ra"),
        ("6", "Cấu hình WiFi nhà cho loa (quét + nối)"),
        ("7", "Bật phát WiFi (hotspot) từ loa để điện thoại kết nối"),
        ("8", "Gỡ cài đặt / khôi phục app gốc"),
        ("8b", "Tải firmware về trước (làm khi CÒN internet, để cài sau)"),
        ("0", "Bật ADB qua WiFi (khi adb báo offline / cổng 5555 chưa mở)"),
        ("9", "Thoát"),
    ]
    for k, t in items:
        print("   %s%-2s%s %s" % (C.CYAN, k, C.RESET, t))
    print()
    choice = ask("Nhập số", "1")

    if choice == "5c":
        flow_player(argparse.Namespace(ip=None, action=None))
        return

    if choice == "5d":
        flow_voice(argparse.Namespace(ip=None, action=None, text=None, code=None))
        return

    if choice == "5e":
        flow_why_silent(argparse.Namespace(ip=None))
        return

    if choice == "5b":
        flow_audio(argparse.Namespace(ip=None, no_sound_test=False))
        return

    if choice == "8b":
        flow_prefetch()
        return

    if choice == "1":
        flow_install(argparse.Namespace(ip=None, apk=None, source="github", resolvers=None,
                                        music_server=None, yes=False, pre=False,
                                        ai_server=None, ai_url=None, ai_token=None,
                                        audio_quality=None))
    elif choice == "2":
        flow_update(argparse.Namespace(ip=None, apk=None, yes=False, pre=False, force=False))
    elif choice == "3":
        flow_install(argparse.Namespace(ip=None, apk=None, source="local", resolvers=None,
                                        music_server=None, yes=False, pre=False,
                                        ai_server=None, ai_url=None, ai_token=None,
                                        audio_quality=None))
    elif choice in ("3b", "web"):
        flow_webinstall(argparse.Namespace(ip=None))
    elif choice == "4":
        args = argparse.Namespace(ip=None, resolvers=None, music_server=None, yes=False,
                                  ai_server=None, ai_url=None, ai_token=None, audio_quality=None)
        flow_config(args)
    elif choice == "5":
        flow_status(argparse.Namespace(ip=None))
    elif choice == "6":
        ip = ask("IP loa (Enter = %s nếu đang nối WiFi của loa)" % HOTSPOT_IP) or HOTSPOT_IP
        if not web_alive(ip, timeout=2):
            # Firmware gốc: dùng API 8989
            configure_wifi_stock(host=ip)
        else:
            flow_wifi(argparse.Namespace(ip=ip))
    elif choice == "7":
        flow_hotspot(argparse.Namespace(ip=None, yes=False, on=None, ssid=None, password=None))
    elif choice == "8":
        flow_uninstall(argparse.Namespace(ip=None, yes=False))
    elif choice == "0":
        flow_adb_wifi(argparse.Namespace(ip=None))
    elif choice in ("9", "q", "quit", "exit", ""):
        print("Tạm biệt!")
        sys.exit(0)
    else:
        warn("Không có mục '%s' — chọn số trong danh sách." % choice)


def main():
    global ADB
    ap = argparse.ArgumentParser(
        description="Tool cài đặt / cập nhật firmware %s cho loa Phicomm R1." % APP_NAME,
        epilog="Nguồn firmware: github.com/%s" % REPO)
    ap.add_argument("command", nargs="?", default="menu",
                    choices=["install", "update", "status", "config", "uninstall",
                             "hotspot", "wifi", "webinstall", "adbwifi", "audio",
                             "player", "voice", "silent", "menu"],
                    help="Việc cần làm (bỏ trống = mở menu)")
    ap.add_argument("--ip", help="IP loa trong WiFi, ví dụ 192.168.2.20")
    ap.add_argument("--apk", help="Dùng file APK chỉ định thay vì tải GitHub")
    ap.add_argument("--source", choices=["github", "debug", "local"], default="github",
                    help="Nguồn APK: github (release) hoặc local (build trong máy)")
    ap.add_argument("--resolvers", help='Mảng JSON resolver, ví dụ \'[{"type":"custom","url":"http://x:8091"}]\'')
    ap.add_argument("--music-server", dest="music_server", help="URL máy chủ nhạc cuối cùng")
    ap.add_argument("--ai-server", dest="ai_server",
                    choices=["xiaozhi", "xiaozhi_self", "custom"],
                    help="Máy chủ AI: xiaozhi (mặc định xiaozhi.me) | xiaozhi_self | custom")
    ap.add_argument("--ai-url", dest="ai_url", help="URL WebSocket của máy chủ AI")
    ap.add_argument("--ai-token", dest="ai_token", help="Token/API key cho máy chủ AI tự dựng")
    ap.add_argument("--audio-quality", dest="audio_quality", choices=["high", "compat"],
                    help="high = Opus chất lượng cao (mặc định), compat = AAC/m4a")
    ap.add_argument("--on", dest="on", action="store_true", default=None,
                    help="(hotspot) bật phát WiFi")
    ap.add_argument("--off", dest="on", action="store_false",
                    help="(hotspot) tắt phát WiFi")
    ap.add_argument("--ssid", help="(hotspot) tên WiFi, mặc định R1-Y")
    ap.add_argument("--password", help="(hotspot) mật khẩu WiFi, mặc định r1y12345678")
    ap.add_argument("--pre", action="store_true", help="Cho phép bản thử nghiệm (prerelease)")
    ap.add_argument("--force", action="store_true", help="Cài đè dù đã ở bản mới nhất")
    ap.add_argument("--reboot", dest="reboot", action="store_true", default=None,
                    help="Khởi động lại loa sau khi cài")
    ap.add_argument("--no-reboot", dest="reboot", action="store_false",
                    help="Không khởi động lại loa sau khi cài")
    ap.add_argument("--yes", "-y", action="store_true", help="Không hỏi lại (dùng cho script/CI)")
    ap.add_argument("--action",
                    choices=["pause", "resume", "stop", "repeat-on", "repeat-off",
                             "talk", "abort", "text", "key"],
                    help="(player) pause/resume/stop/repeat-on/repeat-off | "
                         "(voice) talk/abort/text/key — làm 1 việc rồi thoát")
    ap.add_argument("--text", help="(voice --action text) câu cần gửi cho trợ lý")
    ap.add_argument("--code", type=int, help="(voice --action key) mã phím cần giả lập, vd 66")
    ap.add_argument("--no-sound-test", dest="no_sound_test", action="store_true",
                    help="(audio) chỉ kiểm tra âm lượng/mic, không thử phát nhạc")
    ap.add_argument("--adb", help="Đường dẫn adb nếu không có trong PATH")
    args = ap.parse_args()

    if args.adb:
        ADB = args.adb

    if IS_WIN and hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass

    try:
        cmd = args.command
        # Tự chuẩn bị môi trường (cài thư viện thiếu) — không hiện lỗi khó hiểu
        if cmd in ("install", "update", "status", "hotspot", "uninstall") or cmd is None:
            try:
                ensure_env()
            except Exception:
                pass
        if cmd is None or cmd == "menu":
            menu()
        elif cmd == "install":
            flow_install(args)
        elif cmd == "update":
            flow_update(args)
        elif cmd == "status":
            flow_status(args)
        elif cmd == "config":
            flow_config(args)
        elif cmd == "hotspot":
            flow_hotspot(args)
        elif cmd == "wifi":
            flow_wifi(args)
        elif cmd == "webinstall":
            flow_webinstall(args)
        elif cmd == "audio":
            flow_audio(args)
        elif cmd == "silent":
            flow_why_silent(argparse.Namespace(ip=getattr(args, "ip", None)))
        elif cmd == "voice":
            flow_voice(argparse.Namespace(
                ip=getattr(args, "ip", None), action=getattr(args, "action", None),
                text=getattr(args, "text", None), code=getattr(args, "code", None)))
        elif cmd == "player":
            flow_player(args)
        elif cmd == "adbwifi":
            flow_adb_wifi(args)
        elif cmd == "uninstall":
            flow_uninstall(args)
    except RuntimeError as e:
        die(explain_error(str(e)))
    except OSError as e:
        die(explain_error(str(e)))
    except KeyboardInterrupt:
        print()
        die("Đã huỷ.")


if __name__ == "__main__":
    main()
