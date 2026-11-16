#!/bin/sh
# 作业板 · 网页端统一入口（桌面图标「作业板.app」和「启动-网页版.command」都走这里）
#
#   ./launch.sh --probe   已经在跑就打印地址，没跑就什么都不打印
#   ./launch.sh           已经在跑就直接开浏览器，没跑就起服务
#
# 为什么要先探测：serve.py 每次都会挑一个新的空端口，
# 不先看一眼就双击，容易变成同时开着两个服务、两个页面各改各的。

HERE=$(cd "$(dirname "$0")" && pwd)

# serve.py 从 8123 起往上挑，正常只会落在最前面几个端口上
probe() {
  for p in 8123 8124 8125 8126 8127 8128; do
    # 认内容而不是认端口：别的程序占了 8123 也不会被误当成作业板
    if curl -fsS -m 0.25 "http://127.0.0.1:$p/index.html" 2>/dev/null | grep -q "作业板"; then
      echo "http://127.0.0.1:$p/index.html"
      return 0
    fi
  done
  return 1
}

if [ "$1" = "--probe" ]; then
  if URL=$(probe); then echo "$URL"; fi
  exit 0
fi

if URL=$(probe); then
  echo "服务已经在跑：$URL"
  open "$URL"
  exit 0
fi

exec "$HERE/start.sh"
