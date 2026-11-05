#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""作业板 · 电脑端录入页 —— 本地静态服务

Windows / macOS / Linux 通用（只需要 Python 3.7+，不用装任何依赖）。

为什么不直接双击 index.html：
  Chrome / Edge 在 file:// 下会拒绝「选择文件夹」的写入授权，页面拿不到目录句柄就存不了。
  走 http://127.0.0.1 是「安全上下文」，File System Access API 才能正常工作。

用法：
  python3 serve.py            # 自动挑一个空端口并打开浏览器
  python3 serve.py 8123       # 指定端口
  python3 serve.py --no-open  # 只起服务，不自动开浏览器
"""

import os
import pathlib
import socket
import sys
import threading
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent
PORT_FIRST, PORT_LAST = 8123, 8160


class Handler(SimpleHTTPRequestHandler):
    """只服务本文件所在目录，且禁掉缓存（改完 index.html 刷新就能看到）。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):  # 只报错误，正常请求不刷屏
        if args and str(args[1]).startswith(("4", "5")):
            sys.stderr.write("  %s %s\n" % (args[1], args[0]))


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
    print(" 首次使用点「选择文件夹」，选中坚果云里的 作业板 目录")
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
