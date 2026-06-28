# 屏幕时间管理 - 打包脚本 (PowerShell)
# 用法: 在项目根目录执行 .\build.ps1

$ErrorActionPreference = "Stop"

$AppName = "ScreenTimeManager"
$AppVersion = "1.0.0"
$ProjectDir = $PSScriptRoot
$StagingDir = Join-Path $ProjectDir "target\staging"
$DistDir = Join-Path $ProjectDir "dist"
$MainJar = "screen-time-management-1.0-SNAPSHOT-jar-with-dependencies.jar"

Set-Location $ProjectDir

function Invoke-Step($Message, [scriptblock]$Action) {
    Write-Host $Message
    & $Action
}

function Resolve-CommandPath($Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        throw "找不到命令 $Name，请确认它已安装并加入 PATH。"
    }
    return $cmd.Source
}

Invoke-Step "[1/4] 编译代码 ..." {
    Resolve-CommandPath "mvn" | Out-Null
    mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "Maven 编译失败" }
}

Invoke-Step "[2/4] 准备文件 ..." {
    if (Test-Path $StagingDir) { Remove-Item -Recurse -Force $StagingDir }
    New-Item -ItemType Directory $StagingDir -Force | Out-Null

    $jarPath = Join-Path $ProjectDir "target\$MainJar"
    if (!(Test-Path $jarPath)) {
        throw "找不到打包产物：$jarPath"
    }
    Copy-Item $jarPath $StagingDir -Force

    $repo = Join-Path $env:USERPROFILE ".m2\repository"
    foreach ($groupPath in @("org\openjfx", "org\xerial", "net\java\dev\jna")) {
        $path = Join-Path $repo $groupPath
        if (Test-Path $path) {
            Get-ChildItem -Recurse $path -Filter "*.jar" |
                Where-Object { $_.Directory.Name -match "^\d+\.\d+" } |
                Copy-Item -Destination $StagingDir -Force
        }
    }

    Remove-Item (Join-Path $StagingDir "javafx-maven-*.jar") -Force -ErrorAction SilentlyContinue
    java -cp "$StagingDir\*" com.screentime.util.IconConverter "src\main\resources\icon.png" "target\icon.ico"
}

Invoke-Step "[3/4] 打包 ..." {
    Resolve-CommandPath "jpackage" | Out-Null
    if (!(Test-Path $DistDir)) { New-Item -ItemType Directory $DistDir -Force | Out-Null }

    jpackage --type app-image --name $AppName --app-version $AppVersion `
        --vendor "ScreenTime" --description "屏幕时间管理" `
        --input $StagingDir --main-jar $MainJar `
        --main-class com.screentime.Main `
        --java-options "-Dprism.order=sw" --icon "target\icon.ico" --dest $DistDir

    if ($LASTEXITCODE -ne 0) { throw "jpackage 打包失败" }
}

Invoke-Step "[4/4] 修正 JavaFX 模块配置 ..." {
    $cfg = Join-Path $DistDir "$AppName\app\$AppName.cfg"
    if (!(Test-Path $cfg)) { throw "找不到 jpackage 配置文件：$cfg" }

    $lines = Get-Content -Encoding UTF8 $cfg
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($line in $lines) {
        $result.Add($line)
        if ($line -eq "[JavaOptions]") {
            $result.Add("java-options=--module-path=`$APPDIR")
            $result.Add("java-options=--add-modules=ALL-MODULE-PATH")
        }
    }
    Set-Content -Encoding UTF8 $cfg $result
}

Write-Host ""
Write-Host "======== 打包完成 ========"
Write-Host "路径: $DistDir\$AppName\$AppName.exe"
