@echo off
chcp 65001 >nul
title 作业板 · 电脑端录入页
setlocal
cd /d "%~dp0"

rem 依次找一个能真正跑起来的 Python 3；都没有就用 PowerShell 内置服务兜底。
rem 注意 Windows 自带的 python.exe 只是应用商店的「占位别名」，
rem 直接 where python 会误判，所以这里用 python -c 真跑一句来判断。

py -3 -c "import sys" >nul 2>nul
if not errorlevel 1 goto use_py

python -c "import sys" >nul 2>nul
if not errorlevel 1 goto use_python

goto use_powershell

:use_py
py -3 serve.py %*
goto end

:use_python
python serve.py %*
goto end

:use_powershell
echo 没找到可用的 Python，改用 Windows 自带的 PowerShell 起服务……
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0serve.ps1" %*
if errorlevel 1 (
  echo.
  echo 启动失败。可以手动装一个 Python： https://www.python.org/downloads/
  pause
)

:end
endlocal
