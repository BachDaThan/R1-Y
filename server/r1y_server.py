#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R1-Y server - máy chủ nhạc cho loa R1-Y (Phicomm R1).

Kế thừa tìm kiếm chính xác từ:
- Tube (BachDaThan/Tube): yt-dlp ytsearch/scsearch, nhận diện link,
  resolver giả kiểu Invidious (/api/v1/search, /api/v1/videos/<id>).
- LiteTube (BachDaThan/LiteTube): Piped API (/search, /streams/<id>),
  luồng âm thanh do yt-dlp bóc tách, xếp bitrate cao nhất, proxy Range.

Loa dùng server này như một resolver trong mảng JSON:
  [{"type":"custom","url":"http://192.168.2.130:8091"}]
hoặc {"type":"invidious",...} / {"type":"piped",...} đều được.

Chỉ dùng thư viện chuẩn Python + yt-dlp (tự tải nếu thiếu).
Chạy:  python3 r1y_server.py
Cấu hình qua biến môi trường hoặc file r1y.env cùng thư mục (xem r1y.env.example).
"""

import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.9.1"
BASE = os.path.dirname(os.path.abspath(__file__))


def load_env_file():
    path = os.path.join(BASE, "r1y.env")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip()
            if k not in os.environ:
                os.environ[k] = v


load_env_file()


def env(name, default=""):
    v = os.environ.get(name)
    if v is None:
        return default
    return (re.split(r"\s+#", v, maxsplit=1)[0].strip().strip('"').strip("'")) or default


PORT = int(env("PORT", "8091"))
HOST = env("HOST", "0.0.0.0")
ALLOW_PUBLIC = env("ALLOW_PUBLIC", "0") == "1"
YTDLP_PATH = env("YTDLP_PATH", os.path.join(BASE, "yt-dlp"))
YTDLP_URL = env("YTDLP_URL", "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp")
YTDLP_ARGS = shlex.split(env("YTDLP_ARGS", ""))
if env("COOKIES_FILE"):
    YTDLP_ARGS += ["--cookies", env("COOKIES_FILE")]
AUDIO_FORMAT = env("AUDIO_FORMAT", "high").lower()  # high (Opus, nghe hay hơn) | compat (AAC m4a)
MAX_STREAMS = int(env("MAX_STREAMS", "4"))
AUTO_UPDATE = env("AUTO_UPDATE", "1") != "0"

YT_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
YT_ID_RE = re.compile(r"(?:youtube\.com/(?:watch\?v=|shorts/|embed/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})")

CLIENTS = [None, "tv", "web_safari", "ios", "android_vr", "mweb"]
_good_client = {"c": None, "set": False}
_info_cache = {}
# Máy chủ mà YouTube chặn (403 / "Sign in to confirm you're not a bot" /
# "No valid audio streams") thì thử lại ngay cũng vô ích: nhớ 10 phút và
# thử máy chủ kế tiếp, để loa không phải chờ từng lần.
_dead_until = {}
DEAD_TTL = 600
_cache_lock = threading.Lock()
_active = [0]
_active_lock = threading.Lock()


def log(msg):
    print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


# ------------------------------------------------------------------ yt-dlp
def ytdlp_cmd():
    if os.path.exists(YTDLP_PATH):
        return [sys.executable, YTDLP_PATH]
    p = shutil.which("yt-dlp")
    if p:
        return [p]
    return [sys.executable, "-m", "yt_dlp"]


def ytdlp_version():
    try:
        r = subprocess.run(ytdlp_cmd() + ["--version"], capture_output=True, text=True, timeout=30)
        return (r.stdout or "").strip() or "?"
    except Exception:
        return "?"


def download_ytdlp():
    tmp = YTDLP_PATH + ".new"
    req = urllib.request.Request(YTDLP_URL, headers={"User-Agent": "r1y-server"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    chk = subprocess.run([sys.executable, tmp, "--version"], capture_output=True, text=True, timeout=60)
    if chk.returncode != 0 or not re.match(r"^\d{4}\.\d{2}\.\d{2}", (chk.stdout or "").strip()):
        os.remove(tmp)
        raise RuntimeError("file yt-dlp tải về không chạy được")
    os.replace(tmp, YTDLP_PATH)
    log(f"yt-dlp {ytdlp_version()}")


def _kill_tree(proc):
    """
    yt-dlp gọi mạng nên đôi khi treo. Trước đây dùng subprocess.run(timeout=)
    → Python chỉ ném TimeoutExpired và BỎ MẶC tiến trình con, nên hàng loạt
    yt-dlp treo dồn lại làm máy chủ chậm dần rồi đứng hẳn (mỗi lần gọi lại
    thêm độ trễ). Giờ chạy bằng Popen trong nhóm riêng và diệt cả cây.
    """
    try:
        if os.name == "posix":
            os.killpg(os.getpgid(proc.pid), 9)
        else:
            proc.kill()
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def run_ytdlp(args, extra=None, timeout=90):
    """Chạy yt-dlp với thời gian chờ CỨNG; hết giờ thì diệt cả cây tiến trình."""
    cmd = ytdlp_cmd() + ["--no-warnings"] + YTDLP_ARGS + (extra or []) + args
    kwargs = {}
    if os.name == "posix":
        kwargs["start_new_session"] = True          # tạo process group riêng
    else:
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace", **kwargs)
    try:
        out, err = proc.communicate(timeout=timeout)
        rc = proc.returncode
    except subprocess.TimeoutExpired:
        _kill_tree(proc)
        try:
            proc.communicate(timeout=10)
        except Exception:
            pass
        raise RuntimeError("yt-dlp quá %d giây — đã dừng (máy chủ YouTube chậm/chặn IP)"
                           % timeout)
    return subprocess.CompletedProcess(cmd, rc, out, err)


def ytdlp_json(args, extra=None, timeout=90):
    r = run_ytdlp(args, extra, timeout)
    if r.returncode != 0:
        lines = (r.stderr or r.stdout or "").strip().splitlines()
        raise RuntimeError(lines[-1][:300] if lines else "yt-dlp lỗi")
    out = (r.stdout or "").strip()
    return json.loads(out.splitlines()[-1])


def video_info(vid):
    with _cache_lock:
        c = _info_cache.get(vid)
        if c and c["exp"] > time.time():
            return c["d"]
    order = list(CLIENTS)
    if _good_client["set"] and _good_client["c"] in order:
        order.remove(_good_client["c"])
        order.insert(0, _good_client["c"])
    # bỏ qua client vừa bị YouTube chặn gần đây (không thử lại ngay)
    now = time.time()
    fresh = [c for c in order if _dead_until.get(c or "mac dinh", 0) < now]
    order = fresh + [c for c in order if c not in fresh]
    errs = []
    for cl in order:
        if _dead_until.get(cl or "mac dinh", 0) > now:
            errs.append(f"{cl or 'mac dinh'}: đang bị chặn, bỏ qua 10 phút")
            continue
        extra = [] if cl is None else ["--extractor-args", f"youtube:player_client={cl}"]
        try:
            d = ytdlp_json(["-J", f"https://www.youtube.com/watch?v={vid}"], extra, 90)
        except Exception as e:
            errs.append(f"{cl or 'mac dinh'}: {e}")
            msg = str(e).lower()
            if ("sign in" in msg or "not a bot" in msg or "403" in msg
                    or "no valid audio" in msg or "quá" in msg):
                _dead_until[cl or "mac dinh"] = time.time() + DEAD_TTL
            continue
        if d.get("formats"):
            _good_client.update({"c": cl, "set": True})
            with _cache_lock:
                _info_cache[vid] = {"d": d, "exp": time.time() + 3000}
                if len(_info_cache) > 150:
                    for k in sorted(_info_cache, key=lambda k: _info_cache[k]["exp"])[:50]:
                        _info_cache.pop(k, None)
            return d
    raise RuntimeError(errs[0] if errs else "yt-dlp lỗi")


def audio_ladder(d):
    """Thang chất lượng có tự hạ cấp (tốt nhất trước).

    Bậc 1-2: Opus (webm) ≥128k / <128k
    Bậc 3-4: AAC/m4a ≥128k / <128k
    Bậc 5  : codec audio khác (vorbis, mp3...)
    Bậc 6  : âm thanh lấy từ luồng video (khi server chỉ trả video có tiếng)

    AUDIO_FORMAT=compat -> đảo ưu tiên AAC lên trước Opus.
    """
    high = AUDIO_FORMAT not in ("compat", "aac", "m4a")
    out = []
    for f in (d.get("formats") or []):
        url = f.get("url")
        if not url:
            continue
        acodec = (f.get("acodec") or "").lower()
        vcodec = (f.get("vcodec") or "").lower()
        has_audio = acodec not in ("", "none")
        is_video = vcodec not in ("", "none")
        if not has_audio:
            continue
        abr = float(f.get("abr") or 0)
        ext = (f.get("ext") or "").lower()
        if is_video:
            kind, tier = "video", 6
        elif "opus" in acodec:
            kind, tier = "opus", (1 if abr >= 128 else 2)
        elif "mp4a" in acodec or "aac" in acodec or ext == "m4a":
            kind, tier = "aac", (3 if abr >= 128 else 4)
        else:
            kind, tier = "other", 5
        if not high and kind in ("opus", "aac"):
            tier = {1: 3, 2: 4, 3: 1, 4: 2}[tier]
        out.append({"url": url, "tier": tier, "kind": kind, "abr": abr,
                    "ext": ext, "acodec": acodec, "fmt": f})
    if not out:
        raise RuntimeError("Không có luồng audio nào dùng được")
    out.sort(key=lambda c: (c["tier"], -c["abr"]))
    # khử trùng lặp URL
    seen, uniq = set(), []
    for c in out:
        if c["url"] in seen:
            continue
        seen.add(c["url"])
        uniq.append(c)
    return uniq


def pick_audio(d):
    """Chọn luồng audio tốt nhất (bậc đầu của thang)."""
    return audio_ladder(d)[0]["fmt"]


def ladder_json(d):
    """Thang cho API: [{"tier":1,"label":"Opus cao","abr":160,...}]"""
    labels = {1: "Opus chất lượng cao", 2: "Opus tiết kiệm", 3: "AAC chất lượng cao",
              4: "AAC tiết kiệm", 5: "Codec khác", 6: "Âm thanh từ video"}
    out = []
    for c in audio_ladder(d):
        out.append({"tier": c["tier"], "label": labels.get(c["tier"], "?"),
                    "codec": c["kind"], "abr": c["abr"], "ext": c["ext"]})
    return out


def search_text(q, n):
    """Tìm theo từ khóa bằng ytsearch (Tube)."""
    n = max(5, min(int(n or 20), 50))
    d = ytdlp_json(["--flat-playlist", "-J", f"ytsearch{n}:{q}"], timeout=60 + n)
    out, seen = [], set()
    for e in d.get("entries") or []:
        if not e or not e.get("id") or e["id"] in seen:
            continue
        if not YT_RE.match(str(e["id"])):
            continue
        seen.add(e["id"])
        out.append({
            "videoId": e["id"],
            "title": e.get("title") or e["id"],
            "author": e.get("uploader") or e.get("channel") or "",
            "lengthSeconds": int(e.get("duration") or 0),
        })
    return out[:n]


# ------------------------------------------------------------------ HTTP
def ip_allowed(ip):
    if ALLOW_PUBLIC:
        return True
    import ipaddress
    nets = ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "100.64.0.0/10", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10")
    try:
        a = ipaddress.ip_address(ip.split("%")[0])
        if getattr(a, "ipv4_mapped", None):
            a = a.ipv4_mapped
        return any(a in ipaddress.ip_network(x) for x in nets)
    except Exception:
        return False


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 9))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


class Handler(BaseHTTPRequestHandler):
    server_version = f"R1Y/{VERSION}"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False)
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _base(self):
        host = self.headers.get("Host") or f"{lan_ip()}:{PORT}"
        proto = self.headers.get("X-Forwarded-Proto") or "http"
        return f"{proto}://{host}"

    def do_GET(self):
        self._route("GET")

    def do_HEAD(self):
        self._route("HEAD")

    def do_POST(self):
        self._route("POST")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def _route(self, method):
        if not ip_allowed(self.client_address[0]):
            return self._send(403, {"error": "forbidden"})
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)
        try:
            if path in ("/", "/health", "/api/status"):
                return self._send(200, {"app": "R1-Y server", "version": VERSION,
                                        "ytdlp": ytdlp_version(), "port": PORT})
            # Invidious (Tube)
            if path == "/api/v1/search" and method == "GET":
                q = (qs.get("q") or [""])[0]
                return self._send(200, search_text(q, 20))
            m = re.fullmatch(r"/api/v1/videos/([A-Za-z0-9_-]{11})", path)
            if m and method == "GET":
                return self._send(200, self._invidious_video(m.group(1)))
            # Piped (LiteTube)
            if path == "/search" and method == "GET":
                q = (qs.get("q") or [""])[0]
                items = [{"url": f"/watch?v={r['videoId']}", "title": r["title"],
                          "uploaderName": r["author"], "duration": r["lengthSeconds"]}
                         for r in search_text(q, 20)]
                return self._send(200, {"items": items, "nextpage": ""})
            m = re.fullmatch(r"/streams/([A-Za-z0-9_-]{11})", path)
            if m and method == "GET":
                return self._send(200, self._piped_streams(m.group(1)))
            # Custom (Tube)
            if path == "/api/search" and method == "GET":
                q = (qs.get("q") or [""])[0]
                return self._send(200, search_text(q, 20))
            if path in ("/api/play", "/api/resolve") and method == "GET":
                # Dán link YouTube vào là phát được luôn; không phải link thì tìm rồi phát
                q = (qs.get("q") or qs.get("url") or qs.get("query") or [""])[0]
                if not q.strip():
                    return self._send(400, {"error": "thiếu ?q=link hoặc từ khóa"})
                return self._send(200, self._resolve_query(q))
            m = re.fullmatch(r"/stream/([A-Za-z0-9_-]{11})", path)
            if m and method in ("GET", "HEAD"):
                return self._stream(m.group(1), method == "HEAD")
            return self._send(404, {"error": "not found"})
        except Exception as e:
            log(f"LOI {method} {path}: {e}")
            return self._send(500, {"error": str(e)[:300]})

    def _invidious_video(self, vid):
        d = video_info(vid)
        f = pick_audio(d)
        base = self._base()
        mime = "audio/mp4" if f.get("ext") == "m4a" else "audio/webm"
        typ = f'{mime}; codecs="mp4a.40.2"' if mime == "audio/mp4" else f'{mime}; codecs="opus"'
        return {
            "videoId": vid, "title": d.get("title") or vid,
            "author": d.get("uploader") or "", "lengthSeconds": int(d.get("duration") or 0),
            "adaptiveFormats": [{"url": f"{base}/stream/{vid}", "itag": "140", "type": typ,
                                 "bitrate": str(int((f.get("abr") or 128) * 1000)),
                                 "container": f.get("ext") or "m4a",
                                 "encoding": "aac" if mime == "audio/mp4" else "opus",
                                 "audioQuality": "AUDIO_QUALITY_MEDIUM",
                                 "audioSampleRate": "44100", "audioChannels": 2}],
            "formatStreams": [{"url": f"{base}/stream/{vid}", "itag": "18", "type": typ,
                               "container": f.get("ext") or "m4a"}],
        }

    def _resolve_query(self, q):
        """Nhận link YouTube (watch?v=, youtu.be, shorts, live) hoặc từ khóa -> videoId + luồng."""
        m = YT_ID_RE.search(q) or (YT_RE.match(q.strip()) and None)
        vid = None
        if m:
            vid = m.group(1)
        elif YT_RE.match(q.strip()):
            vid = q.strip()
        title = ""
        if vid is None:
            found = search_text(q, 1)
            if not found:
                raise RuntimeError("không tìm thấy: " + q)
            vid = found[0]["videoId"]
            title = found[0].get("title", "")
        d = video_info(vid)
        best = audio_ladder(d)[0]
        f = best["fmt"]
        base = self._base()
        return {
            "ok": True, "videoId": vid, "title": d.get("title") or title,
            "author": d.get("uploader") or "", "duration": int(d.get("duration") or 0),
            "audioUrl": base + "/stream/" + vid,
            "tier": best["tier"], "ext": f.get("ext"), "abr": f.get("abr"),
            "codec": f.get("acodec"),
            "quality": "high" if AUDIO_FORMAT not in ("compat", "aac", "m4a") else "compat",
            "ladder": ladder_json(d),
        }

    def _piped_streams(self, vid):
        d = video_info(vid)
        base = self._base()
        fmts = [f for f in (d.get("formats") or []) if f.get("acodec") not in (None, "none")]
        fmts.sort(key=lambda f: (f.get("abr") or 0), reverse=True)  # bitrate cao nhất trước (LiteTube)
        audio = []
        for f in fmts[:6]:
            audio.append({
                "url": f"{base}/stream/{vid}", "format": (f.get("ext") or "").upper(),
                "quality": f"{int(f.get('abr') or 0)}kbps",
                "bitrate": int((f.get("abr") or 0) * 1000),
                "itag": f.get("format_id") or "140",
                "mimeType": "audio/mp4" if f.get("ext") == "m4a" else "audio/webm",
            })
        return {"url": f"https://www.youtube.com/watch?v={vid}", "title": d.get("title") or vid,
                "uploader": d.get("uploader") or "", "duration": int(d.get("duration") or 0),
                "audioStreams": audio, "videoStreams": []}

    def _stream(self, vid, head_only):
        """Phát luồng audio. Tự hạ chất lượng dần nếu bậc cao bị lỗi (403/hết hạn)."""
        with _active_lock:
            if _active[0] >= MAX_STREAMS:
                return self._send(503, {"error": "Máy đang bận, thử lại sau"})
            _active[0] += 1
        try:
            d = video_info(vid)
            ladder = audio_ladder(d)
            last = None
            # Thử từng bậc: Opus cao -> Opus thấp -> AAC -> codec khác -> audio từ video
            for cand in ladder:
                ok, last = self._pipe(cand, head_only, vid)
                if ok:
                    return
                log(f"Bậc {cand['tier']} ({cand['kind']}) lỗi: {last} — hạ bậc")
            return self._send(502, {"error": f"không lấy được luồng nào ({last})"})
        finally:
            with _active_lock:
                _active[0] -= 1

    def _pipe(self, cand, head_only, vid):
        """Mở một luồng upstream và chuyển tiếp. Trả về (thành_công, lỗi)."""
        url = cand["url"]
        mime = "audio/mp4" if cand["ext"] in ("m4a", "mp4") else "audio/webm"
        if cand["kind"] == "video":
            mime = "video/mp4"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            rng = self.headers.get("Range")
            if rng:
                req.add_header("Range", rng)
            with urllib.request.urlopen(req, timeout=30) as r:
                self.send_response(r.status)
                self.send_header("Content-Type", mime)
                self.send_header("X-R1Y-Tier", str(cand["tier"]))
                self.send_header("X-R1Y-Codec", cand["kind"])
                for k in ("Content-Length", "Content-Range", "Accept-Ranges"):
                    v = r.headers.get(k)
                    if v:
                        self.send_header(k, v)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                if not head_only:
                    try:
                        shutil.copyfileobj(r, self.wfile, 65536)
                    except (BrokenPipeError, ConnectionResetError):
                        pass  # loa ngắt kết nối — không phải lỗi
            return True, None
        except urllib.error.HTTPError as e:
            if e.code == 403:
                with _cache_lock:
                    _info_cache.pop(vid, None)
            return False, f"upstream {e.code}"
        except Exception as e:
            return False, str(e)[:120]


def main():
    log(f"R1-Y server {VERSION} | cổng {PORT} | yt-dlp {ytdlp_version()} | audio={AUDIO_FORMAT}")
    if AUTO_UPDATE and not os.path.exists(YTDLP_PATH) and not shutil.which("yt-dlp"):
        try:
            download_ytdlp()
        except Exception as e:
            log(f"tải yt-dlp lỗi (sẽ dùng yt-dlp hệ thống nếu có): {e}")
    try:
        ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
