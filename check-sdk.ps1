$ProgressPreference = "SilentlyContinue"
$java = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$res = "C:\Users\zjh19\DSH\qqbot-sandbox\_research"

Write-Host "=== termux-app v0.118.3 gradle.properties ==="
$gp = Join-Path $res "termux-gradle.properties"
curl.exe -sL --max-time 60 -o $gp "https://ghfast.top/https://raw.githubusercontent.com/termux/termux-app/v0.118.3/gradle.properties"
Get-Content $gp | Select-String -Pattern "Sdk|ndk" | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }

Write-Host ""
Write-Host "=== original termux-arm64.apk signature ==="
& $java -jar (Join-Path $res "uber-apk-signer.jar") -a (Join-Path $res "termux-arm64.apk") -y 2>&1 | Select-String -Pattern "Subject|SHA256|verified|Expires|ERROR|error" | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }

Write-Host ""
Write-Host "=== final QQBot-Sandbox.apk signature ==="
& $java -jar (Join-Path $res "uber-apk-signer.jar") -a "C:\Users\zjh19\DSH\qqbot-sandbox\QQBot-Sandbox.apk" -y 2>&1 | Select-String -Pattern "Subject|SHA256|verified|Expires|ERROR|error" | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }