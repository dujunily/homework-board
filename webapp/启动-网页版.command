#!/bin/sh
# macOS 双击入口（.command 文件双击会用「终端」执行）。
# 真正的逻辑在 start.sh，这里只是转发，保证三个平台行为一致。

cd "$(dirname "$0")" || exit 1
exec ./start.sh "$@"
