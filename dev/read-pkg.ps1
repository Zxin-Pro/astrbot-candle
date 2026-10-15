$ErrorActionPreference = "Continue"
$tar = "C:\Windows\system32\tar.exe"
$dd = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_deb"
$dt = Join-Path $dd "data.tar.xz"
$ex = Join-Path $dd "x"
Remove-Item $ex -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $ex -Force | Out-Null
Push-Location $ex
& $tar -xf $dt "./opt/QQ/resources/app/package.json" "./opt/QQ/version.json" "./opt/QQ/resources/app/app_launcher/index.js" "./opt/QQ/resources/app/app_launcher/launcher.js" 2>&1 | Out-Null
Pop-Location
$pj = Join-Path $ex "opt\QQ\resources\app\package.json"
if (Test-Path $pj) { Write-Host "=== resources/app/package.json ==="; Get-Content $pj -Raw } else { Write-Host "package.json NOT extracted" }
$vj = Join-Path $ex "opt\QQ\version.json"
if (Test-Path $vj) { Write-Host "=== opt/QQ/version.json ==="; Get-Content $vj -Raw }
$ix = Join-Path $ex "opt\QQ\resources\app\app_launcher\index.js"
if (Test-Path $ix) { Write-Host ("=== app_launcher/index.js (" + (Get-Item $ix).Length + " bytes) ==="); Get-Content $ix -Raw }
$lj = Join-Path $ex "opt\QQ\resources\app\app_launcher\launcher.js"
if (Test-Path $lj) { Write-Host ("=== app_launcher/launcher.js (" + (Get-Item $lj).Length + " bytes) ==="); Get-Content $lj -Raw }