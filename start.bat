@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"
title AI 群聊 · 一键启动

echo ==================================================
echo   AgentScope 多 Agent 群聊 · 一键启动
echo ==================================================
echo.

rem ---- 是否首次运行：没有 .env，或 .env 里还没配好「主力发言模型」 ----
rem        数据库由程序首次启动时自动创建，不用它来判断（没有库 ≠ 没配置好）
set "NEED_SETUP="
if not exist ".env" (
  set "NEED_SETUP=1"
) else (
  findstr /b /i /r "APP_MODEL_CHAT_BASE_URL=." ".env" >nul 2>nul || set "NEED_SETUP=1"
  findstr /b /i /r "APP_MODEL_CHAT_API_KEY=."  ".env" >nul 2>nul || set "NEED_SETUP=1"
  findstr /b /i /r "APP_MODEL_CHAT_MODEL=."    ".env" >nul 2>nul || set "NEED_SETUP=1"
)

if defined NEED_SETUP (
  echo [首次运行] 没有检测到可用的 .env（或里面缺少「主力发言模型」配置），先带你填一下大模型接口。
  echo.
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup-env.ps1"
  if errorlevel 2 (
    echo.
    echo 已取消。修好之后重新双击本文件即可。
    echo.
    pause
    exit /b 1
  )
  echo.
  echo 配置已保存到 .env
  echo.
)

rem ---- 找 java ----
set "JAVA="
where java >nul 2>nul && set "JAVA=java"
if not defined JAVA if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA (
  echo [错误] 没有找到 java 命令。
  echo        请先安装 JDK 17：https://adoptium.net/temurin/releases/?version=17
  echo        安装时勾选 "Add to PATH"，装完重新打开本窗口再运行。
  echo.
  pause
  exit /b 1
)

rem ---- 找可直接运行的 jar（target\ 里，或 jar 和本文件放在一起）----
set "JAR="
for %%f in ("target\agentscope-group-chat-*.jar") do (
  if not defined JAR if exist "%%~ff" set "JAR=%%~ff"
)
if not defined JAR (
  for %%f in ("agentscope-group-chat-*.jar") do (
    if not defined JAR if exist "%%~ff" set "JAR=%%~ff"
  )
)

rem ---- 释放端口：如果上次没关干净的实例还占着 8080，先结束它，免得报 "Address already in use" ----
set "PORT=8080"
set "KILLED="
for /f "tokens=5" %%p in ('netstat -ano -p tcp ^| findstr /i "LISTENING" ^| findstr ":%PORT% "') do (
  if not "%%p"=="0" if not "%%p"=="4" (
    echo   [占用] 端口 %PORT% 正被进程 %%p 使用，正在结束它...
    taskkill /f /t /pid %%p >nul 2>nul
    set "KILLED=1"
  )
)
if defined KILLED (
  echo   已释放端口 %PORT%，等待端口回收...
  ping -n 3 127.0.0.1 >nul
) else (
  echo   端口 %PORT% 空闲。
)
echo.

set "RC=0"
if defined JAR (
  echo 使用 jar 启动：!JAR!
  echo 启动后会自动打开浏览器：http://localhost:8080
  echo （关闭本窗口 = 停止服务）
  echo.
  start "" /b cmd /c "ping -n 12 127.0.0.1 >nul & start "" http://localhost:8080/"
  "%JAVA%" -jar "!JAR!"
  set "RC=!errorlevel!"
) else (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo [错误] 没有找到可直接运行的 jar，也没有找到 mvn。
    echo        两种做法任选其一：
    echo          a^) 编译好再启动：mvn package -DskipTests  然后重新双击本文件；
    echo          b^) 用 IntelliJ IDEA 打开本工程，直接运行主类。
    echo.
    pause
    exit /b 1
  )
  echo 没有找到 jar，改用 Maven 启动（首次会下载依赖，可能要几分钟）...
  echo 启动后会自动打开浏览器：http://localhost:8080
  echo （关闭本窗口 = 停止服务）
  echo.
  start "" /b cmd /c "ping -n 30 127.0.0.1 >nul & start "" http://localhost:8080/"
  call mvn spring-boot:run
  set "RC=!errorlevel!"
)

echo.
if "!RC!"=="0" (
  echo 服务已停止。
) else (
  echo 服务异常退出（错误码 !RC!）。往上翻可以看到具体报错。
)
echo.
pause
endlocal