#!/bin/sh
# 作业板 · 网页端统一入口（桌面图标「作业板.app」和「启动-网页版.command」都走这里）
#
#   ./launch.sh --probe       已经在跑就打印地址，没跑就什么都不打印
#   ./launch.sh --start-only  只把服务拉起来，不开浏览器（桌面图标用；不阻塞 applet）
#   ./launch.sh               已经在跑就直接开浏览器，没跑就先起服务再开
#
# 装过 install-service.sh 之后，服务由 launchd 常驻（开机自启、关终端不影响），
# 这里只负责把页面打开；没装过就退回原来那套前台启动。
#
# 为什么要先探测：serve.py 每次都会挑一个新的空端口，
# 不先看一眼就双击，容易变成同时开着两个服务、两个页面各改各的。

HERE=$(cd "$(dirname "$0")" && pwd)
LABEL="cn.yiweiqiu.homeworkboard.web"
DOMAIN="gui/$(id -u)"
PORT="${PORT:-8123}"

# serve.py 从 8123 起往上挑，正常只会落在最前面几个端口上
probe() {
  for p in 8123 8124 8125 8126 8127 8128 8129 8130; do
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

if [ "$1" = "--start-only" ]; then
  if URL=$(probe); then echo "$URL"; exit 0; fi
  if [ -f "$HOME/Library/LaunchAgents/$LABEL.plist" ] \
     && launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
    launchctl kickstart -k "$DOMAIN/$LABEL" 2>/dev/null \
      || launchctl kickstart "$DOMAIN/$LABEL" 2>/dev/null || true
    i=0
    while [ "$i" -lt 40 ]; do
      if URL=$(probe); then echo "$URL"; exit 0; fi
      i=$((i + 1))
      sleep 0.25
    done
  fi
  # 这里绝不 exec 前台服务：调用方是桌面图标，阻塞会让图标一直转圈
  echo "服务起不来，先跑一次 ./install-service.sh" >&2
  exit 1
fi

if URL=$(probe); then
  echo "服务已经在跑：$URL"
  open "$URL"
  exit 0
fi

# 没在跑：装了常驻服务就叫它起来（kickstart 对未运行的 job 会直接启动）
if [ -f "$HOME/Library/LaunchAgents/$LABEL.plist" ] \
   && launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
  launchctl kickstart -k "$DOMAIN/$LABEL" 2>/dev/null \
    || launchctl kickstart "$DOMAIN/$LABEL" 2>/dev/null || true
  i=0
  while [ "$i" -lt 40 ]; do
    if URL=$(probe); then
      echo "服务已由后台拉起：$URL"
      open "$URL"
      exit 0
    fi
    i=$((i + 1))
    sleep 0.25
  done
  echo "服务没能起来，看日志：tail -20 /tmp/作业板-网页端.log" >&2
fi

# 没装常驻服务（或起不来）：前台起服务，行为和以前一样
exec "$HERE/start.sh" "$PORT"
