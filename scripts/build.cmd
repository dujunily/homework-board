@echo off
chcp 65001 >nul
title 作业板 · 构建
setlocal
cd /d "%~dp0"

rem 双击即可运行。默认打全部；也可以带参数，例如  build.cmd tv
rem -ExecutionPolicy Bypass 只对这一条命令生效，不改系统设置。

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*

echo.
if errorlevel 1 (
  echo 构建失败。上面对应的报错就是原因。
) else (
  echo 构建完成，产物在 dist\ 目录。
)
pause
endlocal
