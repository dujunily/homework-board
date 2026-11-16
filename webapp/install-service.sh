#!/bin/sh
# 作业板 · 网页端常驻服务（macOS launchd）
#
#   ./install-service.sh        装成开机自启的后台服务（以后双击桌面图标只开页面）
#   ./install-service.sh --uninstall   卸掉，恢复成「双击才起」的老样子
#   ./install-service.sh --status      看它现在活着没
#
# 为什么要走 launchd：applet 里用 `do shell script ... &` 起的服务属于临时任务，
# 启动它的程序一退出就被系统连窝端掉；launchd 托管的进程父级是 launchd 自己，
# 终端关不关、重启机器都与它无关。

set -e

HERE=$(cd "$(dirname "$0")" && pwd)
LABEL="cn.yiweiqiu.homeworkboard.web"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="/tmp/作业板-网页端.log"
PORT="${PORT:-8123}"
UID_N=$(id -u)
DOMAIN="gui/$UID_N"

loaded() {
  launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1
}

do_status() {
  if loaded; then
    echo "已装载：$LABEL"
    launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E "^\s*(state|pid|last exit code) " | sed 's/^[[:space:]]*/  /'
  else
    echo "未装载（服务由 launch.sh 临时拉起，关闭终端就没了）"
  fi
  # 真正能不能用，还是得看页面
  for p in 8123 8124 8125 8126 8127 8128 8129 8130; do
    if curl -fsS -m 0.25 "http://127.0.0.1:$p/index.html" 2>/dev/null | grep -q "作业板"; then
      echo "  页面可用：http://127.0.0.1:$p/index.html"
      return 0
    fi
  done
  echo "  页面打不开（服务没在跑）"
  return 1
}

do_uninstall() {
  if loaded; then
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  fi
  rm -f "$PLIST"
  # 顺手把仍在跑的旧进程收掉，免得卸完还占着端口
  pkill -f "$HERE/serve.py" 2>/dev/null || true
  echo "已卸载。以后双击「启动-网页版.command」或桌面图标才会起服务。"
}

case "$1" in
  --status) do_status; exit 0 ;;
  --uninstall) do_uninstall; exit 0 ;;
esac

# ---------- 安装 ----------

mkdir -p "$HOME/Library/LaunchAgents"

# 先写临时文件再原子替换：launchd 不认半截 plist
TMP=$(mktemp)
cat > "$TMP" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/sh</string>
    <string>$HERE/start.sh</string>
    <string>--no-open</string>
    <string>$PORT</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$HERE</string>
  <key>EnvironmentVariables</key>
  <dict>
    <!-- launchd 不读登录 shell 的 PATH，不补这条就找不到 homebrew 里的 python3 -->
    <key>PATH</key>
    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
    <!-- serve.py 见到这个就不再打印「关掉窗口即停止」那套前台话术 -->
    <key>WORKBOARD_DAEMON</key>
    <string>1</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <!-- 只有异常退出（崩溃、被杀）才重启；bootout 停掉不会自己回来 -->
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>ThrottleInterval</key>
  <integer>10</integer>
  <key>StandardOutPath</key>
  <string>$LOG</string>
  <key>StandardErrorPath</key>
  <string>$LOG</string>
  <key>ProcessType</key>
  <string>Background</string>
</dict>
</plist>
PLIST

if ! plutil -lint "$TMP" >/dev/null 2>&1; then
  echo "plist 格式有问题，中止：" >&2
  plutil -lint "$TMP" >&2
  rm -f "$TMP"
  exit 1
fi

mv "$TMP" "$PLIST"
chmod 644 "$PLIST"

# 已经装过就先卸再装，让新配置立即生效（bootout 对没装载的也返回 0）
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
# 老版本可能还留着手动起的服务，占着端口会让新服务一路往后挪
pkill -f "$HERE/serve.py" 2>/dev/null || true
sleep 0.4

launchctl bootstrap "$DOMAIN" "$PLIST"
launchctl enable "$DOMAIN/$LABEL" 2>/dev/null || true
# 配置改过但进程还是旧的时候，这一步确保代码是最新的
launchctl kickstart -k "$DOMAIN/$LABEL" 2>/dev/null || true

# 等页面真的能打开再报成功，不让用户自己猜
i=0
while [ "$i" -lt 40 ]; do
  for p in 8123 8124 8125 8126 8127 8128 8129 8130; do
    if curl -fsS -m 0.3 "http://127.0.0.1:$p/index.html" 2>/dev/null | grep -q "作业板"; then
      echo "已装好，服务在后台常驻：http://127.0.0.1:$p/index.html"
      echo "  开机自启 · 关终端不影响 · 日志 $LOG"
      echo "  停止：$HERE/install-service.sh --uninstall"
      exit 0
    fi
  done
  i=$((i + 1))
  sleep 0.25
done

echo "服务已装载，但页面还没起来。看日志：tail -20 $LOG" >&2
exit 1
