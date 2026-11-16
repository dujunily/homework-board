#!/bin/sh
# 把网页端做成一个桌面图标（作业板.app），双击就打开录入页。
#
# 只在 macOS 上需要跑：换电脑、重装系统、或者删掉了图标时重来一次。
# Windows 用「启动-网页版.bat」，Linux 用「启动-网页版.command」。
#
# 双击之后的行为：
#   1. 服务已经在跑  → 直接把浏览器开起来
#   2. 服务第一次用  → 自动装成 launchd 常驻服务（后台、无窗口、开机自启），再开页面
# 全程不弹终端窗口。

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

	-- 已经在跑就只开页面
	try
		set openUrl to do shell script "cd '" & appDir & "' && ./launch.sh --probe"
	end try

	if openUrl = "" then
		-- 没在跑。第一次用的时候顺手把常驻服务装上（装过一次就一直有效，开机自启）
		try
			do shell script "cd '" & appDir & "' && ./install-service.sh >/dev/null 2>&1"
		end try
		-- 叫 launchd 把服务拉起来（同步等它就绪，最多 10 秒）
		try
			set openUrl to do shell script "cd '" & appDir & "' && ./launch.sh --start-only"
		end try
	end if

	if openUrl is not "" then
		open location openUrl
	else
		-- 服务没装或起不来：说清楚怎么办，别让人对着没反应的图标发愣
		activate
		display alert "服务没起来" message "先在终端里跑一次：
cd " & appDir & " && ./install-service.sh

装好后这个图标以后双击就直接开页面，不用再管终端。" as critical
	end if
end run
APPLESCRIPT

osacompile -o "$BUILD/$NAME.app" "$BUILD/launcher.applescript"
if [ -f "$HERE/icon/作业板.icns" ]; then
  cp "$HERE/icon/作业板.icns" "$BUILD/$NAME.app/Contents/Resources/applet.icns"
  # macOS 12+ 会优先吃 osacompile 顺手生成的 Assets.car，里面是默认的脚本图标；
  # 留着它，换上去的 icns 就不生效（桌面上还是一张白纸）
  rm -f "$BUILD/$NAME.app/Contents/Resources/Assets.car"
  # 同理，CFBundleIconName 是在 Assets.car 里找图标的开关，没有 car 就别留着
  /usr/libexec/PlistBuddy -c "Delete :CFBundleIconName" "$BUILD/$NAME.app/Contents/Info.plist" >/dev/null 2>&1 || true
fi

# 后台型 applet：双击时不在 Dock 上闪一下图标，点完就消失
/usr/libexec/PlistBuddy -c "Add :LSUIElement bool true" "$BUILD/$NAME.app/Contents/Info.plist" >/dev/null 2>&1 \
  || /usr/libexec/PlistBuddy -c "Set :LSUIElement true" "$BUILD/$NAME.app/Contents/Info.plist" >/dev/null 2>&1 || true

# 改过 Info.plist / 删过资源都算破坏签名，必须重签一次（ad-hoc 即可），
# 否则 macOS 会直接拒绝启动，报「应用已损坏」
codesign --force --deep --sign - "$BUILD/$NAME.app" 2>/dev/null || true

# 用 ditto 覆盖而不是删了重来：桌面不是我们该乱动的目录。
# 但 ditto 只合并不清理，桌面上旧包里那份 Assets.car 得单独点名删掉，
# 否则换上的图标永远被它盖着。
mkdir -p "$DESKTOP"
ditto "$BUILD/$NAME.app" "$DESKTOP/$NAME.app"
rm -f "$DESKTOP/$NAME.app/Contents/Resources/Assets.car"
codesign --force --deep --sign - "$DESKTOP/$NAME.app" 2>/dev/null || true
touch "$DESKTOP/$NAME.app"

echo "已放好：$DESKTOP/$NAME.app"
echo "双击它就会打开录入页（服务由 launchd 常驻，不会再弹终端窗口）"
