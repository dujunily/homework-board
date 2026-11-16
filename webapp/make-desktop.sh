#!/bin/sh
# 把网页端做成一个桌面图标（作业板.app），双击就打开录入页。
#
# 只在 macOS 上需要跑：换电脑、重装系统、或者删掉了图标时重来一次。
# Windows 用「启动-网页版.bat」，Linux 用「启动-网页版.command」。
#
# 双击之后的行为：
#   1. 服务已经在跑  → 直接把浏览器开起来，不会起第二个服务
#   2. 服务没在跑    → 后台起 serve.py（浏览器自己会弹出来），
#                      日志在 /tmp/作业板-网页端.log，进程号在 webapp/.serve.pid
# 想停服务：kill $(cat .serve.pid)，或者重启电脑。

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
NAME="作业板"
DESKTOP="${DESKTOP_DIR:-$HOME/Desktop}"

if [ "$(uname)" != "Darwin" ]; then
  echo "这个脚本只在 macOS 上有意义。"
  exit 1
fi

# 图标资源没有就试着自己画一张；机器上没装 Pillow 就退回系统默认图标，不影响能用
if [ ! -f "$HERE/icon/作业板.icns" ] && [ -f "$HERE/make-icon.py" ]; then
  python3 "$HERE/make-icon.py" || echo "（图标没生成出来，先用系统默认图标，不影响使用）"
fi

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT

cat > "$BUILD/launcher.applescript" <<APPLESCRIPT
on run
	set appDir to "$HERE"
	set openUrl to ""
	try
		set openUrl to do shell script "cd '" & appDir & "' && ./launch.sh --probe"
	end try
	if openUrl is not "" then
		open location openUrl
		return
	end if
	do shell script "cd '" & appDir & "' && sh -c './launch.sh >/tmp/作业板-网页端.log 2>&1 & echo \$! > .serve.pid'"
end run
APPLESCRIPT

osacompile -o "$BUILD/$NAME.app" "$BUILD/launcher.applescript"
if [ -f "$HERE/icon/作业板.icns" ]; then
  cp "$HERE/icon/作业板.icns" "$BUILD/$NAME.app/Contents/Resources/applet.icns"
fi

# 用 ditto 覆盖而不是删了重来：桌面不是我们该乱动的目录
mkdir -p "$DESKTOP"
ditto "$BUILD/$NAME.app" "$DESKTOP/$NAME.app"
touch "$DESKTOP/$NAME.app"

echo "已放好：$DESKTOP/$NAME.app"
echo "双击它就会打开 http://127.0.0.1:8123/ （端口被占时自动顺延）"
