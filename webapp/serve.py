#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""作业板 · 电脑端录入页 —— 本地静态服务 + WebDAV 转发

Windows / macOS / Linux 通用（只需要 Python 3.7+，不用装任何依赖）。

为什么需要起这个服务，而不是直接双击 index.html：
  1. 页面要用坚果云账号直连 /dav/ ，而坚果云响应里没有 Access-Control-Allow-Origin，
     浏览器带 Authorization 的请求会卡在预检阶段，必须由本机代跑一趟（/dav-proxy）
  2. 保存后要往局域网喊一嗓子通知电视，浏览器发不了 UDP 广播（/ping）

两者都只走 127.0.0.1，不监听外网。

用法：
  python3 serve.py            # 自动挑一个空端口并打开浏览器
  python3 serve.py 8123       # 指定端口
  python3 serve.py --no-open  # 只起服务，不自动开浏览器
"""

import json
import os
import pathlib
import socket
import sys
import threading
import time
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.parse import parse_qs, quote, urlparse
from urllib.request import Request, urlopen

ROOT = pathlib.Path(__file__).resolve().parent
PORT_FIRST, PORT_LAST = 8123, 8160

PROXY_PATH = "/dav-proxy"

# 和手机端、电视端写死的同一套暗号（core/.../sync/SyncPing.kt）
PING_PORT = 47823
PING_MAGIC = b"HOMEWORK-BOARD/SYNC/1"
PING_REPEAT = 3
PING_GAP_S = 0.12
# 坚果云把文件传上去要几秒，所以说两遍：立刻一遍，6 秒后再一遍
PING_ROUNDS_DELAY = (0.0, 6.0)


class Handler(SimpleHTTPRequestHandler):
    """只服务本文件所在目录，且禁掉缓存（改完 index.html 刷新就能看到）。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_POST(self):
        # 页面保存成功后调这里：本机替它往局域网喊一嗓子，电视就立刻去拉
        # （浏览器发不了 UDP 广播，只能借服务端的手）
        if self.path.split("?")[0] != "/ping":
            self.send_error(404, "not found")
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            rounds = announce_later()
            body = json.dumps({"ok": True, "rounds": rounds}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as exc:  # 喊不出去也不能让页面以为保存失败
            self.send_error(500, str(exc))

    # ---------- WebDAV 转发 ----------
    #
    # 页面和 dav.jianguoyun.com 不同源，而坚果云响应里没有任何
    # Access-Control-Allow-Origin，浏览器带 Authorization 的请求会卡在预检，
    # 所以只能由本机服务代跑一趟。这里只做透明转发：
    # Authorization 头跟着浏览器的请求走，服务端不读也不存。
    # 只允许转发到 http(s) 的绝对地址，且服务只监听 127.0.0.1。

    def _is_proxy(self):
        return self.path.split("?")[0] == PROXY_PATH

    def _proxy(self):
        query = parse_qs(urlparse(self.path).query)
        base = (query.get("base") or [""])[0].rstrip("/")
        sub = (query.get("path") or [""])[0]
        if not base.startswith(("https://", "http://")):
            self.send_error(400, "base must be an absolute http(s) url")
            return

        target = base
        for seg in sub.split("/"):
            if seg:
                target += "/" + seg
        target = quote(target, safe=":/")

        auth = self.headers.get("Authorization")
        if not auth:
            self.send_error(401, "missing Authorization")
            return

        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None

        req = Request(target, data=body, method=self.command)
        req.add_header("Authorization", auth)
        for name in ("Depth", "Content-Type", "Accept", "If-None-Match",
                     "If-Match", "Destination", "Overwrite", "Content-Range"):
            value = self.headers.get(name)
            if value:
                req.add_header(name, value)

        try:
            resp = urlopen(req, timeout=60)
        except HTTPError as exc:   # 401/404/405 这些要原样交回页面判断
            resp = exc
        except Exception as exc:
            self.send_error(502, "upstream failed: %s" % exc)
            return

        payload = resp.read()
        self.send_response(resp.status)
        for name in ("Content-Type", "ETag", "Last-Modified"):
            value = resp.headers.get(name)
            if value:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def do_GET(self):
        if self._is_proxy():
            return self._proxy()
        super().do_GET()

    def do_HEAD(self):
        if self._is_proxy():
            return self._proxy()
        super().do_HEAD()

    def do_PUT(self):
        if self._is_proxy():
            return self._proxy()
        self.send_error(405, "method not allowed")

    def do_DELETE(self):
        if self._is_proxy():
            return self._proxy()
        self.send_error(405, "method not allowed")

    def do_PROPFIND(self):
        if self._is_proxy():
            return self._proxy()
        self.send_error(405, "method not allowed")

    def do_MKCOL(self):
        if self._is_proxy():
            return self._proxy()
        self.send_error(405, "method not allowed")

    def do_MOVE(self):
        if self._is_proxy():
            return self._proxy()
        self.send_error(405, "method not allowed")

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):  # 只报错误，正常请求不刷屏
        if args and str(args[1]).startswith(("4", "5")):
            sys.stderr.write("  %s %s\n" % (args[1], args[0]))


