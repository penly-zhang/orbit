"""Serve this version's APK, ZIP, installation page and checksums over the LAN."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import zipfile
from email.utils import formatdate
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit
from urllib.request import ProxyHandler, build_opener
from artifact_config import ROOT, VERSION, ARTIFACT, APK, ZIP

STATE = ROOT / ".tools/lan-download.json"


def set_lan_links(url):
    index = ARTIFACT / "index.html"
    html = index.read_text(encoding="utf-8")
    html = re.sub(r'href="(?:https?://[^"]*/)?(?:' + re.escape(APK) + r'|(?:orbit|codex-pocket)\.apk)"', 'href="' + url + APK + '"', html)
    html = re.sub(r'href="(?:https?://[^"]*/)?' + re.escape(ZIP) + r'"', 'href="' + url + ZIP + '"', html)
    index.write_text(html, encoding="utf-8")


def lan_address():
    for interface in ["en0", "en1", "en2"]:
        result = subprocess.run(["/usr/sbin/ipconfig", "getifaddr", interface], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        address = result.stdout.decode().strip()
        if result.returncode == 0 and address:
            return address
    raise RuntimeError("No active LAN IPv4 address found")


class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ARTIFACT), **kwargs)

    def send_head(self):
        self.remaining = None
        path = unquote(urlsplit(self.path).path)
        if path == "/healthz":
            payload = json.dumps({"service": "orbit-apk", "version": 2, "appVersion": VERSION}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            from io import BytesIO
            return BytesIO(payload)
        downloads = {"/" + APK: APK, "/orbit.apk": APK, "/codex-pocket.apk": APK, "/" + ZIP: ZIP}
        if path in downloads:
            name = downloads[path]
            source = (ARTIFACT / name).open("rb")
            metadata = os.fstat(source.fileno())
            size = metadata.st_size
            etag = '"{:x}-{:x}"'.format(metadata.st_mtime_ns, size)
            last_modified = formatdate(metadata.st_mtime, usegmt=True)
            start, end = 0, size - 1
            range_header = self.headers.get("Range")
            if_range = self.headers.get("If-Range")
            if if_range and if_range not in [etag, last_modified]:
                range_header = None
            if range_header:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
                try:
                    if not match or not any(match.groups()):
                        raise ValueError()
                    first, last = match.groups()
                    if first:
                        start = int(first)
                        end = min(int(last), size - 1) if last else size - 1
                    else:
                        suffix = int(last)
                        if suffix <= 0:
                            raise ValueError()
                        start = max(0, size - suffix)
                    if not 0 <= start <= end < size:
                        raise ValueError()
                except ValueError:
                    source.close()
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */{}".format(size))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return None
            self.send_response(206 if range_header else 200)
            self.send_header("Content-Type", "application/octet-stream" if name == APK else "application/zip")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("ETag", etag)
            self.send_header("Last-Modified", last_modified)
            self.send_header("Content-Disposition", 'attachment; filename="Orbit-{}.{}"'.format(VERSION, "apk" if name == APK else "zip"))
            if range_header:
                self.send_header("Content-Range", "bytes {}-{}/{}".format(start, end, size))
            self.end_headers()
            self.remaining = end - start + 1
            source.seek(start)
            return source
        if path not in ["/", "/index.html", "/SHA256SUMS.txt"]:
            self.send_error(404)
            return None
        self.path = "/index.html" if path == "/" else path
        return super().send_head()

    def end_headers(self):
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.close_connection = True
        super().end_headers()

    def copyfile(self, source, outputfile):
        remaining = self.remaining
        try:
            while remaining is None or remaining > 0:
                chunk = source.read(65536 if remaining is None else min(65536, remaining))
                if not chunk:
                    break
                outputfile.write(chunk)
                if remaining is not None:
                    remaining -= len(chunk)
            outputfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def guess_type(self, path):
        if path.endswith(".apk"):
            return "application/vnd.android.package-archive"
        return super().guess_type(path)

    def list_directory(self, path):
        self.send_error(404)
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", action="store_true")
    parser.add_argument("--bind")
    parser.add_argument("--port", type=int, default=8765)
    options = parser.parse_args()
    address = options.bind or lan_address()
    if options.serve:
        ThreadingHTTPServer((address, options.port), Handler).serve_forever()
        return

    assert (ARTIFACT / APK).is_file(), "APK missing"
    archive = ARTIFACT / ZIP
    if not archive.exists() or archive.stat().st_mtime < (ARTIFACT / APK).stat().st_mtime:
        with zipfile.ZipFile(str(archive), "w", compression=zipfile.ZIP_STORED) as bundle:
            bundle.write(str(ARTIFACT / APK), "Orbit-" + VERSION + ".apk")
        with zipfile.ZipFile(str(archive)) as bundle:
            assert bundle.testzip() is None
    # On the LAN the APK can be downloaded directly, without the preview tool's MIME filter.
    index = ARTIFACT / "index.html"
    html = index.read_text(encoding="utf-8")
    html = re.sub(r"data:application/vnd\.android\.package-archive;base64,[A-Za-z0-9+/=]+", APK, html)
    html = html.replace('href="' + APK + '"', 'href="orbit.apk"')
    if 'href="' + ZIP + '"' not in html:
        html = html.replace('下载安卓 APK ↗</a>', '下载安卓 APK ↗</a> <a class="button" href="' + ZIP + '" download>备用 ZIP 下载 ↗</a>')
        html = html.replace('<section class="setup">', '<section class="setup"><p>如果 APK 下载一直停在 100%，先取消旧下载，再下载 ZIP。解压后点击其中的 APK 安装。</p>')
    index.write_text(html, encoding="utf-8")
    opener = build_opener(ProxyHandler({}))
    if STATE.exists():
        old = json.loads(STATE.read_text())
        try:
            response = opener.open(old["url"] + "healthz", timeout=2)
            health = json.load(response)
            if old["host"] == address and health.get("service") == "orbit-apk" and health.get("version") == 2 and health.get("appVersion") == VERSION and options.port <= old["port"] < options.port + 10:
                set_lan_links(old["url"])
                print(json.dumps(old, ensure_ascii=False))
                return
        except Exception:
            pass
    for port in range(options.port, options.port + 10):
        probe = socket.socket()
        try:
            probe.bind((address, port))
            break
        except OSError:
            if port == options.port + 9:
                raise
        finally:
            probe.close()
    log = (ROOT / ".tools/lan-download.log").open("ab")
    child = subprocess.Popen([sys.executable, "-u", str(Path(__file__).resolve()), "--serve", "--bind", address, "--port", str(port)],
                             cwd=str(ROOT), stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    url = "http://{}:{}/".format(address, port)
    for _ in range(40):
        try:
            response = opener.open(url + "healthz", timeout=1)
            health = json.load(response)
            if health.get("service") == "orbit-apk" and health.get("version") == 2 and health.get("appVersion") == VERSION:
                set_lan_links(url)
                record = {"pid": child.pid, "host": address, "port": port, "url": url, "apkUrl": url + APK, "zipUrl": url + ZIP, "appVersion": VERSION}
                STATE.write_text(json.dumps(record, ensure_ascii=False, indent=2))
                print(json.dumps(record, ensure_ascii=False))
                return
        except Exception:
            if child.poll() is not None:
                raise RuntimeError("Download service exited; see .tools/lan-download.log")
            time.sleep(0.1)
    raise RuntimeError("Download service did not become ready")


if __name__ == "__main__":
    main()
