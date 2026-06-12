# 1. 准备打包文件
mkdir target\staging
copy target\screen-time-management-1.0-SNAPSHOT.jar target\staging\

# 复制依赖
for /r "%USERPROFILE%\.m2\repository\org\openjfx" %f in (javafx-*.jar) do copy "%f" target\staging\ >nul 2>&1
for /r "%USERPROFILE%\.m2\repository\org\xerial" %f in (sqlite-jdbc*.jar) do copy "%f" target\staging\ >nul 2>&1
for /r "%USERPROFILE%\.m2\repository\net\java\dev\jna" %f in (jna-*.jar) do copy "%f" target\staging\ >nul 2>&1
del target\staging\javafx-maven-*.jar 2>nul

# 2. 生成图标
java -cp "target\staging\*" com.screentime.util.IconConverter src\main\resources\icon.png target\icon.ico

# 3. 打包
jpackage --type app-image --name ScreenTimeManager --app-version 1.0.0 ^
    --vendor "ScreenTime" --description "屏幕时间管理" ^
    --input target\staging --main-jar screen-time-management-1.0-SNAPSHOT.jar ^
    --main-class com.screentime.App ^
    --java-options "-Dprism.order=sw" --icon target/icon.ico --dest dist

# 4. 修正 cfg
powershell -Command "$cfg = 'dist\ScreenTimeManager\app\ScreenTimeManager.cfg'; $c = Get-Content $cfg; $r = @(); foreach ($l in $c) { $r += $l; if ($l -eq '[JavaOptions]') { $r += 'java-options=--module-path=$APPDIR'; $r += 'java-options=--add-modules=ALL-MODULE-PATH' } }; Set-Content $cfg $r"