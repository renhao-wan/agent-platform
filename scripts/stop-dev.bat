@echo off
chcp 65001 >nul
title AI 助手平台 - 一键停止
echo [stop] 按端口停止两个服务（MySQL 容器保留运行）...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8000 .*LISTENING"') do taskkill /F /PID %%p >nul 2>&1
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8090 .*LISTENING"') do taskkill /F /PID %%p >nul 2>&1
echo [stop] 完成。重新启动请运行 start-dev.bat
pause
