# 屏幕时间管理 - 打包脚本 (PowerShell)
# 用法: 右键 -> 使用 PowerShell 运行

$AppName = "ScreenTimeManager"
$AppVersion = "1.0.0"

Set-Location $PSScriptRoot

Write-Host "[1/4] 编译代码 ..."
mvn clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) { Read-Host "编译失败，按回车退出"; exit }

Write-Host "[2/4] 准备文件 ..."
$staging = "target\staging"
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory $staging -Force | Out-Null
Copy-Item "target\screen-time-management-1.0-SNAPSHOT.jar" "$staging\" -Force

$repo = "$env:USERPROFILE\.m2\repository"
Get-ChildItem -Recurse "$repo\org\openjfx" -Filter "*.jar" | Where-Object Directory.Name -match "^\d+\.\d+" | Copy-Item -Destination $staging -Force
Get-ChildItem -Recurse "$repo\org\xerial" -Filter "*.jar" | Where-Object Directory.Name -match "^\d+\.\d+" | Copy-Item -Destination $staging -Force
Get-ChildItem -Recurse "$repo\net\java\dev\jna" -Filter "*.jar" | Where-Object Directory.Name -match "^\d+\.\d+" | Copy-Item -Destination $staging -Force
Remove-Item "$staging\javafx-maven-*" -Force -ErrorAction SilentlyContinue

java -cp "$staging\*" com.screentime.util.IconConverter "src\main\resources\icon.png" "target\icon.ico" 2>$null

Write-Host "[3/4] 打包 ..."
jpackage --type app-image --name $AppName --app-version $AppVersion `
    --vendor "ScreenTime" --description "屏幕时间管理" `
    --input $staging --main-jar "screen-time-management-1.0-SNAPSHOT.jar" `
    --main-class com.screentime.App `
    --java-options "-Dprism.order=sw" --icon "target/icon.ico" --dest dist

if ($LASTEXITCODE -ne 0) { Read-Host "打包失败，按回车退出"; exit }

Write-Host "[4/4] 修正配置 ..."
$cfg = "dist\$AppName\app\ScreenTimeManager.cfg"
$c = Get-Content $cfg; $r = @()
foreach ($l in $c) { $r += $l; if ($l -eq "[JavaOptions]") { $r += "java-options=--module-path=`$APPDIR"; $r += "java-options=--add-modules=ALL-MODULE-PATH" } }
Set-Content $cfg $r

Write-Host "`n======= 打包完成！======="
Write-Host "路径: $PSScriptRoot\dist\$AppName\$AppName.exe"
Read-Host "按回车退出"
