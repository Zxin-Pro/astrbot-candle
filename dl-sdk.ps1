$ErrorActionPreference="Continue"
$dir = "C:\Users\zjh19\DSH\qqbot-sandbox\_sdk"
New-Item -ItemType Directory -Path $dir -Force | Out-Null
& curl.exe -sL --max-time 900 -o (Join-Path $dir "apktool.jar") "https://ghfast.top/https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar"
Write-Host ("apktool.jar " + (Get-Item (Join-Path $dir "apktool.jar")).Length)
& curl.exe -sL --max-time 900 -o (Join-Path $dir "cmdline-tools.zip") "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
Write-Host ("cmdline-tools.zip " + (Get-Item (Join-Path $dir "cmdline-tools.zip")).Length)
& curl.exe -sL --max-time 900 -o (Join-Path $dir "build-tools.zip") "https://dl.google.com/android/repository/build-tools_r34-windows.zip"
Write-Host ("build-tools.zip " + (Get-Item (Join-Path $dir "build-tools.zip")).Length)
Write-Host "ALL DONE"