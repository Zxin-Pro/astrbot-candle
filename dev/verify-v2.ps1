$ErrorActionPreference = "Continue"
$java = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$javac = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\javac.exe"
$tools = "C:\Users\zjh19\DSH\qqbot-sandbox\tools"
$apk = "C:\Users\zjh19\DSH\qqbot-sandbox\out\signed\termux-qqbot-aligned-debugSigned.apk"
$tmp = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_v2"
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

# ELF header byte-for-byte compare with pristine
$pri = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_pristine-bootstrap.so"
$a = [System.IO.File]::ReadAllBytes($soPath)
$b = [System.IO.File]::ReadAllBytes($pri)
Write-Host ("sizes: new=" + $a.Length + " pristine=" + $b.Length)
$same = $true
for ($i = 0; $i -lt 1328; $i++) { if ($a[$i] -ne $b[$i]) { $same = $false; break } }
Write-Host ("ELF prefix [0,1328) identical: " + $same)
$same2 = $true
for ($i = 29390231; $i -lt 29392992; $i++) { if ($a[$i] -ne $b[$i]) { $same2 = $false; break } }
Write-Host ("ELF suffix [29390231,29392992) identical: " + $same2)

$listPath = Join-Path $tmp "list.txt"
& $java -cp $tools RepackBootstrap list $soPath 1328 29388903 2>$null | Set-Content -Encoding ASCII $listPath
$lines = @(Get-Content $listPath)
Write-Host ("ZIP entries: " + $lines.Count)
Write-Host ("qqbot entries: " + @($lines | Where-Object { $_ -like "opt/qqbot/*" -or $_ -like "etc/profile.d/50-qqbot*" }).Count)
Write-Host ("share/LICENSES kept: " + @($lines | Where-Object { $_ -like "share/LICENSES*" }).Count)
Write-Host ("share/man|doc|info leftover: " + @($lines | Where-Object { $_ -like "share/man*" -or $_ -like "share/doc*" -or $_ -like "share/info*" }).Count)

foreach ($f in @("opt/qqbot/common.sh","opt/qqbot/steps/20-rootfs.sh","opt/qqbot/sandbox/30-apt.sh","opt/qqbot/sandbox/50-napcat.sh","opt/qqbot/sandbox/run-napcat.sh","opt/qqbot/sandbox/40-qq.sh","opt/qqbot/deploy.sh")) {
  $out = Join-Path $tmp ([System.IO.Path]::GetFileName($f))
  & $java -cp $tools RepackBootstrap cat $soPath 1328 29388903 $f $out 2>$null | Out-Null
  Write-Host ""
  Write-Host ("---- " + $f + " ----")
  if (Test-Path $out) {
    Write-Host ("  len=" + (Get-Item $out).Length)
    Get-Content $out | Select-String -Pattern "ubuntu-ports|QQBOT_APT_MIRRORS|libnotify4|libsecret|libatspi|NAPCAT_WEBUI_SECRET|cp -rn|onebot11|ws://127" | ForEach-Object { Write-Host ("  > " + $_.Line.Trim()) }
  } else { Write-Host "  MISSING" }
}

Write-Host ""
Write-Host "==== VerifyBootstrap2 (simulate getZip + ZipInputStream) ===="
& $javac -d $tools (Join-Path $tools "VerifyBootstrap2.java") 2>$null
& $java -cp $tools VerifyBootstrap2 $soPath $pri 1328 29388903 29390231
Write-Host ""
Write-Host ("APK sha256: " + (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower())
Write-Host ("APK bytes : " + (Get-Item $apk).Length)