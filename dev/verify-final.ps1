$java = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$apk = "C:\Users\zjh19\DSH\qqbot-sandbox\out\signed\termux-qqbot-aligned-debugSigned.apk"
$tools = "C:\Users\zjh19\DSH\qqbot-sandbox\tools"
$tmp = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_verify"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

$z = [System.IO.Compression.ZipFile]::OpenRead($apk)
Write-Host ("APK entries: " + $z.Entries.Count)
$so = $z.Entries | Where-Object { $_.FullName -eq "lib/arm64-v8a/libtermux-bootstrap.so" }
Write-Host ("bootstrap stored=" + $so.Length + " compressed=" + $so.CompressedLength)
$soPath = Join-Path $tmp "bootstrap.so"
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($so, $soPath, $true)
$z.Dispose()

$listPath = Join-Path $tmp "list.txt"
& $java -cp $tools RepackBootstrap list $soPath 1328 29388903 2>$null | Set-Content -Encoding ASCII $listPath
$lines = @(Get-Content $listPath)
Write-Host ("ZIP entries: " + $lines.Count)
Write-Host ("qqbot entries: " + @($lines | Where-Object { $_ -like "opt/qqbot/*" -or $_ -like "etc/profile.d/50-qqbot*" }).Count)
Write-Host ("share/LICENSES kept: " + @($lines | Where-Object { $_ -like "share/LICENSES*" }).Count)
Write-Host ("share/man|doc|info leftover: " + @($lines | Where-Object { $_ -like "share/man*" -or $_ -like "share/doc*" -or $_ -like "share/info*" }).Count)

foreach ($f in @("opt/qqbot/sandbox/run-napcat.sh", "opt/qqbot/steps/90-summary.sh", "etc/profile.d/50-qqbot-sandbox.sh", "opt/qqbot/sandbox/60-astrbot.sh", "opt/qqbot/steps/70-launcher.sh")) {
  $out = Join-Path $tmp ([System.IO.Path]::GetFileName($f))
  & $java -cp $tools RepackBootstrap cat $soPath 1328 29388903 $f $out 2>$null | Out-Null
  Write-Host ""
  Write-Host ("---- " + $f + " ----")
  if (Test-Path $out) {
    Write-Host ("  len=" + (Get-Item $out).Length)
    Get-Content $out | Select-String -Pattern "qqbot|Token|NAPCAT_WEBUI|6185|6099|6199|Astrbot123" | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
  } else { Write-Host "  MISSING" }
}

Write-Host ""
Write-Host ("APK sha256: " + (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower())
Write-Host ("APK bytes : " + (Get-Item $apk).Length)