def local_ipv4():
    """本机在局域网里的地址。用 UDP「连」一下不发包，只是让系统选出出口网卡。"""
    for probe in ("223.5.5.5", "8.8.8.8", "192.168.1.1"):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.settimeout(0.3)
                sock.connect((probe, 53))
                addr = sock.getsockname()[0]
                if addr and not addr.startswith("127."):
                    return addr
        except OSError:
            continue
    return None


def broadcast_targets():
    """各网卡的子网广播地址（192.168.31.255 这种）+ 受限广播兜底。

    子网广播比 255.255.255.255 更容易被路由器转发；拿不到本机地址时只发受限广播。
    """
    targets = set()
    ip = local_ipv4()
    if ip:
        parts = ip.split(".")
        if len(parts) == 4:
            targets.add("%s.%s.%s.255" % (parts[0], parts[1], parts[2]))
    targets.add("255.255.255.255")
    return sorted(targets)


def broadcast_once():
    """发一轮（连发几遍抗丢包），返回发包数。失败静默。"""
    sent = 0
    targets = broadcast_targets()
    for i in range(PING_REPEAT):
        for host in targets:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    sock.sendto(PING_MAGIC, (host, PING_PORT))
                    sent += 1
            except OSError:
                pass
        if i < PING_REPEAT - 1:
            time.sleep(PING_GAP_S)
    return sent


def announce_later():
    """立刻喊一轮，再延后喊一轮（等坚果云把文件传上去）。返回将要喊的轮数。"""
    def run(delay):
        if delay:
            threading.Event().wait(delay)
        broadcast_once()

    for delay in PING_ROUNDS_DELAY:
        if delay == 0:
            run(0)
        else:
            threading.Thread(target=run, args=(delay,), daemon=True).start()
    return len(PING_ROUNDS_DELAY)


def pick_port(start, end):
    for port in range(start, end + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                continue
            return port
    return None


def main(argv):
    args = [a for a in argv[1:]]
    no_open = "--no-open" in args
    args = [a for a in args if not a.startswith("--")]

    wanted = PORT_FIRST
    if args:
        try:
            wanted = int(args[0])
        except ValueError:
            print("端口要写数字，例如：python3 serve.py 8123")
            return 2

    port = pick_port(wanted, max(wanted, PORT_LAST))
    if port is None:
        print("端口 %d~%d 都被占用了，先把别的服务关掉再试。" % (wanted, max(wanted, PORT_LAST)))
        return 1

    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True
    url = "http://127.0.0.1:%d/index.html" % port

    line = "─" * 52
    print(line)
    print(" 作业板录入页已启动： %s" % url)
    print(" 首次使用填坚果云账号 + 应用专用密码（不是登录密码）")
    print(" 保存后会自动通知局域网里的电视去拉（端口 %d）" % PING_PORT)
    print("")
    print(" 关闭这个窗口（或按 Ctrl+C）即停止服务，录入过程中别关")
    print(line)
    sys.stdout.flush()

    if not no_open:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    os.chdir(ROOT)  # 双击运行时工作目录可能是家目录
    sys.exit(main(sys.argv))
