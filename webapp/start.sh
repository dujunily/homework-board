#!/bin/sh
# 作业板 · 电脑端录入页启动器（macOS / Linux）
# 起一个本地静态服务再打开浏览器。关掉这个终端窗口即停止。

cd "$(dirname "$0")" || exit 1
HERE=$(pwd)

# 找一个能用的 Python 3
PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1; then
    if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
      PY="$candidate"
      break
    fi
  fi
done

if [ -z "$PY" ]; then
  echo "──────────────────────────────────────────────"
  echo " 没找到 Python 3，起不了本地服务。"
  echo ""
  echo " macOS：先在「终端」里跑一次  xcode-select --install"
  echo "        （或  brew install python）"
  echo " Linux： sudo apt install python3   /   sudo dnf install python3"
  echo ""
  echo " 装好后重新双击本文件即可。"
  echo "──────────────────────────────────────────────"
  # 双击运行时窗口会立刻关掉，停一下让人看清提示
  printf "按回车关闭…"
  read -r _
  exit 1
fi

exec "$PY" "$HERE/serve.py" "$@"
