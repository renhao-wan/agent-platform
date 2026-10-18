@echo off
chcp 65001 >nul
title AI 助手平台 - 一键启动
echo [start] 1/3 启动中间件容器（MySQL 3307）...
docker compose -f "%~dp0..\docker-compose.dev.yml" up -d
echo [start] 2/3 启动 meeting-booking (:8090)...
cd /d D:\projects\dist-system-idtp\business-systems\meeting-booking
start "meeting-booking" /min javaw -jar target\meeting-booking-0.1.0-SNAPSHOT.jar
echo [start] 3/3 启动 agent-platform (:8000, local profile)...
cd /d D:\projects\dist-system-idtp\agent-platform
start "agent-platform" /min javaw -jar target\agent-platform-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
echo [start] 等待服务就绪（约 15 秒）...
timeout /t 15 /nobreak >nul
curl -s --max-time 5 http://localhost:8000/ping >nul 2>&1 && (echo [start] 平台 OK  → http://localhost:8000/) || (echo [start] 平台未就绪，请稍后刷新浏览器)
curl -s --max-time 5 http://localhost:8090/ping >nul 2>&1 && (echo [start] booking OK → http://localhost:8090/swagger-ui.html) || echo [start] booking 未就绪
echo [start] 全部完成。控制台：http://localhost:8000/  （登录：admin / admin123）
pause
