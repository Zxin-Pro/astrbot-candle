$ErrorActionPreference="Continue"
$dir = "C:\Users\zjh19\DSH\qqbot-sandbox\_sdk"
& curl.exe -sL --max-time 900 -o (Join-Path $dir "platform-34.zip") "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
Write-Host ("platform-34.zip " + (Get-Item (Join-Path $dir "platform-34.zip")).Length)
Write-Host "PLATFORM DONE"