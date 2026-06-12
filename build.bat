@echo off
chcp 65001 >nul
title 屏幕时间管理 - 打包
setlocal enabledelayedexpansion

set "PROJECT_DIR=G:\桌面\CODE\Java\Screen-Time-Management"
set "APP_NAME=ScreenTimeManager"
set "APP_VERSION=1.0.0"
cd /d "%PROJECT_DIR%"

echo [1/4] 编译代码 ...
call mvn clean package -DskipTests -q
if errorlevel 1 ( echo 编译失败 & pause & exit /b 1 )

echo [2/4] 准备文件 ...
if exist "target\staging" rmdir /s /q "target\staging"
mkdir target\staging

copy target\screen-time-management-1.0-SNAPSHOT.jar target\staging\ >nul

:: 使用 PowerShell 复制依赖（避免 for /r 的 % 符号被解析）
powershell -ExecutionPolicy Bypass -Command ^
    "Get-ChildItem -Recurse \"$env:USERPROFILE\.m2\repository\org\openjfx\" -Filter *.jar | Where-Object { $_.Directory.Name -match '^\d+\.\d+' } | Copy-Item -Destination target\staging -Force" 2>nul
powershell -ExecutionPolicy Bypass -Command ^
    "Get-ChildItem -Recurse \"$env:USERPROFILE\.m2\repository\org\xerial\" -Filter *.jar | Where-Object { $_.Directory.Name -match '^\d+\.\d+' } | Copy-Item -Destination target\staging -Force" 2>nul
powershell -ExecutionPolicy Bypass -Command ^
    "Get-ChildItem -Recurse \"$env:USERPROFILE\.m2\repository\net\java\dev\jna\" -Filter *.jar | Where-Object { $_.Directory.Name -match '^\d+\.\d+' } | Copy-Item -Destination target\staging -Force" 2>nul

del target\staging\javafx-maven-*.jar 2>nul

echo 生成 ICO ...
java -cp "target\staging\*" com.screentime.util.IconConverter src\main\resources\icon.png target\icon.ico 2>nul

echo [3/4] 打包 ...
if not exist "dist" mkdir dist
jpackage --type app-image --name "%APP_NAME%" --app-version %APP_VERSION% ^
    --vendor "ScreenTime" --description "屏幕时间管理" ^
    --input target\staging --main-jar screen-time-management-1.0-SNAPSHOT.jar ^
    --main-class com.screentime.App ^
    --java-options "-Dprism.order=sw" --icon target/icon.ico --dest dist

if errorlevel 1 ( echo 打包失败 & pause & exit /b 1 )

echo [4/4] 修正启动配置 ...
powershell -ExecutionPolicy Bypass -Command ^
    "$cfg = 'dist\%APP_NAME%\app\ScreenTimeManager.cfg'; ^
    $c = Get-Content $cfg; ^
    $r = @(); ^
    foreach ($l in $c) { ^
        $r += $l; ^
        if ($l -eq '[JavaOptions]') { ^
            $r += 'java-options=--module-path=$APPDIR'; ^
            $r += 'java-options=--add-modules=ALL-MODULE-PATH' ^
        } ^
    }; ^
    Set-Content $cfg $r"

echo.
echo ======== 打包完成！========
echo 路径: %PROJECT_DIR%\dist\%APP_NAME%\ScreenTimeManager.exe
pause
