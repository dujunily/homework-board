#!/bin/sh
# macOS 双击入口（.command 文件双击会用「终端」执行）。
#
# 装过常驻服务（install-service.sh）之后，双击这里只会把页面打开，终端可以随手关掉；
# 没装过就是老行为：在终端里前台起服务，关窗口即停。
# 想彻底不用管终端：双击一次桌面上的「作业板」图标即可。

cd "$(dirname "$0")" || exit 1
exec ./launch.sh "$@"
