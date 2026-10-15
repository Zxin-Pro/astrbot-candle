$ErrorActionPreference = "Continue"
$JAVA = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$T = "C:\Users\zjh19\DSH\qqbot-sandbox\tools"
$deb = "C:\Users\zjh19\DSH\qqbot-sandbox\_research\QQ_arm64.deb"
$dd = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_deb"
Remove-Item $dd -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dd -Force | Out-Null
& $JAVA -cp $T ArTool $deb data.tar.xz $dd
$dt = Join-Path $dd "data.tar.xz"
Write-Host ("data.tar.xz = " + (Get-Item $dt).Length + " bytes")
$tar = "C:\Windows\system32\tar.exe"
$listing = & $tar -tf $dt 2>$null
Write-Host ("entries: " + $listing.Count)
Write-Host "--- 关键条目 ---"
$keys = @("./opt/QQ/qq","./opt/QQ/package.json","./opt/QQ/resources/app/package.json","./opt/QQ/chrome-sandbox","./opt/QQ/resources/app/app_launcher","./opt/QQ/icudtl.dat","./opt/QQ/resources.pak","./opt/QQ/libEGL.so","./opt/QQ/libffmpeg.so")
foreach ($k in $keys) {
  $hit = $listing | Where-Object { $_.TrimEnd("/") -eq $k.TrimEnd("/") }
  if ($hit) { Write-Host ("  OK   " + $k) } else { Write-Host ("  MISS " + $k) }
}
Write-Host "--- opt/QQ 顶层 ---"
$listing | Where-Object { $_ -match "^./opt/QQ/[^/]+/?$" } | Select-Object -First 40 | ForEach-Object { Write-Host ("  " + $_) }
Write-Host "--- app_launcher 下前 20 项 ---"
$listing | Where-Object { $_ -match "^./opt/QQ/resources/app/app_launcher/" } | Select-Object -First 20 | ForEach-Object { Write-Host ("  " + $_) }
$listing | Where-Object { $_ -match "^./opt/QQ/resources/app/app_launcher/" } | Measure-Object | ForEach-Object { Write-Host ("  app_launcher count = " + $_.Count) }
Write-Host "--- resources/app 顶层 ---"
$listing | Where-Object { $_ -match "^./opt/QQ/resources/app/[^/]+/?$" } | Select-Object -First 30 | ForEach-Object { Write-Host ("  " + $_) }